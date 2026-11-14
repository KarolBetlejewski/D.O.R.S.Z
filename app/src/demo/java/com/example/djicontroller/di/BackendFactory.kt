package com.example.djicontroller.di

import android.content.Context
import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.model.PatrolWaypoint
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.SimulatorPort
import com.example.djicontroller.demo.DroneSimulator
import com.example.djicontroller.demo.SimulatedCamera
import com.example.djicontroller.demo.SimulatedConnection
import com.example.djicontroller.demo.SimulatedFlight
import com.example.djicontroller.demo.SimulatedMission
import com.example.djicontroller.demo.SimulatedStation
import com.example.djicontroller.demo.SimulatedTelemetry
import com.example.djicontroller.demo.SimulatedVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wariant `demo` nie ładuje żadnych bibliotek natywnych DJI — nic do zrobienia. */
val platformBootstrap = PlatformBootstrap { }

fun createDroneBackend(
    context: Context,
    scope: CoroutineScope,
    logger: FlightLogger,
    clock: Clock,
    arbiter: ControlArbiter,
    stickAxes: StickAxes,
): DroneBackend = SimulatedBackend(scope, logger, clock, arbiter, stickAxes = stickAxes)

/** Stacja demo: Pole Mokotowskie w Warszawie — otwarty teren, dobrze widoczny na mapie OSM. */
val DEMO_STATION = GeoPoint(52.21305, 20.99620)

/** Przykładowa trasa: 6 punktów na 30 m wokół stacji, ok. 1 km. */
val DEMO_ROUTE = PatrolRoute(
    id = "demo-patrol",
    name = "Patrol demo",
    station = DEMO_STATION,
    waypoints = listOf(
        PatrolWaypoint(DEMO_STATION.offsetBy(120.0, -60.0), 30.0),
        PatrolWaypoint(DEMO_STATION.offsetBy(190.0, 60.0), 30.0),
        PatrolWaypoint(DEMO_STATION.offsetBy(130.0, 180.0), 30.0),
        PatrolWaypoint(DEMO_STATION.offsetBy(-30.0, 210.0), 30.0),
        PatrolWaypoint(DEMO_STATION.offsetBy(-110.0, 110.0), 30.0),
        PatrolWaypoint(DEMO_STATION.offsetBy(-70.0, -30.0), 30.0),
    ),
    cruiseSpeedMps = 5.0,
)

class SimulatedBackend(
    private val scope: CoroutineScope,
    logger: FlightLogger,
    clock: Clock,
    arbiter: ControlArbiter,
    timeScale: Double = 3.0,
    chargeRealSeconds: Double = 15.0,
    stickAxes: StickAxes = StickAxes.ROLL_FORWARD,
) : DroneBackend {
    /** Symulowany dron (nie mylić z [simulator] — symulatorem MSDK, którego w demo nie ma). */
    val drone = DroneSimulator(scope, clock, logger, DEMO_STATION, timeScale)
    private val simFlight = SimulatedFlight(drone, arbiter, scope, clock, logger, stickAxes)
    private val simCamera = SimulatedCamera(drone, scope, clock, logger)
    private val simStation = SimulatedStation(drone, scope, clock, logger, chargeRealSeconds)

    override val name = "SYMULACJA"
    override val isSimulation = true
    override val connection = SimulatedConnection(drone)
    override val telemetry = SimulatedTelemetry(drone)
    override val camera = simCamera
    override val videoPreview = SimulatedVideo(drone, simCamera, scope)
    override val flight = simFlight
    override val mission = SimulatedMission(drone, arbiter, scope, logger)
    override val station = simStation
    override val defaultRoute: PatrolRoute = DEMO_ROUTE

    /** W demo cały dron jest symulowany — symulator MSDK nie ma tu zastosowania. */
    override val simulator: SimulatorPort? = null

    override val debugActions: List<DebugAction> = listOf(
        DebugAction("Bateria −20%") { drone.drainBattery(20) },
        DebugAction("Zasłoń marker 5 s") {
            drone.markerHidden = true
            scope.launch { delay(5_000); drone.markerHidden = false }
        },
        DebugAction("Łącze stacji ⇄") { simStation.toggleLink() },
        DebugAction("Płyta zajęta ⇄") { simStation.togglePad() },
        DebugAction("Łącze drona ⇄") { drone.setLinkCut(!drone.linkCut) },
        DebugAction("Przejęcie z RC") { simFlight.simulateRcTakeover() },
        DebugAction("Dryf/wiatr ⇄") { drone.windMps = if (drone.windMps > 0) 0.0 else 0.3 },
    )

    override fun start() {
        drone.start()
        simStation.start()
    }
}
