package com.example.djicontroller.core.diagnostics

import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.landing.LandingPlanner
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.StationCommand
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.StationStatus
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Stacja zastępcza do testu lądowania na samej płycie z markerem (R5, zanim powstanie stacja):
 * łącze zawsze „połączone”, płyta zawsze wolna, „dron na płycie” = silniki wyłączone i dron nie leci.
 * Status odświeża się przy każdej zmianie telemetrii, więc L5 dostaje świeże potwierdzenie.
 */
class PadOnlyStation(scope: CoroutineScope, telemetry: TelemetryPort, clock: Clock) : StationPort {

    override val link: StateFlow<StationLink> = telemetry.state
        .map { d -> StationLink.Connected(status(d.areMotorsOn == false && d.isFlying == false, clock)) }
        .stateIn(scope, SharingStarted.Eagerly, StationLink.Connected(status(false, clock)))

    override suspend fun send(command: StationCommand): CommandResult =
        CommandResult.Failure("Test bez stacji — stacja nie jest podłączona")

    private companion object {
        fun status(dronePresent: Boolean, clock: Clock) = StationStatus(
            charging = false,
            dronePresent = dronePresent,
            padClear = true,
            ready = false,
            receivedAtMs = clock.monotonicMs(),
        )
    }
}

/** Stan testu lądowania precyzyjnego bez stacji. */
sealed interface PadLandingState {
    data object Idle : PadLandingState
    data class Running(val attempt: Int, val startedAtMs: Long) : PadLandingState
    data class Finished(
        val success: Boolean,
        val reason: String,
        val attempts: Int,
        val durationMs: Long,
    ) : PadLandingState
}

/**
 * Test R5 z ekranu Diagnostyka: pełne lądowanie precyzyjne L1–L5 na płycie z markerem, bez stacji
 * i bez cyklu patrolu. Ten sam [LandingPlanner] co w cyklu, ale ze stacją [PadOnlyStation]
 * i położeniem płyty = punkt startu (dron startuje z płyty).
 *
 * Próby jak w orkiestratorze: po utracie markera planer się wznosi i próbuje ponownie,
 * po [maxAttempts] — koniec testu z wynikiem negatywnym. STOP i utrata sterowania przerywają test
 * przez [FlightTestRunner] (zawis, oddanie Virtual Stick, w L4 przerwanie auto-lądowania).
 */
class PadLandingTest(
    private val runner: FlightTestRunner,
    private val planner: LandingPlanner,
    private val logger: FlightLogger,
    private val clock: Clock,
    private val maxAttempts: Int = planner.config.maxAttempts,
) {
    private val _state = MutableStateFlow<PadLandingState>(PadLandingState.Idle)
    val state: StateFlow<PadLandingState> = _state.asStateFlow()

    /** Pod-stan L1–L5 trwającej próby (do UI). */
    val phase = planner.phase

    fun start(): CommandResult {
        val startedAt = clock.monotonicMs()
        var attempt = 1
        var result: Pair<Boolean, String>? = null
        return runner.launch(
            name = TEST_NAME,
            body = {
                while (result == null) {
                    _state.value = PadLandingState.Running(attempt, startedAt)
                    result = when (val outcome = planner.execute(attempt)) {
                        LandingOutcome.Landed -> true to "Wylądował na płycie (próba $attempt)"
                        is LandingOutcome.Aborted -> false to "Przerwane: ${outcome.reason}"
                        is LandingOutcome.MarkerLost ->
                            if (attempt >= maxAttempts) false to "Marker utracony $attempt razy: ${outcome.reason}" else null
                    }
                    if (result == null) attempt++
                }
            },
            onFinished = { abortReason, error ->
                val (success, reason) = when {
                    abortReason != null -> false to abortReason
                    error != null -> false to "błąd: ${error.message}"
                    else -> result ?: (false to "nieznany wynik")
                }
                val duration = clock.monotonicMs() - startedAt
                logger.system(
                    "test_${TEST_NAME}_result",
                    if (success) LogLevel.INFO else LogLevel.WARN,
                    mapOf("success" to success, "reason" to reason, "attempts" to attempt, "ms" to duration),
                )
                _state.value = PadLandingState.Finished(success, reason, attempt, duration)
            },
        ).also { if (it is CommandResult.Failure) _state.value = PadLandingState.Finished(false, it.reason, 0, 0) }
    }

    fun stop() = runner.cancel("operator")

    companion object {
        const val TEST_NAME = "r5_pad_landing"
    }
}
