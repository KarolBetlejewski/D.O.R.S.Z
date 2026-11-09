package com.example.djicontroller.core.diagnostics

import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.BodyVelocity
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.TelemetryPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Werdykt testu osi Virtual Stick. */
enum class AxisVerdict {
    /** Polecenie „do przodu” daje ruch do przodu, „w prawo” — w prawo. */
    OK,

    /** Osie zamienione — trzeba przełączyć mapowanie [StickAxes]. */
    SWAPPED,

    /** Kierunek odwrócony w co najmniej jednej osi — nie latać, zgłosić błąd. */
    INVERTED,

    /** Dron nie ruszył (np. symulator wyłączony, wiatr, brak GPS) — wynik nierozstrzygnięty. */
    NO_MOTION,
}

data class AxisCheckResult(
    val axes: StickAxes,
    /** Średnia prędkość BODY zmierzona przy poleceniu „do przodu”. */
    val forwardCommand: BodyVelocity,
    /** Średnia prędkość BODY zmierzona przy poleceniu „w prawo”. */
    val rightCommand: BodyVelocity,
    val verdict: AxisVerdict,
)

sealed interface AxisCheckState {
    data object Idle : AxisCheckState
    data class Running(val step: String) : AxisCheckState
    data class Finished(val result: AxisCheckResult?, val reason: String?) : AxisCheckState
}

/**
 * Test osi Virtual Stick (przed R3, w symulatorze MSDK): krótko „do przodu”, potem „w prawo”,
 * i porównanie z prędkością z telemetrii obróconą do układu BODY.
 *
 * Powód istnienia: dokumentacja DJI opisuje tryb VELOCITY tak, że `roll` to oś X (przód), a `pitch` — oś Y,
 * co jest sprzeczne z intuicją „pitch = przód”. Pomyłka oznaczałaby lądowanie bokiem do markera,
 * więc mapowanie sprawdzamy pomiarem, a nie założeniem.
 */
class StickAxisCheck(
    private val runner: FlightTestRunner,
    private val flight: FlightControlPort,
    private val telemetry: TelemetryPort,
    private val logger: FlightLogger,
    private val clock: Clock,
    private val speedMps: Double = 0.5,
    private val pulseMs: Long = 2_000,
    private val settleMs: Long = 1_500,
) {
    private val _state = MutableStateFlow<AxisCheckState>(AxisCheckState.Idle)
    val state: StateFlow<AxisCheckState> = _state.asStateFlow()

    fun start(): CommandResult {
        var result: AxisCheckResult? = null
        return runner.launch(
            name = TEST_NAME,
            body = { result = run() },
            onFinished = { abort, _ -> _state.value = AxisCheckState.Finished(result, abort) },
        ).also { if (it.isSuccess) _state.value = AxisCheckState.Running("zawis") }
    }

    fun stop() = runner.cancel("operator")

    private suspend fun run(): AxisCheckResult {
        hover(settleMs)
        _state.value = AxisCheckState.Running("do przodu ${speedMps} m/s")
        val forward = pulse(Setpoint(speedMps, 0.0, 0.0, 0.0))
        hover(settleMs)
        _state.value = AxisCheckState.Running("w prawo ${speedMps} m/s")
        val right = pulse(Setpoint(0.0, speedMps, 0.0, 0.0))
        hover(settleMs)

        val result = AxisCheckResult(flight.virtualStick.value.axes, forward, right, verdict(forward, right))
        logger.system(
            "vs_axis_check",
            if (result.verdict == AxisVerdict.OK) LogLevel.INFO else LogLevel.WARN,
            mapOf(
                "axes" to result.axes,
                "fwdCmd_fwd" to forward.forwardMps, "fwdCmd_right" to forward.rightMps,
                "rightCmd_fwd" to right.forwardMps, "rightCmd_right" to right.rightMps,
                "verdict" to result.verdict,
            ),
        )
        return result
    }

    private suspend fun hover(ms: Long) {
        val end = clock.monotonicMs() + ms
        while (clock.monotonicMs() < end) {
            flight.setVelocityTarget(Setpoint.HOVER)
            delay(PERIOD_MS)
        }
    }

    /** Wysyła [setpoint] przez [pulseMs]; zwraca średnią prędkość z drugiej połowy impulsu (po rozpędzeniu). */
    private suspend fun pulse(setpoint: Setpoint): BodyVelocity {
        val start = clock.monotonicMs()
        val measureFrom = start + pulseMs / 2
        var n = 0
        var f = 0.0
        var r = 0.0
        while (clock.monotonicMs() - start < pulseMs) {
            flight.setVelocityTarget(setpoint)
            if (clock.monotonicMs() >= measureFrom) {
                telemetry.state.value.bodyVelocity()?.let { v ->
                    n++
                    f += v.forwardMps
                    r += v.rightMps
                }
            }
            delay(PERIOD_MS)
        }
        flight.setVelocityTarget(Setpoint.HOVER)
        return if (n == 0) BodyVelocity(0.0, 0.0, 0.0) else BodyVelocity(f / n, r / n, 0.0)
    }

    internal fun verdict(forward: BodyVelocity, right: BodyVelocity): AxisVerdict {
        val threshold = speedMps * 0.4
        val fwdMain = forward.forwardMps
        val fwdCross = forward.rightMps
        val rightMain = right.rightMps
        val rightCross = right.forwardMps
        val moved = maxOf(abs(fwdMain), abs(fwdCross)) > threshold && maxOf(abs(rightMain), abs(rightCross)) > threshold
        return when {
            !moved -> AxisVerdict.NO_MOTION
            abs(fwdMain) >= abs(fwdCross) && abs(rightMain) >= abs(rightCross) ->
                if (fwdMain > 0 && rightMain > 0) AxisVerdict.OK else AxisVerdict.INVERTED
            abs(fwdCross) > abs(fwdMain) && abs(rightCross) > abs(rightMain) ->
                if (fwdCross > 0 && rightCross > 0) AxisVerdict.SWAPPED else AxisVerdict.INVERTED
            else -> AxisVerdict.INVERTED
        }
    }

    companion object {
        const val TEST_NAME = "vs_axes"
        private const val PERIOD_MS = 100L
    }
}
