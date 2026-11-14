package com.example.djicontroller.core

import com.example.djicontroller.core.fake.FakeCamera
import com.example.djicontroller.core.fake.FakeClock
import com.example.djicontroller.core.fake.FakeFlightControl
import com.example.djicontroller.core.fake.FakeStation
import com.example.djicontroller.core.fake.FakeTelemetry
import com.example.djicontroller.core.fake.FakeVision
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.Velocity3D
import com.example.djicontroller.core.port.Ticker
import kotlinx.coroutines.yield
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Świat testowy lądowania na czasie wirtualnym: kinematyka drona nad płytą, pętla Virtual Stick
 * z watchdogiem, auto-lądowanie DJI z ochroną lądowania, kamera z detekcją markerów i stacja.
 *
 * Jest jednocześnie [Ticker]em planera: każde `sleep(ms)` przesuwa [FakeClock] i symulację o `ms`,
 * więc minuty lotu liczą się w milisekundach, a wynik jest deterministyczny.
 *
 * Położenie drona: [north]/[east] względem środka płyty (m), [alt] nad płytą (m), [headingDeg] kurs.
 */
class LandingWorld(
    val clock: FakeClock,
    val telemetry: FakeTelemetry,
    val flight: FakeFlightControl,
    val camera: FakeCamera,
    val vision: FakeVision,
    val station: FakeStation,
    val pad: GeoPoint = STATION,
) : Ticker {
    var north = 0.0
    var east = 0.0
    var alt = 8.0
    var headingDeg = 0.0
    /** Orientacja osi „góra” markera (kurs, w którym dron ma usiąść). */
    var padYawDeg = 0.0
    var flying = true
    var motorsOn = true

    /** Znoszenie w trakcie Virtual Stick (zawis GPS i auto-lądowanie trzymają pozycję). */
    var windNorthMps = 0.0
    var windEastMps = 0.0
    /** Stała czasowa dojścia do zadanej prędkości (Mini 4 Pro ≈ 0,3 s). */
    var tauS = 0.3
    /**
     * Przyspieszenie fizyki względem zegara (demo: `DroneSimulator.timeScale = 3` — dron porusza się
     * 3× szybciej, niż zakłada regulator; najtrudniejszy przypadek dla stabilności pętli).
     */
    var timeScale = 1.0
    /** Stały błąd GPS drona (pozycja z telemetrii minus prawdziwa), m. */
    var gpsBiasNorthM = 0.0
    var gpsBiasEastM = 0.0

    // --- kamera i detekcja
    var frameIntervalMs = 100L
    var latencyMs = 80L
    /** Dodatkowy warunek widoczności markera (np. zasłonięty) — oprócz pola widzenia. */
    var markerVisible: LandingWorld.() -> Boolean = { true }
    /** Detekcja „zawieszona”: brak nowych wyników (np. przeciążony telefon, zerwany strumień wideo). */
    var visionFrozen: LandingWorld.() -> Boolean = { false }
    /** Poniżej tej wysokości detekcja wybiera mały marker (id 3), wyżej duży (id 16). */
    var smallMarkerBelowM = 1.2
    var fovHalfDeg = 35.0
    /** Duży marker wykrywany tylko do tej wysokości (rozdzielczość obrazu). */
    var detectionCeilingM = 14.0
    /** Marker wykryty, ale poza odrzucona (np. błąd reprojekcji) — klatka bez obserwacji. */
    var poseRejected: LandingWorld.() -> Boolean = { false }
    /** Szum pozy: odchylenie standardowe położenia jako ułamek wysokości, kursu w stopniach. */
    var noiseFraction = 0.0
    var yawNoiseDeg = 0.0
    val random = java.util.Random(1)

    // --- auto-lądowanie DJI
    var landingProtectionAltM = 0.3
    var autoDescentMps = 0.5
    var confirmationNeeded = false
        private set
    private var confirmed = false
    private var protectionDone = false
    /** Stacja wykrywa drona na płycie po przyziemieniu (czujnik obecności). */
    var stationDetectsDrone = true

    /** Odległość od środka płyty w chwili przyziemienia; `null` = jeszcze w powietrzu. */
    var touchdownOffsetM: Double? = null
        private set
    var touchdownHeadingDeg: Double? = null
        private set

    /** Historia: (czas, wysokość, właściciel, zadany punkt) — do asercji. */
    data class Sample(val t: Long, val alt: Double, val owner: ControlOwner, val setpoint: Setpoint)
    val samples = mutableListOf<Sample>()

    /** Gdy `true`, położenie startowe zostanie przejęte z telemetrii (np. po przelocie misji w TestRig). */
    var syncFromTelemetry = false

    private var vN = 0.0
    private var vE = 0.0
    private var vU = 0.0
    private var seenSetpoints = 0
    private var current = Setpoint.HOVER
    private var currentAt = 0L
    private var nextFrameAt = 0L

    init {
        flight.onConfirmLanding = { if (confirmationNeeded) confirmed = true }
    }

    fun placeAt(north: Double, east: Double, alt: Double, headingDeg: Double = 0.0) {
        this.north = north
        this.east = east
        this.alt = alt
        this.headingDeg = headingDeg
        publishTelemetry()
    }

    override suspend fun sleep(ms: Long) {
        var left = ms
        while (left > 0) {
            val dt = min(left, STEP_MS)
            step(dt)
            left -= dt
        }
        yield()
    }

    private fun step(dtMs: Long) {
        if (syncFromTelemetry) {
            syncFromTelemetry = false
            val s = telemetry.state.value
            s.location?.let { loc -> pad.northEastTo(loc).let { (n, e) -> north = n; east = e } }
            s.altitudeM?.let { alt = it }
            s.headingDeg?.let { headingDeg = it }
        }
        clock.advance(dtMs)
        val now = clock.monotonicMs()
        val dt = dtMs / 1000.0

        if (flight.setpoints.size != seenSetpoints) {
            seenSetpoints = flight.setpoints.size
            current = flight.setpoints.last()
            currentAt = now
        }
        val owner = flight.arbiter.owner.value
        var cmdN = 0.0
        var cmdE = 0.0
        var cmdU = 0.0
        var yawRate = 0.0
        var sent = Setpoint.HOVER
        when (owner) {
            ControlOwner.VIRTUAL_STICK -> {
                // Watchdog pętli 20 Hz: cel nieodświeżany przez 200 ms → HOVER.
                sent = if (now - currentAt <= WATCHDOG_MS) current else Setpoint.HOVER
                val h = Math.toRadians(headingDeg)
                cmdN = sent.forwardMps * cos(h) - sent.rightMps * sin(h)
                cmdE = sent.forwardMps * sin(h) + sent.rightMps * cos(h)
                cmdU = sent.upMps
                yawRate = sent.yawRateDegS
            }
            ControlOwner.AUTO_LANDING -> if (flying) {
                if (!protectionDone && !confirmationNeeded && alt <= landingProtectionAltM) confirmationNeeded = true
                if (confirmationNeeded && confirmed) {
                    confirmationNeeded = false
                    protectionDone = true
                }
                cmdU = if (confirmationNeeded) 0.0 else -autoDescentMps
            }
            ControlOwner.MISSION, ControlOwner.NONE -> Unit // zawis GPS
        }
        val dtSim = dt * timeScale
        val k = min(1.0, dtSim / tauS)
        vN += (cmdN - vN) * k
        vE += (cmdE - vE) * k
        vU += (cmdU - vU) * k
        if (flying) {
            val windOn = owner == ControlOwner.VIRTUAL_STICK
            north += (vN + if (windOn) windNorthMps else 0.0) * dtSim
            east += (vE + if (windOn) windEastMps else 0.0) * dtSim
            alt = max(0.0, alt + vU * dtSim)
            headingDeg = normalize(headingDeg + yawRate * dtSim)
            if (alt <= 0.0 && owner == ControlOwner.AUTO_LANDING) touchDown()
        }
        publishTelemetry()
        if (now % 100 < dtMs) samples += Sample(now, alt, owner, sent)

        if (vision.users.value.isNotEmpty() && now >= nextFrameAt) {
            nextFrameAt = now + frameIntervalMs
            if (!visionFrozen()) publishFrame(now)
        }
        onStep?.invoke(this)
    }

    /** Wołane po każdym kroku symulacji — test może tu wcisnąć STOP albo zasymulować zdarzenie w locie. */
    var onStep: (LandingWorld.() -> Unit)? = null

    private fun touchDown() {
        flying = false
        motorsOn = false
        vN = 0.0; vE = 0.0; vU = 0.0
        touchdownOffsetM = hypot(north, east)
        touchdownHeadingDeg = headingDeg
        flight.arbiter.release(ControlOwner.AUTO_LANDING) // jak FlightControl po wyłączeniu silników
        station.publish(dronePresent = stationDetectsDrone && hypot(north, east) < 0.5, padClear = true)
    }

    private fun publishFrame(now: Long) {
        val captureAt = now - latencyMs
        // Pozycja w chwili zrobienia zdjęcia (≈ cofnięta o prędkość × opóźnienie).
        val lat = latencyMs / 1000.0
        val n = north - vN * lat
        val e = east - vE * lat
        val a = alt - vU * lat
        val h = Math.toRadians(headingDeg)
        val relN = -n
        val relE = -e
        val dx = relN * cos(h) + relE * sin(h)
        val dy = -relN * sin(h) + relE * cos(h)
        val inFov = a in 0.15..detectionCeilingM && hypot(dx, dy) <= a * tan(Math.toRadians(fovHalfDeg))
        val gimbalDown = (camera.gimbalPitchDeg.value ?: 0.0) <= -85.0
        val obs = if (inFov && gimbalDown && markerVisible() && !poseRejected()) {
            val sigma = noiseFraction * a
            MarkerObservation(
                markerId = if (a < smallMarkerBelowM) 3 else 16,
                dxM = dx + random.nextGaussian() * sigma,
                dyM = dy + random.nextGaussian() * sigma,
                dzM = a * (1 + random.nextGaussian() * noiseFraction),
                yawDeg = normalize(padYawDeg - headingDeg + random.nextGaussian() * yawNoiseDeg),
                timestampMs = captureAt,
                latencyMs = latencyMs,
            )
        } else {
            null
        }
        vision.publish(obs, frameTimestampMs = captureAt)
    }

    private fun publishTelemetry() {
        telemetry.update {
            it.copy(
                location = pad.offsetBy(north + gpsBiasNorthM, east + gpsBiasEastM),
                altitudeM = alt,
                ultrasonicHeightM = if (alt < 5.0) alt else null,
                headingDeg = headingDeg,
                velocity = Velocity3D(vN, vE, -vU),
                isFlying = flying,
                areMotorsOn = motorsOn,
                isLandingConfirmationNeeded = confirmationNeeded,
                updatedAtMs = clock.monotonicMs(),
            )
        }
    }

    val maxAltitudeSeen: Double get() = samples.maxOfOrNull { it.alt } ?: alt

    companion object {
        const val STEP_MS = 10L
        const val WATCHDOG_MS = 200L

        fun normalize(deg: Double): Double {
            var d = deg % 360.0
            if (d > 180) d -= 360
            if (d <= -180) d += 360
            return d
        }
    }
}
