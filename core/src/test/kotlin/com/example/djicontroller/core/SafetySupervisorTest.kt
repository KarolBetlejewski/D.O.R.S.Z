package com.example.djicontroller.core

import com.example.djicontroller.core.model.ConnectionState
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.MissionStatus
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.StationLink
import com.example.djicontroller.core.orchestrator.SafetyDirective
import com.example.djicontroller.core.orchestrator.SafetySupervisor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetySupervisorTest {

    /** Rig bez uruchomionego orkiestratora — dyrektywy odbiera bezpośrednio test. */
    private fun supervisorTest(block: suspend TestRig.(SafetySupervisor) -> Unit) = runBlocking {
        val job = Job()
        val rig = TestRig(CoroutineScope(coroutineContext + job))
        try {
            rig.block(rig.safety)
        } finally {
            job.cancel()
        }
    }

    private suspend fun SafetySupervisor.nextDirective(): SafetyDirective = withTimeout(1_000) { directives.first() }

    @Test fun `stop during mission pauses it and asks for hold`() = supervisorTest { safety ->
        mission.upload(testRoute().toMissionPlan("p1"))
        mission.start("p1")
        safety.stopNow()
        assertTrue(safety.nextDirective() is SafetyDirective.Hold)
        assertEquals(MissionStatus.PAUSED, mission.progress.value.status)
    }

    @Test fun `stop under virtual stick sends hover`() = supervisorTest { safety ->
        flight.startVirtualStick()
        flight.setVelocityTarget(Setpoint(1.0, 0.0, -0.5, 0.0))
        safety.stopNow()
        assertTrue(safety.nextDirective() is SafetyDirective.Hold)
        assertEquals(Setpoint.HOVER, flight.setpoints.last())
    }

    @Test fun `halt motion is idempotent for a paused mission`() = supervisorTest { safety ->
        mission.upload(testRoute().toMissionPlan("p1"))
        mission.start("p1")
        safety.haltMotion()
        safety.haltMotion()
        assertEquals(1, mission.calls.count { it == "pause" })
    }

    @Test fun `station loss during landing holds the drone`() = supervisorTest { safety ->
        connection.connectMini4Pro()
        telemetry.state.value = READY_ON_STATION.copy(isFlying = true, areMotorsOn = true)
        flight.startVirtualStick()
        val cycle = MutableStateFlow<CycleState>(CycleState.Landing(1))
        scope.launch { safety.monitor(cycle, flowOf(STATION)) }

        station.disconnect()
        val d = safety.nextDirective()
        assertTrue(d is SafetyDirective.Hold)
        assertTrue(d.reason, d.reason.contains("stacją"))
        assertEquals(Setpoint.HOVER, flight.setpoints.last())
        assertEquals("station", safety.alarms.value.single().key)
    }

    @Test fun `drone link loss in flight is a failure, on the ground it is ignored`() = supervisorTest { safety ->
        connection.connectMini4Pro()
        telemetry.state.value = READY_ON_STATION
        val cycle = MutableStateFlow<CycleState>(CycleState.Charging)
        scope.launch { safety.monitor(cycle, flowOf(STATION)) }

        connection.state.value = ConnectionState.Disconnected   // na ziemi: oczekiwane wyłączenie drona
        cycle.value = CycleState.Patrolling(1, 3)                 // w locie: awaria
        val d = safety.nextDirective()
        assertTrue(d is SafetyDirective.Fail)
        assertTrue(d.reason, d.reason.contains("failsafe"))
    }

    @Test fun `RC takeover fails the cycle, other control loss holds`() = supervisorTest { safety ->
        connection.connectMini4Pro()
        val cycle = MutableStateFlow<CycleState>(CycleState.Landing(1))
        station.publish()
        scope.launch { safety.monitor(cycle, flowOf(STATION)) }
        kotlinx.coroutines.delay(20)

        flight.controlLost.emit(ControlLoss("strefa zakazana", takenOverByRc = false))
        assertTrue(safety.nextDirective() is SafetyDirective.Hold)
        flight.controlLost.emit(ControlLoss("pilot przejął drążki", takenOverByRc = true))
        assertTrue(safety.nextDirective() is SafetyDirective.Fail)
    }

    @Test fun `assess ignores ground stages`() = supervisorTest { safety ->
        val findings = safety.assess(
            CycleState.Ready,
            READY_ON_STATION.copy(batteryPercent = 5),
            ConnectionState.Disconnected,
            StationLink.Disconnected,
            STATION,
        )
        assertTrue(findings.isEmpty())
        assertEquals(ControlOwner.NONE, flight.controlOwner.value)
    }
}
