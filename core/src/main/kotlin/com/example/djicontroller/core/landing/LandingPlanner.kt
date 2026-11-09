package com.example.djicontroller.core.landing

import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.safety
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.core.port.VisionPort
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min

/** Pod-stany lądowania (sekcja 5.5). */
enum class LandingStep(val label: String) {
    L1_APPROACH("L1 dolot"),
    L2_SEARCH("L2 szukanie markera"),
    L3_DESCENT("L3 centrowanie i opadanie"),
    L4_TOUCHDOWN("L4 przyziemienie"),
    L5_VERIFY("L5 weryfikacja"),
}

/** Bieżący pod-stan lądowania do UI i logów. */
data class LandingPhase(
    val step: LandingStep,
    val attempt: Int,
    val detail: String = "",
    val heightM: Double? = null,
    val horizontalErrorM: Double? = null,
    val toleranceM: Double? = null,
)

data class LandingPlannerConfig(
    // L1
    /** Odległość pozioma od stacji, w której uznajemy dolot za zakończony, m. */
    val approachRadiusM: Double = 3.0,
    val approachMinAltitudeM: Double = 5.0,
    val approachMaxAltitudeM: Double = 12.0,
    /** Wysokość, na którą L1 koryguje drona spoza zakresu, m. */
    val approachTargetAltitudeM: Double = 8.0,
    val approachTimeoutMs: Long = 30_000,
    val verticalAdjustMps: Double = 1.0,
    // L2
    val gimbalTimeoutMs: Long = 3_000,
    /** N sekund bez markera → wzniesienie i ponowne szukanie. */
    val searchTimeoutMs: Long = 5_000,
    val searchClimbM: Double = 3.0,
    val maxSearchClimbs: Int = 2,
    val maxSearchAltitudeM: Double = 15.0,
    // L3
    val maxObservationAgeMs: Long = 400,
    /**
     * Marker niewidoczny dłużej niż tyle w L3 → przerwanie opadania, wzniesienie, powrót do L2 (kolejna próba).
     * Liczy się tylko czas, w którym przychodzą ŚWIEŻE wyniki detekcji bez markera.
     */
    val markerLostTimeoutMs: Long = 1_500,
    /**
     * Przestój potoku wizyjnego (brak nowych wyników — np. przycięcie CPU, utrata obrazu): dron wisi bez opadania
     * i czeka; dopiero po tylu ms bez żadnego obrazu lądowanie jest przerywane jak przy utracie markera.
     */
    val visionStallTimeoutMs: Long = 6_000,
    /** Ostatnia klatka starsza niż tyle → powtarzamy korektę poziomą, ale bez opadania „na ślepo”, ms. */
    val blindDescentMaxAgeMs: Long = 250,
    val lostClimbM: Double = 2.0,
    /** Wysokość przejścia do L4, m. */
    val transitionHeightM: Double = 0.5,
    /** Poniżej tej wysokości wierzymy czujnikowi ToF (`KeyUltrasonicHeight`) bardziej niż odległości do markera, m. */
    val ultrasonicTrustBelowM: Double = 3.0,
    /** Marker zniknął tuż nad płytą (zbyt blisko kamery): dopuszczamy L4, jeśli ToF ≤ tyle i dron był wycentrowany, m. */
    val blindFinalMaxHeightM: Double = 0.8,
    val periodMs: Long = 50,
    // L4
    val touchdownTimeoutMs: Long = 30_000,
    val confirmRetryMs: Long = 2_000,
    // L5
    val stationConfirmTimeoutMs: Long = 15_000,
) {
    init {
        require(approachMinAltitudeM < approachMaxAltitudeM) { "Zakres wysokości dolotu" }
        require(approachTargetAltitudeM in approachMinAltitudeM..approachMaxAltitudeM) { "Wysokość docelowa L1 poza zakresem" }
        require(transitionHeightM > 0) { "Wysokość przejścia do L4 musi być dodatnia" }
        require(maxSearchClimbs >= 0) { "maxSearchClimbs ≥ 0" }
    }
}

/**
 * Precyzyjne lądowanie na płycie stacji — maszyna pod-stanów L1–L5 (F3, sekcja 5.5). Czysty Kotlin.
 *
 * - **L1 dolot:** zatrzymanie misji WPML, potwierdzenie pozycji nad stacją (promień [LandingPlannerConfig.approachRadiusM])
 *   i ewentualna korekta wysokości do 8 m przez Virtual Stick.
 * - **L2 szukanie:** gimbal −90°, Virtual Stick, zawis. Brak markera przez N s → wzniesienie o kilka metrów
 *   i ponowne szukanie; po wyczerpaniu wznoszeń → [LandingOutcome.MarkerLost].
 * - **L3 centrowanie i opadanie:** [LandingController] (PD + stożek) do wysokości przejścia. Krótka utrata markera →
 *   zawis bez opadania; dłuższa → wzniesienie i [LandingOutcome.MarkerLost] (orkiestrator ponawia od L2
 *   jako kolejną próbę, a po `maxLandingAttempts` przechodzi do AWARII).
 * - **L4 przyziemienie:** wyłączenie Virtual Stick, `KeyStartAutoLanding`; gdy dron zgłosi
 *   `KeyIsLandingConfirmationNeeded`, planer sam wysyła `KeyConfirmLanding` (test R5).
 * - **L5 weryfikacja:** silniki wyłączone (`KeyAreMotorsOn == false`) i stacja potwierdza drona na płycie
 *   (`StationStatus.dronePresent`) → [LandingOutcome.Landed], dalej orkiestrator: WYŁĄCZANIE.
 *
 * Anulowanie (STOP) w dowolnym miejscu: zawis i oddanie Virtual Stick w `finally`.
 */
class LandingPlanner(
    private val flight: FlightControlPort,
    private val mission: MissionPort,
    private val camera: CameraPort,
    private val vision: VisionPort,
    private val telemetry: TelemetryPort,
    private val station: StationPort,
    /** Położenie stacji (z aktywnej trasy, ew. punkt startu). */
    private val stationLocation: () -> GeoPoint?,
    private val logger: FlightLogger,
    private val clock: Clock,
    val config: LandingPlannerConfig = LandingPlannerConfig(),
    controllerConfig: LandingControllerConfig = LandingControllerConfig(),
) : LandingProcedure {

    private val controller = LandingController(controllerConfig)

    private val _phase = MutableStateFlow<LandingPhase?>(null)

    /** Pod-stan bieżącego lądowania; `null` poza lądowaniem. */
    val phase: StateFlow<LandingPhase?> = _phase.asStateFlow()

    override suspend fun execute(attempt: Int): LandingOutcome {
        val startedAt = clock.monotonicMs()
        logger.system("landing_start", fields = mapOf("attempt" to attempt))
        vision.acquire(VISION_USER)
        try {
            approach(attempt)?.let { return it }
            search(attempt)?.let { return it }
            descend(attempt)?.let { return it }
            val touchdownStartedAt = clock.monotonicMs()
            touchdown(attempt)?.let { return it }
            return verify(attempt, startedAt, touchdownStartedAt)
        } catch (e: LandingInterrupted) {
            logger.safety("landing_interrupted", LogLevel.ERROR, mapOf("reason" to e.message))
            return LandingOutcome.Aborted(e.message ?: "utrata sterowania")
        } finally {
            withContext(NonCancellable) {
                if (flight.controlOwner.value == ControlOwner.VIRTUAL_STICK) {
                    flight.setVelocityTarget(Setpoint.HOVER)
                    logger.command("landing_vs_off", flight.stopVirtualStick())
                }
                vision.release(VISION_USER)
                _phase.value = null
            }
        }
    }

    // ------------------------------------------------------------------ L1

    private suspend fun approach(attempt: Int): LandingOutcome? {
        enter(LandingStep.L1_APPROACH, attempt, "zatrzymanie misji, kontrola pozycji")
        if (mission.progress.value.isActive || flight.controlOwner.value == ControlOwner.MISSION) {
            val stopped = mission.stop()
            logger.command("landing_mission_stop", stopped)
            if (stopped is CommandResult.Failure) return LandingOutcome.Aborted("Nie zatrzymano misji WPML: ${stopped.reason}")
        }
        val stationPoint = stationLocation() ?: return LandingOutcome.Aborted("Nieznane położenie stacji")

        val arrived = withTimeoutOrNull(config.approachTimeoutMs) {
            telemetry.state.first { d -> d.distanceToM(stationPoint)?.let { it <= config.approachRadiusM } == true }
        }
        if (arrived == null) {
            val distance = telemetry.state.value.distanceToM(stationPoint)
            return LandingOutcome.Aborted("Dron nie dotarł nad stację (odległość ${distance?.let { "%.1f".format(it) } ?: "?"} m)")
        }

        val vs = flight.startVirtualStick()
        logger.command("landing_vs_on", vs, mapOf("attempt" to attempt))
        if (vs is CommandResult.Failure) return LandingOutcome.Aborted("Virtual Stick: ${vs.reason}")

        val altitude = telemetry.state.value.altitudeM
        if (altitude != null && altitude !in config.approachMinAltitudeM..config.approachMaxAltitudeM) {
            enter(LandingStep.L1_APPROACH, attempt, "korekta wysokości do ${config.approachTargetAltitudeM} m", heightM = altitude)
            if (!moveVertically(config.approachTargetAltitudeM, config.verticalAdjustMps, config.approachTimeoutMs)) {
                return LandingOutcome.Aborted("Nie osiągnięto wysokości dolotu ${config.approachTargetAltitudeM} m")
            }
        }
        return null
    }

    // ------------------------------------------------------------------ L2

    private suspend fun search(attempt: Int): LandingOutcome? {
        enter(LandingStep.L2_SEARCH, attempt, "gimbal −90°")
        val gimbal = camera.pointGimbalDown()
        logger.command("landing_gimbal_down", gimbal)
        if (gimbal is CommandResult.Failure) return LandingOutcome.Aborted("Gimbal: ${gimbal.reason}")
        val down = withTimeoutOrNull(config.gimbalTimeoutMs) {
            camera.gimbalPitchDeg.first { it != null && it <= -85.0 }
        }
        if (down == null) logger.system("landing_gimbal_unconfirmed", LogLevel.WARN, mapOf("pitch" to camera.gimbalPitchDeg.value))

        var climbs = 0
        while (true) {
            enter(LandingStep.L2_SEARCH, attempt, if (climbs == 0) "zawis, szukam markera" else "szukam po wzniesieniu $climbs")
            if (waitForMarker(config.searchTimeoutMs) != null) return null
            val altitude = telemetry.state.value.altitudeM ?: 0.0
            if (climbs >= config.maxSearchClimbs || altitude + config.searchClimbM > config.maxSearchAltitudeM) {
                return LandingOutcome.MarkerLost("Marker nie znaleziony w L2 (wzniesienia: $climbs)")
            }
            climbs++
            logger.safety("landing_search_climb", LogLevel.WARN, mapOf("climb" to climbs, "alt" to altitude))
            if (!moveVertically(altitude + config.searchClimbM, config.verticalAdjustMps, config.approachTimeoutMs)) {
                return LandingOutcome.Aborted("Nie udało się wznieść w trakcie szukania markera")
            }
        }
    }

    /**
     * Zawis do pojawienia się świeżej obserwacji; `null` po [timeoutMs] szukania. Czas przestoju detekcji
     * (brak nowych klatek) nie jest liczony do [timeoutMs] — przycięcie obrazu to nie „brak markera”.
     */
    private suspend fun waitForMarker(timeoutMs: Long): MarkerObservation? {
        var searchedMs = 0L
        var stallMs = 0L
        var previous = clock.monotonicMs()
        while (searchedMs < timeoutMs) {
            ensureVirtualStick()
            freshObservation()?.let { return it }
            flight.setVelocityTarget(Setpoint.HOVER)
            delay(config.periodMs)
            val now = clock.monotonicMs()
            val dt = now - previous
            previous = now
            if (visionProcessing(now)) {
                searchedMs += dt
                stallMs = 0
            } else {
                stallMs += dt
                if (stallMs > config.visionStallTimeoutMs) {
                    logger.safety("landing_vision_stall", LogLevel.WARN, mapOf("ms" to stallMs, "step" to "L2"))
                    return null
                }
            }
        }
        return null
    }

    /** Potok wizyjny dostarcza świeże wyniki (z markerem lub bez). */
    private fun visionProcessing(now: Long): Boolean {
        val frame = vision.results.value?.frameTimestampMs ?: return false
        return now - frame <= config.maxObservationAgeMs
    }

    // ------------------------------------------------------------------ L3

    private suspend fun descend(attempt: Int): LandingOutcome? {
        enter(LandingStep.L3_DESCENT, attempt, "centrowanie")
        controller.reset()
        var lostMs = 0L
        var stallMs = 0L
        var previous = clock.monotonicMs()
        var lastFrame = Long.MIN_VALUE
        var lastCommand: LandingCommand? = null
        while (true) {
            ensureVirtualStick()
            val now = clock.monotonicMs()
            val dt = now - previous
            previous = now
            val obs = freshObservation()
            if (obs != null) {
                lostMs = 0
                stallMs = 0
                if (obs.timestampMs != lastFrame) {
                    lastFrame = obs.timestampMs
                    val drone = telemetry.state.value
                    val cmd = controller.update(obs, drone, heightFor(obs.dzM))
                    lastCommand = cmd
                    flight.setVelocityTarget(cmd.setpoint)
                    logStep(obs, cmd)
                    _phase.value = LandingPhase(
                        LandingStep.L3_DESCENT, attempt,
                        if (cmd.descentAllowed) "opadanie" else "centrowanie",
                        cmd.heightM, cmd.horizontalErrorM, cmd.toleranceM,
                    )
                    if (cmd.heightM <= config.transitionHeightM && cmd.horizontalErrorM <= controller.config.toleranceM(config.transitionHeightM)) {
                        logger.system("landing_transition", fields = mapOf("h" to cmd.heightM, "err" to cmd.horizontalErrorM, "yaw" to obs.yawDeg))
                        return null
                    }
                } else {
                    // Ta sama klatka co poprzednio: podtrzymujemy polecenie, ale bez opadania, gdy obraz się starzeje.
                    lastCommand?.let { cmd ->
                        val sp = cmd.setpoint
                        flight.setVelocityTarget(
                            if (obs.ageMs(now) > config.blindDescentMaxAgeMs && sp.upMps < 0) sp.copy(upMps = 0.0) else sp,
                        )
                    }
                }
            } else {
                // Brak markera: natychmiast bez opadania (zawis); po limicie — wzniesienie i kolejna próba.
                flight.setVelocityTarget(Setpoint.HOVER)
                if (visionProcessing(now)) {
                    lostMs += dt
                    stallMs = 0
                } else {
                    // Przestój detekcji — marker mógł nie zniknąć; czekamy w zawisie, nie liczymy utraty.
                    stallMs += dt
                    if (stallMs > config.visionStallTimeoutMs) {
                        logger.safety("landing_vision_stall", LogLevel.WARN, mapOf("ms" to stallMs, "step" to "L3"))
                        lostMs = config.markerLostTimeoutMs + 1
                    }
                }
                val tof = telemetry.state.value.ultrasonicHeightM
                val last = lastCommand
                if (last != null && tof != null && tof <= config.blindFinalMaxHeightM &&
                    last.heightM <= config.blindFinalMaxHeightM && last.horizontalErrorM <= last.toleranceM
                ) {
                    // Tuż nad płytą marker bywa za blisko kamery — dron był wycentrowany, ToF potwierdza wysokość.
                    logger.safety("landing_blind_final", LogLevel.WARN, mapOf("tof" to tof, "lastErr" to last.horizontalErrorM))
                    return null
                }
                if (lostMs > config.markerLostTimeoutMs) {
                    val altitude = telemetry.state.value.altitudeM ?: last?.heightM ?: 0.0
                    logger.safety("landing_marker_lost", LogLevel.WARN, mapOf("lostMs" to lostMs, "alt" to altitude, "attempt" to attempt))
                    enter(LandingStep.L3_DESCENT, attempt, "marker utracony — wznoszenie", heightM = altitude)
                    moveVertically(min(altitude + config.lostClimbM, config.maxSearchAltitudeM), config.verticalAdjustMps, 15_000)
                    return LandingOutcome.MarkerLost("Marker utracony w L3 na wysokości ${"%.1f".format(altitude)} m")
                }
            }
            delay(config.periodMs)
        }
    }

    // ------------------------------------------------------------------ L4

    private suspend fun touchdown(attempt: Int): LandingOutcome? {
        enter(LandingStep.L4_TOUCHDOWN, attempt, "lądowanie automatyczne")
        flight.setVelocityTarget(Setpoint.HOVER)
        logger.command("landing_vs_off", flight.stopVirtualStick())
        // Potwierdzenie ochrony lądowania robi planer (testowalne w :core), nie adapter.
        val start = flight.startAutoLanding(confirmAutomatically = false)
        logger.command("landing_auto_start", start)
        if (start is CommandResult.Failure) return LandingOutcome.Aborted("KeyStartAutoLanding: ${start.reason}")

        var lastConfirmAt = Long.MIN_VALUE
        var confirmations = 0
        val done = withTimeoutOrNull(config.touchdownTimeoutMs) {
            while (true) {
                val d = telemetry.state.value
                if (d.areMotorsOn == false && d.isFlying != true) break
                val now = clock.monotonicMs()
                if (d.isLandingConfirmationNeeded == true && (lastConfirmAt == Long.MIN_VALUE || now - lastConfirmAt >= config.confirmRetryMs)) {
                    confirmations++
                    logger.safety(
                        "landing_confirmation_needed",
                        LogLevel.WARN,
                        mapOf("n" to confirmations, "tof" to d.ultrasonicHeightM, "alt" to d.altitudeM),
                    )
                    enter(LandingStep.L4_TOUCHDOWN, attempt, "ochrona lądowania — potwierdzam ($confirmations)")
                    lastConfirmAt = now
                    logger.command("landing_confirm", flight.confirmLanding(), mapOf("n" to confirmations))
                }
                delay(100)
            }
            true
        }
        if (done == null) return LandingOutcome.Aborted("Brak przyziemienia w ciągu ${config.touchdownTimeoutMs / 1000} s")
        logger.system("landing_touchdown", fields = mapOf("confirmations" to confirmations))
        return null
    }

    // ------------------------------------------------------------------ L5

    private suspend fun verify(attempt: Int, startedAt: Long, touchdownStartedAt: Long): LandingOutcome {
        enter(LandingStep.L5_VERIFY, attempt, "silniki wyłączone, czekam na potwierdzenie stacji")
        // Liczy się tylko status odebrany po rozpoczęciu przyziemienia (L4) — starszy opisuje płytę przed lądowaniem.
        val touchdownAt = touchdownStartedAt
        val confirmed = withTimeoutOrNull(config.stationConfirmTimeoutMs) {
            station.link.first { link ->
                link is StationLink.Connected && link.status?.let { it.dronePresent && it.receivedAtMs >= touchdownAt } == true
            }
        }
        if (confirmed == null) {
            val link = station.link.value
            val reason = when {
                link !is StationLink.Connected -> "brak łączności ze stacją"
                link.status?.dronePresent == false -> "stacja nie wykrywa drona na płycie"
                else -> "brak świeżego statusu stacji"
            }
            logger.safety("landing_verify_failed", LogLevel.ERROR, mapOf("reason" to reason))
            return LandingOutcome.Aborted("Weryfikacja L5: $reason")
        }
        logger.system("landing_done", fields = mapOf("attempt" to attempt, "ms" to clock.monotonicMs() - startedAt))
        return LandingOutcome.Landed
    }

    // ------------------------------------------------------------------ pomocnicze

    private fun freshObservation(): MarkerObservation? =
        vision.observation.value?.takeIf { it.ageMs(clock.monotonicMs()) <= config.maxObservationAgeMs }

    /** Wysokość do stożka: odległość do markera, nisko ograniczona przez ToF (bardziej zachowawcza z dwóch). */
    private fun heightFor(markerHeightM: Double): Double {
        val tof = telemetry.state.value.ultrasonicHeightM
        return if (tof != null && tof > 0 && tof < config.ultrasonicTrustBelowM) min(markerHeightM, tof) else markerHeightM
    }

    /** Ruch w pionie przez Virtual Stick do [targetAltitudeM]; `false` po przekroczeniu czasu. */
    private suspend fun moveVertically(targetAltitudeM: Double, speedMps: Double, timeoutMs: Long): Boolean {
        val reached = withTimeoutOrNull(timeoutMs) {
            while (true) {
                ensureVirtualStick()
                val alt = telemetry.state.value.altitudeM ?: break
                val err = targetAltitudeM - alt
                if (abs(err) <= 0.3) break
                flight.setVelocityTarget(Setpoint(0.0, 0.0, (err * 0.8).coerceIn(-speedMps, speedMps), 0.0))
                delay(config.periodMs)
            }
            true
        }
        flight.setVelocityTarget(Setpoint.HOVER)
        return reached != null
    }

    /** Utrata Virtual Stick (przejęcie z aparatury itp.) — dalsze sterowanie nie ma sensu. */
    private fun ensureVirtualStick() {
        if (flight.controlOwner.value != ControlOwner.VIRTUAL_STICK) {
            throw LandingInterrupted("Virtual Stick nie jest właścicielem sterowania (${flight.controlOwner.value})")
        }
    }

    private fun enter(step: LandingStep, attempt: Int, detail: String, heightM: Double? = null) {
        val previous = _phase.value
        _phase.value = LandingPhase(step, attempt, detail, heightM)
        if (previous?.step != step || previous.detail != detail) {
            logger.log(LogCategory.STATE, LogLevel.INFO, "landing_phase", mapOf("step" to step.name, "attempt" to attempt, "detail" to detail))
        }
    }

    private fun logStep(obs: MarkerObservation, cmd: LandingCommand) {
        logger.log(
            LogCategory.MARKER,
            LogLevel.DEBUG,
            "landing_step",
            mapOf(
                "id" to obs.markerId, "dx" to obs.dxM, "dy" to obs.dyM, "dz" to obs.dzM, "yaw" to obs.yawDeg, "lat" to obs.latencyMs,
                "h" to cmd.heightM, "err" to cmd.horizontalErrorM, "tol" to cmd.toleranceM, "descent" to cmd.descentAllowed,
                "fwd" to cmd.setpoint.forwardMps, "right" to cmd.setpoint.rightMps, "up" to cmd.setpoint.upMps, "yawRate" to cmd.setpoint.yawRateDegS,
            ),
        )
    }

    companion object {
        const val VISION_USER = "landing"
    }
}

/** Przerwanie lądowania z powodu utraty sterowania — zamieniane na [LandingOutcome.Aborted]. */
internal class LandingInterrupted(message: String) : RuntimeException(message)
