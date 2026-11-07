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
import com.example.djicontroller.msdk.camera.CameraAdapter
import com.example.djicontroller.msdk.connection.DroneConnection
import com.example.djicontroller.msdk.connection.SdkInitializer
import com.example.djicontroller.msdk.telemetry.KeyRepository
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

    // --- DJI MSDK (:msdk-adapter)
    val sdk = SdkInitializer(context, logger)
    val connection = DroneConnection(sdk, appScope, logger, clock)
    val telemetry = KeyRepository(sdk, appScope, logger, clock)
    val camera = CameraAdapter(sdk, appScope, logger, clock)

    // --- Moduły kolejnych faz (patrz UnavailablePorts.kt)
    val flight = UnavailableFlightControl(arbiter)
    val mission = UnavailableMission()
    val station = UnavailableStation()
    val landing = UnavailableLanding()

    // --- Logika (:core)
    val batteryPolicy = BatteryPolicy()
    val breakpoints = FileBreakpointStore(context)
    val safety = SafetySupervisor(appScope, telemetry, connection, flight, mission, station, batteryPolicy, logger, clock)
    val orchestrator = CycleOrchestrator(
        appScope, telemetry, connection, flight, mission, station, landing, safety,
        batteryPolicy, breakpoints, logger, clock,
    )
    private val telemetryRecorder = TelemetryRecorder(telemetry, logger, periodMs = 200)

    /** Uruchamia MSDK, telemetrię, nadzór i zapis telemetrii. Wołać raz z `Application.onCreate`. */
    fun start() {
        logger.system("app_start", fields = mapOf("log" to fileLogger.file.absolutePath))
        sdk.initialize()
        telemetry.start()
        orchestrator.start()
        telemetryRecorder.start(appScope)
    }
}
