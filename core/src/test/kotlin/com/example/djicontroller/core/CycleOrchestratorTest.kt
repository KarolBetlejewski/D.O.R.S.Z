package com.example.djicontroller.core

import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.MissionStatus
import com.example.djicontroller.core.model.PowerAction
import com.example.djicontroller.core.model.ReturnReason
import com.example.djicontroller.core.model.StationCommand
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleOrchestratorTest {

    @Test fun `full cycle goes through every stage and back to Ready`() = rigTest {
        assertEquals(CommandResult.Success, orchestrator.startCycle(testRoute()))
        awaitState { it == CycleState.Charging }
        awaitState { it == CycleState.Ready }

        val stages = transitions().map { it.substringBefore('(') }.distinct()
        assertEquals(
            listOf("Booting", "TakingOff", "Patrolling", "Returning", "Landing", "ShuttingDown", "Charging", "Ready"),
            stages,
        )
        assertTrue(transitions().contains(CycleState.Returning(ReturnReason.ROUTE_DONE, null).toString()))
        assertEquals(
            listOf(
                StationCommand.Charge(false),
                StationCommand.PowerButton(PowerAction.POWER_ON),
                StationCommand.PowerButton(PowerAction.POWER_OFF),
                StationCommand.Charge(true),
            ),
            station.commands,
        )
        assertEquals(1, mission.uploaded.size)           // trasa ukończona → bez misji powrotu
        assertEquals(4, mission.uploaded[0].waypoints.size) // 3 punkty + punkt nad stacją
        assertTrue("takeOff" in flight.calls)
        assertNull(orchestrator.breakpoint.value)
    }

    @Test fun `start is rejected when the landing pad is not clear`() = rigTest {
        station.publish(padClear = false)
        val result = orchestrator.startCycle(testRoute())
        assertTrue(result is CommandResult.Failure)
        assertTrue((result as CommandResult.Failure).reason.contains("płyta"))
        assertEquals(CycleState.Ready, orchestrator.state.value)
        assertTrue(station.commands.isEmpty())
    }

    @Test fun `boot fails with a readable reason when GPS is weak`() = rigTest {
        station.onCommand = { cmd ->
            if (cmd == StationCommand.PowerButton(PowerAction.POWER_ON)) {
                connection.connectMini4Pro()
                telemetry.state.value = READY_ON_STATION.copy(gpsSignalLevel = 2)
            }
            CommandResult.Success
        }
        orchestrator.startCycle(testRoute())
        val s = awaitState { it is CycleState.Failure } as CycleState.Failure
        assertTrue(s.cause, s.cause.contains("GPS"))
        assertFalse("takeOff" in flight.calls)
    }

    @Test fun `low battery returns home, saves breakpoint and next cycle resumes from it`() =
        rigTest(landingOutcomes = List(2) { LandingOutcome.Landed }) {
        patrolStopAfter = 1
        orchestrator.startCycle(testRoute())
        awaitState { it == CycleState.Patrolling(1, 3) }
        telemetry.update { it.copy(batteryPercent = 22) }

        awaitState { it is CycleState.Returning }
        awaitState { it == CycleState.Ready }
        assertTrue(transitions().contains(CycleState.Returning(ReturnReason.LOW_BATTERY, 1).toString()))
        assertEquals(1, breakpoints.values["r1"])
        assertEquals(2, mission.uploaded.size)           // patrol + trasa powrotu
        assertTrue(mission.uploaded[1].routeId.endsWith("-return"))

        // Kolejny cykl: naładowany dron, start od punktu przerwania, trasa do końca.
        patrolStopAfter = null
        orchestrator.startCycle(testRoute())
        awaitState { it == CycleState.Booting }
        awaitState { it == CycleState.Ready }
        assertEquals(1, mission.uploaded[2].fromIndex)
        assertEquals(3, mission.uploaded[2].waypoints.size)
        assertNull(breakpoints.values["r1"])             // trasa ukończona → punkt przerwania wyczyszczony
    }

    @Test fun `STOP pauses the mission, holds and resume continues the patrol`() = rigTest {
        patrolStopAfter = 0
        orchestrator.startCycle(testRoute())
        awaitState { it is CycleState.Patrolling }
        delay(40)

        safety.stop()
        val held = awaitState { it is CycleState.Holding } as CycleState.Holding
        assertTrue(held.resumeTo is CycleState.Patrolling)
        assertEquals(MissionStatus.PAUSED, mission.progress.value.status)

        assertEquals(CommandResult.Success, orchestrator.resume())
        awaitState { it is CycleState.Patrolling }
        awaitCondition(what = "wznowienie misji") { mission.progress.value.status == MissionStatus.EXECUTING }
        mission.finish()
        awaitState { it == CycleState.Ready }
    }

    @Test fun `operator return from holding goes home with the breakpoint`() = rigTest {
        patrolStopAfter = 0
        orchestrator.startCycle(testRoute())
        awaitState { it is CycleState.Patrolling }
        delay(40)
        safety.stop()
        awaitState { it is CycleState.Holding }

        assertEquals(CommandResult.Success, orchestrator.returnToStation())
        awaitState { it is CycleState.Returning }
        assertTrue(transitions().contains(CycleState.Returning(ReturnReason.OPERATOR, 0).toString()))
        awaitState { it == CycleState.Ready }
    }

    @Test fun `three lost markers end in failure and VS is released for the pilot`() =
        rigTest(landingOutcomes = List(3) { LandingOutcome.MarkerLost("brak markera") }) {
            landing.beforeOutcome = { flight.startVirtualStick() }
            orchestrator.startCycle(testRoute())
            val failure = awaitState { it is CycleState.Failure } as CycleState.Failure
            assertEquals(listOf(1, 2, 3), landing.attempts)
            assertTrue(failure.cause, failure.cause.contains("3 razy"))
            delay(20)
            assertTrue("stopVirtualStick" in flight.calls)
            assertEquals(ControlOwner.NONE, arbiter.owner.value)
        }

    @Test fun `critical battery during patrol lands in place`() = rigTest {
        patrolStopAfter = 0
        orchestrator.startCycle(testRoute())
        awaitState { it is CycleState.Patrolling }
        delay(40)
        telemetry.update { it.copy(batteryPercent = 8) }

        val failure = awaitState { it is CycleState.Failure } as CycleState.Failure
        assertTrue(failure.cause, failure.cause.contains("Lądowanie awaryjne"))
        delay(20)
        assertTrue("stop" in mission.calls)
        assertTrue("startAutoLanding" in flight.calls)
        assertEquals(ControlOwner.AUTO_LANDING, arbiter.owner.value)
    }

    @Test fun `reset is refused while the drone may be airborne`() = rigTest {
        patrolStopAfter = 0
        orchestrator.startCycle(testRoute())
        awaitState { it is CycleState.Patrolling }
        telemetry.update { it.copy(batteryPercent = 8) }
        awaitState { it is CycleState.Failure }

        assertTrue(orchestrator.reset() is CommandResult.Failure)
        telemetry.update { it.copy(isFlying = false, areMotorsOn = false) }
        assertEquals(CommandResult.Success, orchestrator.reset())
        assertEquals(CycleState.Ready, orchestrator.state.value)
    }
}
