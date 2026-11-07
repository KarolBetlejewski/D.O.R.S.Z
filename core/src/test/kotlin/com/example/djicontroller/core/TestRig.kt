package com.example.djicontroller.core

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.fake.FakeClock
import com.example.djicontroller.core.fake.FakeConnection
import com.example.djicontroller.core.fake.FakeFlightControl
import com.example.djicontroller.core.fake.FakeLanding
import com.example.djicontroller.core.fake.FakeMission
import com.example.djicontroller.core.fake.FakeStation
import com.example.djicontroller.core.fake.FakeTelemetry
import com.example.djicontroller.core.fake.InMemoryBreakpointStore
import com.example.djicontroller.core.fake.RecordingLogger
import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.FailsafeAction
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.model.PatrolWaypoint
import com.example.djicontroller.core.model.PowerAction
import com.example.djicontroller.core.model.StationCommand
import com.example.djicontroller.core.orchestrator.BatteryPolicy
import com.example.djicontroller.core.orchestrator.CycleConfig
import com.example.djicontroller.core.orchestrator.CycleOrchestrator
import com.example.djicontroller.core.orchestrator.SafetySupervisor
import com.example.djicontroller.core.port.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

val STATION = GeoPoint(52.2297, 21.0122)

fun testRoute(id: String = "r1") = PatrolRoute(
    id = id,
    name = "Patrol testowy",
    station = STATION,
    waypoints = listOf(
        PatrolWaypoint(STATION.offsetBy(200.0, 0.0), 30.0),
        PatrolWaypoint(STATION.offsetBy(200.0, 200.0), 30.0),
        PatrolWaypoint(STATION.offsetBy(0.0, 200.0), 30.0),
    ),
)

/** Dron gotowy do startu, stojący na stacji. */
val READY_ON_STATION = DroneState(
    connected = true,
    batteryPercent = 100,
    location = STATION,
    altitudeM = 0.0,
    gpsSatelliteCount = 18,
    gpsSignalLevel = 5,
    isFlying = false,
    areMotorsOn = false,
    homeLocation = STATION,
    failsafeAction = FailsafeAction.GO_HOME,
)

/**
 * Kompletny zestaw fake'ów z orkiestratorem. Domyślnie symuluje „posłusznego” drona i stację:
 * włączenie przyciskiem łączy drona, start podnosi go na 2 m, misja przelatuje wszystkie punkty.
 */
class TestRig(
    val scope: CoroutineScope,
    config: CycleConfig = CycleConfig(
        powerOnTimeoutMs = 2_000,
        preflightTimeoutMs = 2_000,
        takeoffTimeoutMs = 2_000,
        motorsOffTimeoutMs = 2_000,
        powerOffTimeoutMs = 2_000,
        chargeTimeoutMs = 2_000,
        returnTimeoutMarginMs = 2_000,
    ),
    landingOutcomes: List<LandingOutcome> = listOf(LandingOutcome.Landed),
) {
    val clock = FakeClock()
    val logger = RecordingLogger()
    val arbiter = ControlArbiter(logger)
    val telemetry = FakeTelemetry()
    val connection = FakeConnection()
    val flight = FakeFlightControl(arbiter)
    val mission = FakeMission(arbiter)
    val station = FakeStation(clock)
    val landing = FakeLanding(*landingOutcomes.toTypedArray())
    val breakpoints = InMemoryBreakpointStore()
    val batteryPolicy = BatteryPolicy()
    val safety = SafetySupervisor(scope, telemetry, connection, flight, mission, station, batteryPolicy, logger, clock)
    val orchestrator = CycleOrchestrator(
        scope, telemetry, connection, flight, mission, station, landing, safety,
        batteryPolicy, breakpoints, logger, clock, config,
    )

    /** Gdy ustawione, symulowany przelot patrolu zatrzymuje się po osiągnięciu tego indeksu misji (do testów przerwań). */
    var patrolStopAfter: Int? = null

    init {
        station.publish()
        station.onCommand = { cmd ->
            when (cmd) {
                StationCommand.PowerButton(PowerAction.POWER_ON) -> {
                    connection.connectMini4Pro()
                    telemetry.state.value = READY_ON_STATION
                }
                StationCommand.PowerButton(PowerAction.POWER_OFF) -> connection.disconnect()
                StationCommand.Charge(true) -> scope.launch {
                    delay(20); clock.advance(10); station.publish(charging = true, ready = false)
                    delay(20); clock.advance(10); station.publish(charging = false, ready = true)
                }
                else -> Unit
            }
            CommandResult.Success
        }
        flight.onTakeOff = {
            telemetry.update { it.copy(isFlying = true, areMotorsOn = true, altitudeM = 2.0) }
            CommandResult.Success
        }
        mission.onStarted = { plan ->
            scope.launch {
                if (plan.routeId.endsWith("-return")) {
                    delay(20)
                    telemetry.update { it.copy(location = STATION, altitudeM = 8.0) }
                    mission.finish()
                } else {
                    telemetry.update { it.copy(altitudeM = 30.0) }
                    for (i in plan.waypoints.indices) {
                        delay(20)
                        telemetry.update { it.copy(location = plan.waypoints[i].location) }
                        mission.reach(i)
                        if (i == patrolStopAfter) return@launch
                    }
                    delay(20)
                    mission.finish()
                }
            }
        }
        landing.beforeOutcome = { attempt ->
            if (attempt >= 1) telemetry.update { it.copy(isFlying = false, areMotorsOn = false, altitudeM = 0.0) }
        }
    }

    /** Kolejne stany docelowe z logu przejść (StateFlow może zgubić szybkie przejścia). */
    fun transitions(): List<String> = logger.entries
        .filter { it.category == LogCategory.STATE && it.event == "transition" }
        .map { it.fields["to"] as String }

    suspend fun awaitState(timeoutMs: Long = 3_000, predicate: (CycleState) -> Boolean): CycleState {
        awaitCondition(timeoutMs, "stan; jest ${orchestrator.state.value}") { predicate(orchestrator.state.value) }
        return orchestrator.state.value
    }

    suspend fun awaitCondition(timeoutMs: Long = 3_000, what: String = "warunek", condition: () -> Boolean) {
        val ok = withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(5)
            true
        }
        if (ok == null) {
            throw AssertionError("Nie doczekano się: $what\nPrzejścia: ${transitions()}\nLog: ${logger.entries.takeLast(15)}")
        }
    }
}

/** Uruchamia test z rigiem i sprząta wszystkie korutyny po zakończeniu. */
fun rigTest(
    landingOutcomes: List<LandingOutcome> = listOf(LandingOutcome.Landed),
    block: suspend TestRig.() -> Unit,
) = runBlocking {
    val job = Job()
    val rig = TestRig(CoroutineScope(coroutineContext + job), landingOutcomes = landingOutcomes)
    rig.orchestrator.start()
    try {
        rig.block()
    } finally {
        job.cancel()
    }
}
