package com.example.djicontroller.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.djicontroller.appContainer
import com.example.djicontroller.core.diagnostics.AxisCheckState
import com.example.djicontroller.core.diagnostics.AxisVerdict
import com.example.djicontroller.core.diagnostics.HoverTestState
import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.SimulatorStatus
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.model.VirtualStickStatus
import com.example.djicontroller.core.model.VisionFrameResult
import com.example.djicontroller.core.model.VisionStats
import com.example.djicontroller.core.port.LogLevel
import com.example.djicontroller.vision.CameraCalibration
import com.example.djicontroller.vision.FrameSnapshot
import com.example.djicontroller.vision.FrameSources
import com.example.djicontroller.vision.LandingPadLayout
import com.example.djicontroller.vision.VisionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stan ekranu Diagnostyka (F2: symulator MSDK, gimbal, wizja, Virtual Stick, testy R3/R4/R5). */
data class DiagnosticsUiState(
    val connection: ConnectionState = ConnectionState.Initializing,
    val drone: DroneState = DroneState.UNKNOWN,
    val cycle: CycleState = CycleState.Ready,
    val virtualStick: VirtualStickStatus = VirtualStickStatus(),
    val gimbalPitchDeg: Double? = null,
    val vision: VisionFrameResult? = null,
    val visionStats: VisionStats = VisionStats(),
    val visionOn: Boolean = false,
    val visionSource: String = VisionPipeline.CAMERA_SOURCE,
    val visionProblem: String? = null,
    val layout: LandingPadLayout = LandingPadLayout.STATION,
    val calibration: CameraCalibration? = null,
    val hover: HoverTestState = HoverTestState.Idle,
    val axisCheck: AxisCheckState = AxisCheckState.Idle,
    val runningTest: String? = null,
    /** `null` = brak symulatora MSDK w tym wariancie (demo). */
    val simulator: SimulatorStatus? = null,
    val isSimulation: Boolean = false,
    val autoConfirmLanding: Boolean = true,
    val hoverHeightM: Double = 3.0,
) {
    /** Testy i polecenia lotu tylko poza cyklem patrolu (Z2: orkiestrator nie steruje). */
    val cycleIdle: Boolean get() = cycle == CycleState.Ready || cycle is CycleState.Failure
}

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val backend = container.backend
    private val vision = container.vision
    private val logger = container.logger
    private val settings = container.settings

    val videoPreview: VideoPreview = backend.videoPreview

    private val autoConfirm = MutableStateFlow(settings.autoConfirmLanding)
    private val hoverHeight = MutableStateFlow(settings.hoverHeightM)

    private val partFlight = combine(
        container.connection.state,
        container.telemetry.state,
        container.orchestrator.state,
        backend.flight.virtualStick,
        backend.camera.gimbalPitchDeg,
    ) { conn, drone, cycle, vs, pitch -> DiagnosticsUiState(conn, drone, cycle, vs, pitch) }

    private val partVision = combine(
        vision.results,
        vision.stats,
        vision.users,
        vision.testSource,
        vision.layout,
    ) { result, stats, users, source, layout ->
        VisionPart(result, stats, UI_USER in users, source?.name ?: VisionPipeline.CAMERA_SOURCE, layout)
    }

    private val partTests = combine(
        container.hoverTest.state,
        container.axisCheck.state,
        container.flightTests.running,
        backend.simulator?.status ?: flowOf(null),
        vision.calibration,
    ) { hover, axis, running, sim, calib -> TestsPart(hover, axis, running, sim, calib) }

    val state: StateFlow<DiagnosticsUiState> = combine(
        partFlight,
        partVision,
        partTests,
        vision.problem,
        combine(autoConfirm, hoverHeight) { a, h -> a to h },
    ) { f, v, t, problem, (confirm, height) ->
        f.copy(
            vision = v.result,
            visionStats = v.stats,
            visionOn = v.on,
            visionSource = v.source,
            visionProblem = problem,
            layout = v.layout,
            calibration = t.calibration,
            hover = t.hover,
            axisCheck = t.axis,
            runningTest = t.running,
            simulator = t.simulator,
            isSimulation = backend.isSimulation,
            autoConfirmLanding = confirm,
            hoverHeightM = height,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState(isSimulation = backend.isSimulation))

    private data class VisionPart(
        val result: VisionFrameResult?,
        val stats: VisionStats,
        val on: Boolean,
        val source: String,
        val layout: LandingPadLayout,
    )

    private data class TestsPart(
        val hover: HoverTestState,
        val axis: AxisCheckState,
        val running: String?,
        val simulator: SimulatorStatus?,
        val calibration: CameraCalibration,
    )

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ------------------------------------------------------------------ STOP

    fun stop() {
        container.safety.stop(source = "diagnostics")
        _messages.tryEmit("STOP — zawis")
    }

    // ------------------------------------------------------------------ symulator MSDK

    /** Domyślne współrzędne startu symulatora: bieżąca pozycja, punkt startu albo stacja z trasy. */
    fun suggestedSimulatorLocation(): GeoPoint? {
        val d = container.telemetry.state.value
        return d.location?.takeIf { it.isValid }
            ?: d.homeLocation?.takeIf { it.isValid }
            ?: container.orchestrator.activeRoute.value?.station
    }

    fun setSimulator(enabled: Boolean, latitude: Double?, longitude: Double?) {
        val sim = backend.simulator ?: return
        viewModelScope.launch {
            val result = if (enabled) {
                if (latitude == null || longitude == null) {
                    CommandResult.Failure("Podaj współrzędne startowe")
                } else {
                    sim.enable(GeoPoint(latitude, longitude))
                }
            } else {
                sim.disable()
            }
            report(if (enabled) "Symulator MSDK" else "Wyłączenie symulatora", result)
        }
    }

    // ------------------------------------------------------------------ gimbal

    fun gimbalDown() = launchCommand("Gimbal −90°") { backend.camera.pointGimbalDown() }
    fun gimbalReset() = launchCommand("Reset gimbala") { backend.camera.resetGimbal() }

    // ------------------------------------------------------------------ wizja

    fun setVisionOn(on: Boolean) {
        if (on) vision.acquire(UI_USER) else vision.release(UI_USER)
    }

    fun useDroneCamera() = vision.setTestSource(null)

    fun useImage(uri: Uri) {
        keepPermission(uri)
        vision.setTestSource(FrameSources.fromImage(getApplication<Application>(), uri, container.clock))
        setVisionOn(true)
    }

    fun useVideo(uri: Uri) {
        keepPermission(uri)
        vision.setTestSource(FrameSources.fromVideo(getApplication<Application>(), uri, container.clock))
        setVisionOn(true)
    }

    fun setLayout(layout: LandingPadLayout) {
        settings.padLayout = layout
        vision.setLayout(layout)
    }

    fun resetVisionStats() = vision.resetStats()

    /** Zapisuje następną klatkę jako PNG (np. szachownica do kalibracji kamery). */
    fun saveFrame() {
        viewModelScope.launch {
            vision.acquire(SNAPSHOT_USER)
            try {
                val frame = vision.captureNextFrame()
                if (frame == null) {
                    _messages.tryEmit("Brak klatki z kamery w ciągu 3 s")
                    return@launch
                }
                val name = "frame_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.png"
                val file = File(container.framesDir, name)
                val ok = withContext(Dispatchers.IO) { FrameSnapshot.writePng(frame, file) }
                logger.system("frame_saved", if (ok) LogLevel.INFO else LogLevel.WARN, mapOf("file" to file.path, "ok" to ok))
                _messages.tryEmit(if (ok) "Zapisano ${file.name} (${frame.width}×${frame.height})" else "Nie udało się zapisać klatki")
            } finally {
                vision.release(SNAPSHOT_USER)
            }
        }
    }

    // ------------------------------------------------------------------ Virtual Stick i lot testowy

    fun setStickAxes(axes: StickAxes) {
        val result = backend.flight.setStickAxes(axes)
        if (result.isSuccess) settings.stickAxes = axes
        report("Mapowanie osi ${axes.name}", result)
    }

    fun startAxisCheck() = report("Test osi", container.axisCheck.start())
    fun stopAxisCheck() = container.axisCheck.stop()

    /** Przyjmuje zalecenie z testu osi: zamienione osie → przełącz mapowanie. */
    fun applyAxisRecommendation() {
        val result = (state.value.axisCheck as? AxisCheckState.Finished)?.result ?: return
        if (result.verdict != AxisVerdict.SWAPPED) return
        setStickAxes(if (result.axes == StickAxes.ROLL_FORWARD) StickAxes.PITCH_FORWARD else StickAxes.ROLL_FORWARD)
    }

    fun takeOff() = guardedCommand("Start") { backend.flight.takeOff() }

    fun autoLanding() = guardedCommand("Lądowanie automatyczne") {
        backend.flight.startAutoLanding(confirmAutomatically = autoConfirm.value)
    }

    fun confirmLanding() = launchCommand("Potwierdzenie lądowania") { backend.flight.confirmLanding() }

    fun setAutoConfirm(enabled: Boolean) {
        autoConfirm.value = enabled
        settings.autoConfirmLanding = enabled
    }

    // ------------------------------------------------------------------ R3: zawis nad markerem

    fun setHoverHeight(heightM: Double) {
        hoverHeight.value = heightM
        settings.hoverHeightM = heightM
    }

    fun startHover() = report("Zawis nad markerem", container.hoverTest.start(hoverHeight.value))
    fun stopHover() = container.hoverTest.stop()

    // ------------------------------------------------------------------ pomocnicze

    private fun guardedCommand(name: String, block: suspend () -> CommandResult) {
        if (!state.value.cycleIdle) {
            _messages.tryEmit("$name: niedostępne w trakcie cyklu patrolu")
            return
        }
        if (container.flightTests.running.value != null) {
            _messages.tryEmit("$name: najpierw zakończ test")
            return
        }
        launchCommand(name, block)
    }

    private fun launchCommand(name: String, block: suspend () -> CommandResult) {
        viewModelScope.launch {
            val result = block()
            logger.command("diag_${name.lowercase(Locale.ROOT).replace(' ', '_')}", result)
            report(name, result)
        }
    }

    private fun report(name: String, result: CommandResult) {
        _messages.tryEmit(
            when (result) {
                CommandResult.Success -> "$name: OK"
                is CommandResult.Failure -> "$name: ${result.reason}"
            },
        )
    }

    private fun keepPermission(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Część dostawców nie daje trwałego dostępu — wystarczy dostęp na czas działania procesu.
        }
    }

    override fun onCleared() {
        vision.release(SNAPSHOT_USER)
    }

    companion object {
        /** Użytkownik detekcji = przełącznik „Detekcja ArUco” (nakładka na obu ekranach). */
        const val UI_USER = "ui_overlay"
        private const val SNAPSHOT_USER = "snapshot"
    }
}
