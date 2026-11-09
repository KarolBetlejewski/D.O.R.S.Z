package com.example.djicontroller.core.diagnostics

import com.example.djicontroller.core.control.MarkerHoldConfig
import com.example.djicontroller.core.control.MarkerHoldController
import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.core.port.VisionPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class MarkerHoverTestConfig(
    val hold: MarkerHoldConfig = MarkerHoldConfig(),
    /** Okres pętli regulatora, ms (pętla Virtual Stick wysyła niezależnie, 20 Hz). */
    val periodMs: Long = 50,
    /** Najstarsza obserwacja, z której wolno liczyć sterowanie, ms. */
    val maxObservationAgeMs: Long = 500,
    /** Jak długo powtarzamy ostatni punkt zadany po zniknięciu markera, zanim przejdziemy w zawis, ms. */
    val holdLastCommandMs: Long = 300,
    /** Marker niewidoczny dłużej niż tyle → koniec testu, ms. */
    val markerLostAbortMs: Long = 10_000,
    /** Czas na ustabilizowanie, zanim zaczniemy liczyć statystyki R3, ms. */
    val settleMs: Long = 3_000,
    /** Czas na obrót gimbala do −90°, ms. */
    val gimbalTimeoutMs: Long = 3_000,
    val minTargetHeightM: Double = 1.0,
    val maxTargetHeightM: Double = 10.0,
)

/** Podsumowanie testu R3 — trafia do logu jako `r3_summary`. */
data class HoverSummary(
    val durationMs: Long,
    val samples: Int,
    val rmsHorizontalErrorM: Double,
    val maxHorizontalErrorM: Double,
    val meanAbsHeightErrorM: Double,
    val minHeightM: Double,
    val markerVisiblePercent: Double,
)

sealed interface HoverTestState {
    data object Idle : HoverTestState

    data class Running(
        val targetHeightM: Double,
        val phase: String,
        val observation: MarkerObservation?,
        val setpoint: Setpoint,
        val horizontalErrorM: Double?,
        val heightErrorM: Double?,
        val descentAllowed: Boolean,
        val elapsedMs: Long,
        val summary: HoverSummary?,
    ) : HoverTestState

    data class Finished(val reason: String, val success: Boolean, val summary: HoverSummary?) : HoverTestState
}

/**
 * Test R3/F2 „Zawis nad markerem”: dron wisi na zadanej wysokości i utrzymuje środek nad markerem
 * bez opadania poniżej celu. Sterowanie: [MarkerHoldController] (PD) → Virtual Stick.
 *
 * Bez markera dron dostaje [Setpoint.HOVER] (po krótkim podtrzymaniu ostatniego polecenia);
 * po [MarkerHoverTestConfig.markerLostAbortMs] bez markera test się kończy.
 * Przerywają go: STOP, utrata sterowania, przycisk „Zakończ” — patrz [FlightTestRunner].
 */
class MarkerHoverTest(
    private val runner: FlightTestRunner,
    private val flight: FlightControlPort,
    private val camera: CameraPort,
    private val vision: VisionPort,
    private val telemetry: TelemetryPort,
    private val logger: FlightLogger,
    private val clock: Clock,
    val config: MarkerHoverTestConfig = MarkerHoverTestConfig(),
) {
    private val _state = MutableStateFlow<HoverTestState>(HoverTestState.Idle)
    val state: StateFlow<HoverTestState> = _state.asStateFlow()

    private val controller = MarkerHoldController(config.hold)

    fun start(targetHeightM: Double): CommandResult {
        if (targetHeightM !in config.minTargetHeightM..config.maxTargetHeightM) {
            return CommandResult.Failure(
                "Wysokość docelowa poza zakresem ${config.minTargetHeightM}–${config.maxTargetHeightM} m",
            )
        }
        val stats = Stats()
        return runner.launch(
            name = TEST_NAME,
            body = { run(targetHeightM, stats) },
            onFinished = { abort, error ->
                vision.release(VISION_USER)
                val summary = stats.summary(clock.monotonicMs())
                summary?.let { logSummary(targetHeightM, it, abort) }
                _state.value = HoverTestState.Finished(
                    reason = abort ?: "zakończono",
                    success = abort == null || abort == "operator",
                    summary = summary,
                )
                if (error != null) logger.system("r3_error", LogLevel.ERROR, mapOf("error" to error.stackTraceToString()))
            },
        ).also { result ->
            if (result.isSuccess) {
                vision.acquire(VISION_USER)
                _state.value = running(targetHeightM, "przygotowanie", null, Setpoint.HOVER, null, 0)
            }
        }
    }

    fun stop() = runner.cancel("operator")

    private suspend fun run(targetHeightM: Double, stats: Stats) {
        val startMs = clock.monotonicMs()

        // Kamera w dół; czekamy, aż gimbal faktycznie osiągnie ok. −90°.
        _state.value = running(targetHeightM, "gimbal −90°", null, Setpoint.HOVER, null, 0)
        flight.setVelocityTarget(Setpoint.HOVER)
        val gimbal = camera.pointGimbalDown()
        logger.command("r3_gimbal_down", gimbal)
        if (gimbal is CommandResult.Failure) throw FlightTestAborted("gimbal: ${gimbal.reason}")
        // W trakcie czekania pętla Virtual Stick i tak wysyła zawis (watchdog → HOVER).
        val reached = withTimeoutOrNull(config.gimbalTimeoutMs) {
            camera.gimbalPitchDeg.first { pitch -> pitch != null && pitch <= -85.0 }
        }
        if (reached == null) {
            logger.system("r3_gimbal_unconfirmed", LogLevel.WARN, mapOf("pitch" to camera.gimbalPitchDeg.value))
        }

        controller.reset()
        stats.begin(clock.monotonicMs() + config.settleMs)
        var lastUsedFrame = Long.MIN_VALUE
        var lastCommand = Setpoint.HOVER
        var lastCommandAt = Long.MIN_VALUE
        var lastSeenAt = clock.monotonicMs()
        logger.system("r3_start", fields = mapOf("targetHeight" to targetHeightM, "cfg" to config.hold.toString()))

        while (true) {
            if (flight.controlOwner.value != ControlOwner.VIRTUAL_STICK) {
                throw FlightTestAborted("Virtual Stick nie jest już właścicielem sterowania")
            }
            val now = clock.monotonicMs()
            val obs = vision.observation.value?.takeIf { it.ageMs(now) <= config.maxObservationAgeMs }
            val drone = telemetry.state.value

            val setpoint: Setpoint
            var horizontalError: Double? = null
            var heightError: Double? = null
            var descentAllowed = false
            val phase: String
            stats.tick(now, visible = obs != null)
            if (obs != null) {
                lastSeenAt = now
                if (obs.timestampMs != lastUsedFrame) {
                    lastUsedFrame = obs.timestampMs
                    val cmd = controller.update(obs, drone, targetHeightM)
                    lastCommand = cmd.setpoint
                    lastCommandAt = now
                    horizontalError = cmd.horizontalErrorM
                    heightError = cmd.heightErrorM
                    descentAllowed = cmd.descentAllowed
                    stats.add(now, cmd.horizontalErrorM, cmd.heightErrorM, obs.dzM)
                    logger.log(
                        LogCategory.MARKER,
                        LogLevel.DEBUG,
                        "r3_step",
                        mapOf(
                            "id" to obs.markerId, "dx" to obs.dxM, "dy" to obs.dyM, "dz" to obs.dzM, "yaw" to obs.yawDeg,
                            "lat" to obs.latencyMs, "eH" to cmd.horizontalErrorM, "eZ" to cmd.heightErrorM,
                            "fwd" to cmd.setpoint.forwardMps, "right" to cmd.setpoint.rightMps,
                            "up" to cmd.setpoint.upMps, "yawRate" to cmd.setpoint.yawRateDegS,
                            "descent" to cmd.descentAllowed,
                        ),
                    )
                }
                setpoint = lastCommand
                phase = "nad markerem"
            } else {
                if (now - lastSeenAt > config.markerLostAbortMs) {
                    throw FlightTestAborted("marker niewidoczny dłużej niż ${config.markerLostAbortMs / 1000} s")
                }
                setpoint = if (now - lastCommandAt <= config.holdLastCommandMs) lastCommand else Setpoint.HOVER
                if (setpoint.isHover) controller.reset()
                phase = "szukam markera"
            }
            flight.setVelocityTarget(setpoint)
            _state.value = HoverTestState.Running(
                targetHeightM = targetHeightM,
                phase = phase,
                observation = obs,
                setpoint = setpoint,
                horizontalErrorM = horizontalError ?: (_state.value as? HoverTestState.Running)?.horizontalErrorM,
                heightErrorM = heightError ?: (_state.value as? HoverTestState.Running)?.heightErrorM,
                descentAllowed = descentAllowed,
                elapsedMs = now - startMs,
                summary = stats.summary(now),
            )
            delay(config.periodMs)
        }
    }

    private fun running(target: Double, phase: String, obs: MarkerObservation?, sp: Setpoint, err: Double?, elapsed: Long) =
        HoverTestState.Running(target, phase, obs, sp, err, null, false, elapsed, null)

    private fun logSummary(target: Double, s: HoverSummary, abort: String?) {
        logger.system(
            "r3_summary",
            fields = mapOf(
                "targetHeight" to target,
                "durationMs" to s.durationMs,
                "samples" to s.samples,
                "rmsHorizontal" to s.rmsHorizontalErrorM,
                "maxHorizontal" to s.maxHorizontalErrorM,
                "meanAbsHeight" to s.meanAbsHeightErrorM,
                "minHeight" to s.minHeightM,
                "visiblePct" to s.markerVisiblePercent,
                "aborted" to abort,
            ),
        )
    }

    /** Statystyki liczone po czasie ustalania. */
    private class Stats {
        private var fromMs = Long.MAX_VALUE
        private var firstMs = Long.MAX_VALUE
        private var samples = 0
        private var sumSq = 0.0
        private var maxErr = 0.0
        private var sumAbsHeight = 0.0
        private var minHeight = Double.MAX_VALUE
        private var seenTicks = 0
        private var missedTicks = 0

        fun begin(statsFromMs: Long) { fromMs = statsFromMs }

        fun add(now: Long, horizontalError: Double, heightError: Double, height: Double) {
            if (now < fromMs) return
            if (firstMs == Long.MAX_VALUE) firstMs = now
            samples++
            sumSq += horizontalError * horizontalError
            maxErr = max(maxErr, horizontalError)
            sumAbsHeight += abs(heightError)
            if (height < minHeight) minHeight = height
        }

        fun tick(now: Long, visible: Boolean) {
            if (now < fromMs) return
            if (visible) seenTicks++ else missedTicks++
        }

        fun summary(now: Long): HoverSummary? {
            if (samples == 0) return null
            return HoverSummary(
                durationMs = now - firstMs,
                samples = samples,
                rmsHorizontalErrorM = sqrt(sumSq / samples),
                maxHorizontalErrorM = maxErr,
                meanAbsHeightErrorM = sumAbsHeight / samples,
                minHeightM = minHeight,
                markerVisiblePercent = 100.0 * seenTicks / (seenTicks + missedTicks).coerceAtLeast(1),
            )
        }
    }

    companion object {
        const val TEST_NAME = "r3_hover"
        const val VISION_USER = "test_r3"
    }
}
