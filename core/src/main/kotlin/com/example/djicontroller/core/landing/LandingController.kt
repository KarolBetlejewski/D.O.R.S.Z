package com.example.djicontroller.core.landing

import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Nastawy regulatora lądowania (sekcje 5.5 i 9). Limity zależą od wysokości `h` nad płytą —
 * im niżej, tym ciaśniejszy stożek i wolniejszy ruch:
 *
 * - tolerancja błędu poziomego (stożek): `maxHorizontalError(h) = coneBaseM + coneSlope · h`
 * - maks. prędkość pozioma: `maxHorizontalSpeed(h) = clamp(minHorizontalMps + horizontalPerM · h, …, maxHorizontalMps)`
 * - maks. prędkość opadania: `maxDescentSpeed(h) = clamp(minDescentMps + descentPerM · h, …, maxDescentMps)`
 */
data class LandingControllerConfig(
    /** Wzmocnienie P w poziomie: m błędu → m/s. */
    val kp: Double = 0.6,
    /** Wzmocnienie D w poziomie: m/s zmiany błędu → m/s (tłumienie). */
    val kd: Double = 0.25,
    /** Filtr pochodnej liczonej z kolejnych klatek (gdy brak prędkości z telemetrii), 0..1. */
    val derivativeFilterAlpha: Double = 0.5,
    /** Pochodną z klatek liczymy tylko, gdy klatki dzieli najwyżej tyle ms (dłuższa przerwa = reset). */
    val maxDerivativeGapMs: Long = 500,
    /** Kompensacja wieku obserwacji prędkością drona (`KeyAircraftVelocity`): błąd „na teraz”, nie „na klatkę”. */
    val compensateLatency: Boolean = true,
    val maxCompensatedLatencyMs: Long = 400,

    /**
     * Stożek: 0,12 + 0,08·h → 0,16 m przy przejściu do L4 (0,5 m), 0,28 m na 2 m, 0,60 m na 6 m.
     * Węższy stożek (0,08 + 0,06·h, 11 cm na 0,5 m) był na granicy tego, co utrzyma regulator przy
     * szumie pozy i opóźnieniu obrazu — dron wisiał nisko zamiast schodzić. Płyta stacji ma 45 cm.
     */
    val coneBaseM: Double = 0.12,
    val coneSlope: Double = 0.08,

    val minHorizontalMps: Double = 0.2,
    val horizontalPerM: Double = 0.12,
    val maxHorizontalMps: Double = 1.0,

    val minDescentMps: Double = 0.15,
    val descentPerM: Double = 0.07,
    val maxDescentMps: Double = 0.6,

    /** Obracanie do osi markera — dron siada na płycie we właściwej orientacji. */
    val alignYaw: Boolean = true,
    val kpYaw: Double = 0.8,
    val maxYawRateDegS: Double = 20.0,
    /** Opadanie wstrzymane, gdy |błąd kursu| > tyle stopni (tylko przy [alignYaw]). */
    val maxYawErrorDeg: Double = 10.0,
    /** Obrót dopiero, gdy błąd poziomy ≤ tyle tolerancji (daleko od środka najpierw centrowanie). */
    val yawWithinToleranceFactor: Double = 3.0,

    /** Wysokość z czujnika ToF (`KeyUltrasonicHeight`) jest wiarygodna poniżej tej wartości, m. */
    val ultrasonicTrustBelowM: Double = 3.0,
    /** Odczyt ToF poniżej tej wartości traktujemy jako błędny (np. 0 przed pierwszym pomiarem), m. */
    val ultrasonicMinM: Double = 0.05,
) {
    init {
        require(kp > 0 && kd >= 0) { "kp > 0, kd ≥ 0" }
        require(coneBaseM > 0 && coneSlope >= 0) { "Stożek: podstawa > 0, nachylenie ≥ 0" }
        require(minHorizontalMps in 0.0..maxHorizontalMps) { "Prędkość pozioma: min ≤ max" }
        require(minDescentMps > 0 && minDescentMps <= maxDescentMps) { "Prędkość opadania: 0 < min ≤ max" }
        require(derivativeFilterAlpha in 0.0..1.0) { "Filtr pochodnej: 0..1" }
        require(maxYawErrorDeg > 0) { "maxYawErrorDeg > 0" }
    }

    fun maxHorizontalErrorM(heightM: Double): Double = coneBaseM + coneSlope * max(0.0, heightM)

    fun maxHorizontalSpeedMps(heightM: Double): Double =
        (minHorizontalMps + horizontalPerM * max(0.0, heightM)).coerceIn(minHorizontalMps, maxHorizontalMps)

    fun maxDescentSpeedMps(heightM: Double): Double =
        (minDescentMps + descentPerM * max(0.0, heightM)).coerceIn(minDescentMps, maxDescentMps)
}

/** Skąd pochodzi wysokość nad płytą użyta do stożka i limitów. */
enum class HeightSource { ULTRASONIC, MARKER, ALTITUDE }

data class HeightEstimate(val heightM: Double, val source: HeightSource)

/** Skąd pochodzi człon D. */
enum class DerivativeSource { TELEMETRY, FRAMES, NONE }

/** Wynik kroku regulatora — punkt zadany i wszystko, co trafia do logu (Z5). */
data class LandingCommand(
    val setpoint: Setpoint,
    val markerId: Int,
    val frameTimestampMs: Long,
    val heightM: Double,
    val heightSource: HeightSource,
    /** Błąd poziomy po kompensacji opóźnienia (do przodu, w prawo), m. */
    val errorForwardM: Double,
    val errorRightM: Double,
    val horizontalErrorM: Double,
    val toleranceM: Double,
    val yawErrorDeg: Double,
    val withinCone: Boolean,
    val yawAligned: Boolean,
    val descentAllowed: Boolean,
    val derivativeSource: DerivativeSource,
    /** Zmiana markera (duży ↔ mały) w tej klatce — pochodna wyzerowana. */
    val markerSwitched: Boolean,
) {
    fun toLogFields(): Map<String, Any?> = linkedMapOf(
        "id" to markerId,
        "frame" to frameTimestampMs,
        "h" to heightM,
        "hSrc" to heightSource,
        "eF" to errorForwardM,
        "eR" to errorRightM,
        "err" to horizontalErrorM,
        "tol" to toleranceM,
        "yawErr" to yawErrorDeg,
        "cone" to withinCone,
        "yawOk" to yawAligned,
        "descend" to descentAllowed,
        "dSrc" to derivativeSource,
        "switch" to markerSwitched,
        "fwd" to setpoint.forwardMps,
        "right" to setpoint.rightMps,
        "up" to setpoint.upMps,
        "yawRate" to setpoint.yawRateDegS,
    )
}

/**
 * Regulator PD lądowania precyzyjnego (F3, sekcja 5.5). Czysty Kotlin, bez wątków i zegara —
 * czas przychodzi w argumencie.
 *
 * Osie: obserwacja jest już w układzie BODY drona (`dx` = przód, `dy` = prawo), więc
 * `forward = Kp·dx + Kd·d(dx)/dt`, `right = Kp·dy + Kd·d(dy)/dt` — marker przed dronem daje lot do przodu.
 * Pochodna błędu = −prędkość drona z telemetrii (płyta stoi); bez telemetrii — różnica kolejnych
 * **nowych** klatek (ta sama klatka powtórzona w pętli 20 Hz nie zmienia pochodnej, brak dzielenia przez 0).
 *
 * Pionowo (zasada bezpieczeństwa stożka): `up < 0` TYLKO gdy `hypot(dx, dy) ≤ maxHorizontalError(h)`
 * i `|yaw| ≤ maxYawError`; w przeciwnym razie `up = 0` — dron tylko centruje się w poziomie.
 */
class LandingController(val config: LandingControllerConfig = LandingControllerConfig()) {

    private var lastMarkerId: Int? = null
    private var lastFrameMs: Long? = null
    private var lastErrorForward = 0.0
    private var lastErrorRight = 0.0
    private var rateForward = 0.0
    private var rateRight = 0.0
    private var haveRate = false

    /** Zapomina historię (nowa próba lądowania). */
    fun reset() {
        lastMarkerId = null
        lastFrameMs = null
        rateForward = 0.0
        rateRight = 0.0
        haveRate = false
    }

    /**
     * Wysokość nad płytą: ToF (`KeyUltrasonicHeight`) w zakresie wiarygodności, potem `dz` markera,
     * na końcu `KeyAltitude` (wysokość względem punktu startu = stacji). `null`, gdy nic nie wiadomo.
     */
    fun estimateHeight(observation: MarkerObservation?, drone: DroneState): HeightEstimate? {
        val c = config
        val tof = drone.ultrasonicHeightM
        if (tof != null && tof.isFinite() && tof >= c.ultrasonicMinM && tof < c.ultrasonicTrustBelowM) {
            return HeightEstimate(tof, HeightSource.ULTRASONIC)
        }
        val dz = observation?.dzM
        if (dz != null && dz.isFinite() && dz > 0) return HeightEstimate(dz, HeightSource.MARKER)
        val alt = drone.altitudeM
        if (alt != null && alt.isFinite()) return HeightEstimate(max(0.0, alt), HeightSource.ALTITUDE)
        return null
    }

    /**
     * Jeden krok regulatora.
     * @param nowMs czas monotoniczny wywołania — do kompensacji wieku obserwacji
     */
    fun update(observation: MarkerObservation, drone: DroneState, nowMs: Long): LandingCommand {
        val c = config
        val height = estimateHeight(observation, drone) ?: HeightEstimate(observation.dzM, HeightSource.MARKER)
        val h = max(0.0, height.heightM)

        // Zmiana markera (duży ↔ mały): inne źródło pozy, inny szum — pochodna z klatek od zera.
        val switched = lastMarkerId != null && lastMarkerId != observation.markerId
        if (switched) {
            lastFrameMs = null
            rateForward = 0.0
            rateRight = 0.0
            haveRate = false
        }

        val body = drone.bodyVelocity()
        var ef = observation.dxM
        var er = observation.dyM
        if (c.compensateLatency && body != null) {
            val age = (nowMs - observation.timestampMs).coerceIn(0L, c.maxCompensatedLatencyMs) / 1000.0
            ef -= body.forwardMps * age
            er -= body.rightMps * age
        }

        // Pochodna z klatek — tylko dla nowej klatki i rozsądnego odstępu.
        val prevFrame = lastFrameMs
        if (prevFrame == null || observation.timestampMs != prevFrame) {
            if (prevFrame != null) {
                val gapMs = observation.timestampMs - prevFrame
                if (gapMs in 1..c.maxDerivativeGapMs) {
                    val dt = gapMs / 1000.0
                    val a = c.derivativeFilterAlpha
                    val rf = (observation.dxM - lastErrorForward) / dt
                    val rr = (observation.dyM - lastErrorRight) / dt
                    rateForward = if (haveRate) a * rf + (1 - a) * rateForward else rf
                    rateRight = if (haveRate) a * rr + (1 - a) * rateRight else rr
                    haveRate = true
                } else {
                    haveRate = false
                    rateForward = 0.0
                    rateRight = 0.0
                }
            }
            lastFrameMs = observation.timestampMs
            lastErrorForward = observation.dxM
            lastErrorRight = observation.dyM
        }
        lastMarkerId = observation.markerId

        val dSource: DerivativeSource
        val dF: Double
        val dR: Double
        when {
            body != null -> { dSource = DerivativeSource.TELEMETRY; dF = -body.forwardMps; dR = -body.rightMps }
            haveRate -> { dSource = DerivativeSource.FRAMES; dF = rateForward; dR = rateRight }
            else -> { dSource = DerivativeSource.NONE; dF = 0.0; dR = 0.0 }
        }

        var vf = c.kp * ef + c.kd * dF
        var vr = c.kp * er + c.kd * dR
        val vMax = c.maxHorizontalSpeedMps(h)
        val v = hypot(vf, vr)
        if (v > vMax) {
            vf *= vMax / v
            vr *= vMax / v
        }

        val error = hypot(ef, er)
        val tolerance = c.maxHorizontalErrorM(h)
        val yawError = observation.yawDeg
        val withinCone = error <= tolerance
        val yawAligned = !c.alignYaw || abs(yawError) <= c.maxYawErrorDeg
        val descentAllowed = withinCone && yawAligned
        val up = if (descentAllowed) -c.maxDescentSpeedMps(h) else 0.0
        val yawRate = if (c.alignYaw && error <= tolerance * c.yawWithinToleranceFactor) {
            (c.kpYaw * yawError).coerceIn(-c.maxYawRateDegS, c.maxYawRateDegS)
        } else {
            0.0
        }

        val setpoint = Setpoint(vf, vr, up, yawRate)
        val safe = if (setpoint.forwardMps.isFinite() && setpoint.rightMps.isFinite() && setpoint.yawRateDegS.isFinite()) {
            setpoint
        } else {
            Setpoint.HOVER // Z4: nieskończoność / NaN z danych wejściowych → zawis
        }
        return LandingCommand(
            setpoint = safe,
            markerId = observation.markerId,
            frameTimestampMs = observation.timestampMs,
            heightM = h,
            heightSource = height.source,
            errorForwardM = ef,
            errorRightM = er,
            horizontalErrorM = error,
            toleranceM = tolerance,
            yawErrorDeg = yawError,
            withinCone = withinCone,
            yawAligned = yawAligned,
            descentAllowed = descentAllowed && safe === setpoint,
            derivativeSource = dSource,
            markerSwitched = switched,
        )
    }
}
