package com.example.djicontroller.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.djicontroller.appContainer
import com.example.djicontroller.core.landing.LandingPhase
import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.model.VisionFrameResult
import com.example.djicontroller.core.orchestrator.SafetyAlarm
import com.example.djicontroller.di.DebugAction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Wszystko, co pokazuje ekran główny (sekcja 6) — jeden niezmienny obiekt. */
data class MainUiState(
    val connection: ConnectionState = ConnectionState.Initializing,
    val drone: DroneState = DroneState.UNKNOWN,
    val cycle: CycleState = CycleState.Ready,
    val breakpoint: Int? = null,
    val station: StationLink = StationLink.Disconnected,
    val alarms: List<SafetyAlarm> = emptyList(),
    val route: PatrolRoute? = null,
    /** Próg powrotu dla bieżącej odległości od stacji (z trasy) lub od punktu startu. */
    val returnThresholdPercent: Int? = null,
    val logFileName: String = "",
    /** Wariant demo — inne komunikaty, np. brak prośby o podłączenie RC-N2. */
    val isSimulation: Boolean = false,
    /** Wynik detekcji ArUco do nakładki; `null`, gdy detekcja wyłączona. */
    val vision: VisionFrameResult? = null,
    /** Pod-stan precyzyjnego lądowania (L1–L5); `null` poza lądowaniem. */
    val landingPhase: LandingPhase? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val orchestrator = container.orchestrator
    private val safety = container.safety
    private val logger = container.logger

    private val backend = container.backend

    /** Cel podglądu wideo (MSDK albo symulator). */
    val videoPreview: VideoPreview = backend.videoPreview

    /** Akcje testowe symulatora; pusta lista w wariancie prod. */
    val debugActions: List<DebugAction> = backend.debugActions
    val backendName: String = backend.name

    private val core = combine(
        container.connection.state,
        container.telemetry.state,
        orchestrator.state,
        orchestrator.breakpoint,
        orchestrator.activeRoute,
    ) { connection, drone, cycle, breakpoint, route ->
        MainUiState(connection = connection, drone = drone, cycle = cycle, breakpoint = breakpoint, route = route)
    }

    val state: StateFlow<MainUiState> = combine(
        core,
        container.station.link,
        safety.alarms,
        container.vision.results,
        container.landingPlanner.phase,
    ) { base, station, alarms, vision, landingPhase ->
        val reference: GeoPoint? = base.route?.station ?: base.drone.homeLocation
        val distance = reference?.let { base.drone.distanceToM(it) }
        base.copy(
            station = station,
            alarms = alarms,
            returnThresholdPercent = container.batteryPolicy.returnThresholdPercent(distance),
            logFileName = container.fileLogger.file.name,
            isSimulation = container.backend.isSimulation,
            vision = vision,
            landingPhase = landingPhase,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** STOP: natychmiast, bez potwierdzenia — wywołanie nie czeka na wynik (sekcja 5.8). */
    fun stop() {
        safety.stop(source = "ui")
        _messages.tryEmit("STOP — zawis")
    }

    fun startCycle() {
        val route = orchestrator.activeRoute.value ?: backend.defaultRoute
        if (route == null) {
            logger.command("ui_start_cycle", CommandResult.Failure("brak trasy"))
            _messages.tryEmit("Brak trasy patrolu. Ekran planowania trasy powstaje w fazie F4.")
            return
        }
        runCommand("Start cyklu") { orchestrator.startCycle(route) }
    }

    fun returnToStation() = runCommand("Powrót do stacji") { orchestrator.returnToStation() }
    fun resume() = runCommand("Wznowienie") { orchestrator.resume() }
    fun reset() = runCommand("Reset") { orchestrator.reset() }

    private fun runCommand(name: String, block: suspend () -> CommandResult) {
        viewModelScope.launch {
            when (val result = block()) {
                CommandResult.Success -> _messages.tryEmit("$name: OK")
                is CommandResult.Failure -> _messages.tryEmit("$name: ${result.reason}")
            }
        }
    }
}
