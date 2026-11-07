package com.example.djicontroller.di

import android.content.Context
import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.logging.CompositeFlightLogger
import com.example.djicontroller.core.logging.TelemetryRecorder
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.orchestrator.BatteryPolicy
import com.example.djicontroller.core.orchestrator.CycleOrchestrator
import com.example.djicontroller.core.orchestrator.SafetySupervisor
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.core.port.SystemClock
import com.example.djicontroller.logging.FileFlightLogger
import com.example.djicontroller.logging.LogcatFlightLogger
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.storage.FileBreakpointStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Ręczne DI: jeden graf obiektów na proces, tworzony w [com.example.djicontroller.PatrolApp].
 * Żyje dłużej niż Activity — obrót ekranu ani ponowne podłączenie USB nie przerywają cyklu.
 */
class AppContainer(context: Context) {

    val clock: Clock = SystemClock

    private val crashLogger = LogcatFlightLogger(LogLevel.ERROR)

    /** Zakres aplikacji. Błąd jednej korutyny nie zatrzymuje pozostałych (SupervisorJob). */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            crashLogger.system("uncaught", LogLevel.ERROR, mapOf("error" to e.stackTraceToString()))
        },
    )

    val fileLogger = FileFlightLogger(context, clock, appScope)
    val logger: FlightLogger = CompositeFlightLogger(listOf(fileLogger, LogcatFlightLogger()))

    val arbiter = ControlArbiter(logger)

    // --- Platforma: DJI MSDK (wariant prod) albo symulator (wariant demo) — src/<wariant>/…/BackendFactory.kt
    val backend: DroneBackend = createDroneBackend(context, appScope, logger, clock, arbiter)
    val connection: ConnectionPort get() = backend.connection
    val telemetry: TelemetryPort get() = backend.telemetry
    val station: StationPort get() = backend.station

    // --- Logika (:core)
    val batteryPolicy = BatteryPolicy()
    val breakpoints = FileBreakpointStore(context)
    val safety = SafetySupervisor(
        appScope, backend.telemetry, backend.connection, backend.flight, backend.mission, backend.station,
        batteryPolicy, logger, clock,
    )
    val orchestrator = CycleOrchestrator(
        appScope, backend.telemetry, backend.connection, backend.flight, backend.mission, backend.station,
        backend.landing, safety, batteryPolicy, breakpoints, logger, clock,
    )
    private val telemetryRecorder = TelemetryRecorder(telemetry, logger, periodMs = 200)

    /** Uruchamia MSDK, telemetrię, nadzór i zapis telemetrii. Wołać raz z `Application.onCreate`. */
    fun start() {
        logger.system("app_start", fields = mapOf("backend" to backend.name, "log" to fileLogger.file.absolutePath))
        backend.start()
        orchestrator.start()
        telemetryRecorder.start(appScope)
    }
}
