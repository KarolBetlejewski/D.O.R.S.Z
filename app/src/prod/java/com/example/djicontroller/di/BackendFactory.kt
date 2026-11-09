package com.example.djicontroller.di

import android.content.Context
import android.view.Surface
import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.landing.LandingProcedure
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.port.CameraPort
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.ConnectionPort
import com.example.djicontroller.core.port.FlightControlPort
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.port.MissionPort
import com.example.djicontroller.core.port.SimulatorPort
import com.example.djicontroller.core.port.StationPort
import com.example.djicontroller.core.port.TelemetryPort
import com.example.djicontroller.msdk.MsdkBootstrap
import com.example.djicontroller.msdk.camera.CameraAdapter
import com.example.djicontroller.msdk.connection.DroneConnection
import com.example.djicontroller.msdk.connection.SdkInitializer
import com.example.djicontroller.msdk.flight.FlightControl
import com.example.djicontroller.msdk.flight.SimulatorAdapter
import com.example.djicontroller.msdk.telemetry.KeyRepository
import com.example.djicontroller.ui.VideoPreview
import kotlinx.coroutines.CoroutineScope

/** Wariant `prod`: wymaganie MSDK w `attachBaseContext`. */
val platformBootstrap = PlatformBootstrap { app -> MsdkBootstrap.installHelper(app) }

fun createDroneBackend(
    context: Context,
    scope: CoroutineScope,
    logger: FlightLogger,
    clock: Clock,
    arbiter: ControlArbiter,
    stickAxes: StickAxes,
): DroneBackend = DjiBackend(context, scope, logger, clock, arbiter, stickAxes)

/** Prawdziwy dron przez DJI MSDK V5 (:msdk-adapter). Moduły kolejnych faz — patrz UnavailablePorts.kt. */
private class DjiBackend(
    context: Context,
    scope: CoroutineScope,
    logger: FlightLogger,
    clock: Clock,
    arbiter: ControlArbiter,
    stickAxes: StickAxes,
) : DroneBackend {
    private val sdk = SdkInitializer(context, logger)
    private val keyRepository = KeyRepository(sdk, scope, logger, clock)
    private val cameraAdapter = CameraAdapter(sdk, scope, logger, clock)
    private val missionPort: MissionPort = UnavailableMission()

    override val name = "DJI MSDK"
    override val isSimulation = false
    override val connection: ConnectionPort = DroneConnection(sdk, scope, logger, clock)
    override val telemetry: TelemetryPort = keyRepository
    override val camera: CameraPort = cameraAdapter
    override val videoPreview: VideoPreview = object : VideoPreview {
        override fun attach(surface: Surface, width: Int, height: Int) = cameraAdapter.attachPreview(surface, width, height)
        override fun detach(surface: Surface) = cameraAdapter.detachPreview(surface)
    }
    override val flight: FlightControlPort =
        FlightControl(sdk, scope, arbiter, keyRepository, missionPort, logger, initialAxes = stickAxes)
    override val mission: MissionPort = missionPort
    override val simulator: SimulatorPort = SimulatorAdapter(sdk, scope, keyRepository, logger)
    override val station: StationPort = UnavailableStation()
    override val landing: LandingProcedure = UnavailableLanding()
    override val defaultRoute: PatrolRoute? = null
    override val debugActions: List<DebugAction> = emptyList()

    override fun start() {
        sdk.initialize()
        keyRepository.start()
    }
}
