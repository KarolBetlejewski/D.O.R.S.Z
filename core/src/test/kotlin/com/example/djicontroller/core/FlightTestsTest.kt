package com.example.djicontroller.core

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.diagnostics.AxisVerdict
import com.example.djicontroller.core.diagnostics.FlightTestRunner
import com.example.djicontroller.core.diagnostics.HoverTestState
import com.example.djicontroller.core.diagnostics.MarkerHoverTest
import com.example.djicontroller.core.diagnostics.MarkerHoverTestConfig
import com.example.djicontroller.core.diagnostics.StickAxisCheck
import com.example.djicontroller.core.fake.FakeCamera
import com.example.djicontroller.core.fake.FakeFlightControl
import com.example.djicontroller.core.fake.FakeTelemetry
import com.example.djicontroller.core.fake.FakeVision
import com.example.djicontroller.core.fake.RecordingLogger
import com.example.djicontroller.core.model.BodyVelocity
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.port.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightTestsTest {

    private class Rig(scope: CoroutineScope, cycleIdle: Boolean = true, flying: Boolean = true) {
        val logger = RecordingLogger()
        val arbiter = ControlArbiter(logger)
        val flight = FakeFlightControl(arbiter)
        val camera = FakeCamera()
        val vision = FakeVision()
        val telemetry = FakeTelemetry(READY_ON_STATION.copy(isFlying = flying, areMotorsOn = flying))
        val stopEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val runner = FlightTestRunner(scope, flight, telemetry, stopEvents, { cycleIdle }, logger)
        val hover = MarkerHoverTest(
            runner, flight, camera, vision, telemetry, logger, SystemClock,
            MarkerHoverTestConfig(settleMs = 0, markerLostAbortMs = 700, holdLastCommandMs = 150),
        )

        /** Symulowana kamera: świeża obserwacja co 70 ms. */
        fun feedMarker(scope: CoroutineScope, dx: Double = 0.5, dy: Double = 0.0, dz: Double = 3.0): Job = scope.launch {
            while (isActive) {
                vision.publish(MarkerObservation(16, dx, dy, dz, 0.0, SystemClock.monotonicMs(), latencyMs = 80))
                delay(70)
            }
        }
    }

    private suspend fun MarkerHoverTest.awaitFinished(): HoverTestState.Finished =
        withTimeout(5_000) { state.first { it is HoverTestState.Finished } as HoverTestState.Finished }

    @Test
    fun `zawis nad markerem leci do markera i po zakonczeniu oddaje sterowanie`() = runBlocking {
        val rig = Rig(this)
        val feed = rig.feedMarker(this, dx = 0.5)
        assertEquals(CommandResult.Success, rig.hover.start(3.0))
        delay(600)
        assertTrue("gimbalDown" in rig.camera.calls)
        assertTrue(MarkerHoverTest.VISION_USER in rig.vision.users.value)
        assertTrue(rig.flight.setpoints.any { it.forwardMps > 0.1 })
        rig.hover.stop()
        val end = rig.hover.awaitFinished()
        feed.cancel()
        assertTrue(end.success)
        assertTrue(end.summary!!.samples > 0)
        assertEquals(Setpoint.HOVER, rig.flight.setpoints.last())
        assertTrue("stopVirtualStick" in rig.flight.calls)
        assertEquals(ControlOwner.NONE, rig.arbiter.owner.value)
        assertTrue(rig.vision.users.value.isEmpty())
        assertTrue("r3_summary" in rig.logger.events())
    }

    @Test
    fun `nigdy nie opada ponizej wysokosci docelowej`() = runBlocking {
        val rig = Rig(this)
        val feed = rig.feedMarker(this, dx = 0.0, dz = 2.0) // dron na 2 m, cel 3 m
        rig.hover.start(3.0)
        delay(500)
        rig.hover.stop()
        rig.hover.awaitFinished()
        feed.cancel()
        assertTrue(rig.flight.setpoints.none { it.upMps < 0 })
        assertTrue(rig.flight.setpoints.any { it.upMps > 0 })
    }

    @Test
    fun `STOP przerywa test i zostawia zawis`() = runBlocking {
        val rig = Rig(this)
        val feed = rig.feedMarker(this)
        rig.hover.start(3.0)
        delay(400)
        rig.stopEvents.emit("ui")
        val end = rig.hover.awaitFinished()
        feed.cancel()
        assertFalse(end.success)
        assertTrue(end.reason.contains("STOP"))
        assertEquals(Setpoint.HOVER, rig.flight.setpoints.last())
        assertEquals(ControlOwner.NONE, rig.arbiter.owner.value)
    }

    @Test
    fun `utrata sterowania przerywa test`() = runBlocking {
        val rig = Rig(this)
        val feed = rig.feedMarker(this)
        rig.hover.start(3.0)
        delay(400)
        rig.flight.controlLost.emit(ControlLoss("RC_PAUSE_STOP", takenOverByRc = true))
        val end = rig.hover.awaitFinished()
        feed.cancel()
        assertTrue(end.reason.contains("utrata sterowania"))
        assertEquals(ControlOwner.NONE, rig.arbiter.owner.value)
    }

    @Test
    fun `bez markera zawis a po limicie koniec testu`() = runBlocking {
        val rig = Rig(this)
        val feed = rig.feedMarker(this, dx = 0.8)
        rig.hover.start(3.0)
        delay(500)
        feed.cancel()
        rig.vision.publish(null)
        delay(350) // > holdLastCommandMs
        assertEquals(Setpoint.HOVER, rig.flight.setpoints.last())
        val end = rig.hover.awaitFinished()
        assertTrue(end.reason.contains("marker niewidoczny"))
    }

    @Test
    fun `test nie startuje w trakcie cyklu ani na ziemi`() = runBlocking {
        val busy = Rig(this, cycleIdle = false)
        assertTrue(busy.hover.start(3.0) is CommandResult.Failure)
        val ground = Rig(this, flying = false)
        assertTrue(ground.hover.start(3.0) is CommandResult.Failure)
        assertFalse("startVirtualStick" in ground.flight.calls)
        val tooLow = Rig(this)
        assertTrue(tooLow.hover.start(0.3) is CommandResult.Failure)
    }

    @Test
    fun `drugi test nie startuje rownolegle`() = runBlocking {
        val rig = Rig(this)
        val feed = rig.feedMarker(this)
        rig.hover.start(3.0)
        delay(100)
        val axis = StickAxisCheck(rig.runner, rig.flight, rig.telemetry, rig.logger, SystemClock)
        assertTrue(axis.start() is CommandResult.Failure)
        rig.hover.stop()
        rig.hover.awaitFinished()
        feed.cancel()
    }

    @Test
    fun `werdykt testu osi`() = runBlocking {
        val rig = Rig(this)
        val check = StickAxisCheck(rig.runner, rig.flight, rig.telemetry, rig.logger, SystemClock, speedMps = 0.5)
        fun v(f: Double, r: Double) = BodyVelocity(f, r, 0.0)
        assertEquals(AxisVerdict.OK, check.verdict(v(0.48, 0.02), v(-0.03, 0.5)))
        assertEquals(AxisVerdict.SWAPPED, check.verdict(v(0.01, 0.47), v(0.5, 0.05)))
        assertEquals(AxisVerdict.INVERTED, check.verdict(v(-0.5, 0.0), v(0.0, 0.5)))
        assertEquals(AxisVerdict.NO_MOTION, check.verdict(v(0.02, 0.01), v(0.0, 0.03)))
    }
}
