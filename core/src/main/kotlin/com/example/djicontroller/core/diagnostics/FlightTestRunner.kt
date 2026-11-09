package com.example.djicontroller.core.diagnostics

import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.safety
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.TelemetryPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Przerwanie testu z podanego powodu (STOP, utrata sterowania, polecenie operatora). */
class FlightTestAborted(val reason: String) : CancellationException(reason)

/**
 * Wspólny mechanizm testów lotu z ekranu Diagnostyka (R3: zawis nad markerem, test osi Virtual Stick).
 *
 * Gwarancje:
 * - w danej chwili działa najwyżej jeden test,
 * - test startuje tylko poza cyklem patrolu (orkiestrator w `Ready` albo `Failure`) i gdy dron lata,
 * - sterowanie bierze przez `startVirtualStick()` — [com.example.djicontroller.core.control.ControlArbiter]
 *   odmówi, jeśli działa misja albo lądowanie (Z2),
 * - STOP ([stopEvents]) i każda utrata sterowania ([FlightControlPort.controlLost]) przerywają test natychmiast,
 * - po zakończeniu (także przy anulowaniu) dron dostaje [Setpoint.HOVER], a Virtual Stick zostaje
 *   wyłączony — dron przechodzi w zawis GPS, aparatura odzyskuje drążki (Z4).
 */
class FlightTestRunner(
    private val scope: CoroutineScope,
    private val flight: FlightControlPort,
    private val telemetry: TelemetryPort,
    private val stopEvents: Flow<String>,
    private val isCycleIdle: () -> Boolean,
    private val logger: FlightLogger,
) {
    private val lock = Any()
    private var job: Job? = null

    private val _running = MutableStateFlow<String?>(null)

    /** Nazwa trwającego testu albo `null`. */
    val running: StateFlow<String?> = _running.asStateFlow()

    /**
     * Uruchamia test [name]. [body] działa z aktywnym Virtual Stick; [onFinished] dostaje powód zakończenia
     * (`null` = test zakończył się sam) i wykonuje się zawsze, już po zawisie i wyłączeniu Virtual Stick.
     */
    fun launch(
        name: String,
        body: suspend CoroutineScope.() -> Unit,
        onFinished: (abortReason: String?, error: Throwable?) -> Unit,
    ): CommandResult = synchronized(lock) {
        val problem = preconditionProblem()
        if (problem != null) {
            val failure = CommandResult.Failure(problem)
            logger.command("test_${name}_start", failure)
            return failure
        }
        _running.value = name
        job = scope.launch {
            var abortReason: String? = null
            var error: Throwable? = null
            try {
                val vs = flight.startVirtualStick()
                logger.command("test_${name}_vs_on", vs)
                if (vs is CommandResult.Failure) throw FlightTestAborted("Virtual Stick: ${vs.reason}")
                val stopReason = AtomicReference<String?>(null)
                coroutineScope {
                    val testJob = launch {
                        try {
                            body()
                        } catch (e: FlightTestAborted) {
                            stopReason.compareAndSet(null, e.reason)
                        }
                    }
                    val stopWatch = launch(start = CoroutineStart.UNDISPATCHED) {
                        val source = stopEvents.first()
                        stopReason.compareAndSet(null, "STOP ($source)")
                        testJob.cancel()
                    }
                    val lossWatch = launch(start = CoroutineStart.UNDISPATCHED) {
                        val loss = flight.controlLost.first()
                        stopReason.compareAndSet(null, "utrata sterowania: ${loss.reason}")
                        testJob.cancel()
                    }
                    testJob.join()
                    stopWatch.cancel()
                    lossWatch.cancel()
                }
                stopReason.get()?.let { throw FlightTestAborted(it) }
            } catch (e: FlightTestAborted) {
                abortReason = e.reason
            } catch (e: CancellationException) {
                abortReason = e.message ?: "anulowano"
                throw e
            } catch (e: RuntimeException) {
                error = e
                abortReason = "błąd: ${e.message ?: e::class.simpleName}"
            } finally {
                withContext(NonCancellable) {
                    flight.setVelocityTarget(Setpoint.HOVER)
                    if (flight.controlOwner.value == ControlOwner.VIRTUAL_STICK) {
                        logger.command("test_${name}_vs_off", flight.stopVirtualStick())
                    }
                    logger.system(
                        "test_${name}_end",
                        if (abortReason == null) LogLevel.INFO else LogLevel.WARN,
                        mapOf("aborted" to abortReason, "error" to error?.toString()),
                    )
                    synchronized(lock) {
                        _running.value = null
                        job = null
                    }
                    onFinished(abortReason, error)
                }
            }
        }
        logger.system("test_${name}_start")
        CommandResult.Success
    }

    /** Przerywa trwający test (przycisk „Zakończ” w Diagnostyce). */
    fun cancel(reason: String = "operator") {
        val j = synchronized(lock) { job } ?: return
        logger.safety("test_cancel", LogLevel.INFO, mapOf("test" to _running.value, "reason" to reason))
        j.cancel(FlightTestAborted(reason))
    }

    private fun preconditionProblem(): String? {
        val d = telemetry.state.value
        return when {
            job?.isActive == true -> "Trwa test: ${_running.value}"
            !isCycleIdle() -> "Testy tylko poza cyklem patrolu (stan Gotowy albo Awaria)"
            !d.connected -> "Brak połączenia z kontrolerem lotu"
            d.isFlying != true -> "Dron musi być w powietrzu (start: przycisk „Start” w Diagnostyce)"
            flight.controlOwner.value != ControlOwner.NONE -> "Sterowanie zajęte: ${flight.controlOwner.value}"
            else -> null
        }
    }
}
