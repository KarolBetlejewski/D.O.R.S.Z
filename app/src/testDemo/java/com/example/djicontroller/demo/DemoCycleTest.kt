package com.example.djicontroller.demo

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.fake.FakeVision
import com.example.djicontroller.core.fake.InMemoryBreakpointStore
import com.example.djicontroller.core.fake.RecordingLogger
import com.example.djicontroller.core.landing.LandingPlanner
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.MissionStatus
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.model.PatrolWaypoint
import com.example.djicontroller.core.orchestrator.BatteryPolicy
import com.example.djicontroller.core.orchestrator.CycleOrchestrator
import com.example.djicontroller.core.orchestrator.SafetySupervisor
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prawdziwy orkiestrator i nadzór z :core na symulatorze z wariantu demo (czas ×10).
 * Sprawdza, że symulator daje sygnały, przy których cykl przechodzi od startu do naładowania.
 */
class DemoCycleTest {

    private val station = GeoPoint(52.21305, 20.99620)
    private val route = PatrolRoute(
        id = "test",
        name = "test",
        station = station,
        waypoints = listOf(
            PatrolWaypoint(station.offsetBy(60.0, 0.0), 20.0),
            PatrolWaypoint(station.offsetBy(60.0, 60.0), 20.0),
            PatrolWaypoint(station.offsetBy(0.0, 60.0), 20.0),
        ),
    )

    private class World(scope: CoroutineScope, station: GeoPoint) {
        val clock = SystemClock
        val logger = RecordingLogger()
        val arbiter = ControlArbiter(logger)
        val sim = DroneSimulator(scope, clock, logger, station, timeScale = 10.0, drainPercentPerSecond = 0.05)
        val flight = SimulatedFlight(sim, arbiter, scope, clock, logger)
        val mission = SimulatedMission(sim, arbiter, scope, logger)
        val stationPort = SimulatedStation(sim, scope, clock, logger, chargeRealSeconds = 1.0)
        val camera = SimulatedCamera(sim, scope, clock, logger)
        val vision = FakeVision()
        val landing = LandingPlanner(
            flight, mission, camera, vision, SimulatedTelemetry(sim), stationPort, { station }, logger, clock,
        )
        val policy = BatteryPolicy()
        val safety = SafetySupervisor(
            scope, SimulatedTelemetry(sim), SimulatedConnection(sim), flight, mission, stationPort, policy, logger, clock,
        )
        val orchestrator = CycleOrchestrator(
            scope, SimulatedTelemetry(sim), SimulatedConnection(sim), flight, mission, stationPort, landing, safety,
            policy, InMemoryBreakpointStore(), logger, clock,
        )

        init {
            scope.feedMarkerFromSimulator(sim, vision, clock)
            sim.start()
            stationPort.start()
            orchestrator.start()
        }

        fun transitions() = logger.entries
            .filter { it.category == LogCategory.STATE && it.event == "transition" }
            .map { it.fields["to"].toString() }

        suspend fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
            val ok = withTimeoutOrNull(timeoutMs) {
                while (!condition()) delay(20)
                true
            }
            if (ok == null) throw AssertionError("Nie doczekano się: $what; stan=${orchestrator.state.value}; przejścia=${transitions()}")
        }
    }

    private fun world(block: suspend World.() -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            World(scope, station).block()
        } finally {
            scope.cancel()
        }
    }

    @Test fun `full cycle on the simulator ends charged on the pad`() = world {
        await(5_000, "SDK gotowe") { sim.connection.value !is com.example.djicontroller.core.model.ConnectionState.Initializing &&
            sim.connection.value !is com.example.djicontroller.core.model.ConnectionState.Registering }
        assertEquals(CommandResult.Success, orchestrator.startCycle(route))
        await(60_000, "Charging") { orchestrator.state.value == CycleState.Charging }
        await(10_000, "Ready") { orchestrator.state.value == CycleState.Ready }

        val stages = transitions().map { it.substringBefore('(') }.distinct()
        assertEquals(
            listOf("Booting", "TakingOff", "Patrolling", "Returning", "Landing", "ShuttingDown", "Charging", "Ready"),
            stages,
        )
        assertTrue("Dron powinien stać na stacji", sim.currentPosition.distanceTo(station) < 0.3)
        assertEquals(100, sim.batteryPercent)
    }

    @Test fun `STOP during patrol pauses the drone and resume finishes the cycle`() = world {
        await(5_000, "SDK gotowe") { sim.connection.value is com.example.djicontroller.core.model.ConnectionState.Disconnected }
        orchestrator.startCycle(route)
        await(30_000, "Patrolling") { orchestrator.state.value is CycleState.Patrolling }
        delay(300)

        safety.stop("test")
        await(5_000, "Holding") { orchestrator.state.value is CycleState.Holding }
        assertEquals(MissionStatus.PAUSED, mission.progress.value.status)
        val frozen = sim.currentPosition
        delay(500)
        assertTrue("Dron nie powinien się ruszać w zawisie", sim.currentPosition.distanceTo(frozen) < 0.01)

        assertEquals(CommandResult.Success, orchestrator.resume())
        await(60_000, "Ready po wznowieniu") { orchestrator.state.value == CycleState.Ready }
    }
}
