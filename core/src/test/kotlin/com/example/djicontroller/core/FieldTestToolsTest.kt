package com.example.djicontroller.core

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.diagnostics.CheckState
import com.example.djicontroller.core.diagnostics.CompatibilityEvents
import com.example.djicontroller.core.diagnostics.CompatibilityMonitor
import com.example.djicontroller.core.diagnostics.FlightTestRunner
import com.example.djicontroller.core.diagnostics.PadLandingState
import com.example.djicontroller.core.diagnostics.PadLandingTest
import com.example.djicontroller.core.diagnostics.PadOnlyStation
import com.example.djicontroller.core.fake.FakeCamera
import com.example.djicontroller.core.fake.FakeClock
import com.example.djicontroller.core.fake.FakeConnection
import com.example.djicontroller.core.fake.FakeFlightControl
import com.example.djicontroller.core.fake.FakeMission
import com.example.djicontroller.core.fake.FakeStation
import com.example.djicontroller.core.fake.FakeTelemetry
import com.example.djicontroller.core.fake.FakeVision
import com.example.djicontroller.core.fake.RecordingLogger
import com.example.djicontroller.core.landing.LandingPlanner
import com.example.djicontroller.core.landing.LandingStep
import com.example.djicontroller.core.logging.command
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.FailsafeAction
import com.example.djicontroller.core.model.VisionFrameResult
import com.example.djicontroller.core.model.Velocity3D
import com.example.djicontroller.core.port.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Narzędzia do testów polowych z Diagnostyki: lądowanie bez stacji (R5) i raport zgodności (F0). */
class FieldTestToolsTest {

    private class PadRig(scope: CoroutineScope) {
        val clock = FakeClock()
        val logger = RecordingLogger()
        val arbiter = ControlArbiter(logger)
        val telemetry = FakeTelemetry(DroneState(connected = true, isFlying = true, areMotorsOn = true, homeLocation = STATION))
        val flight = FakeFlightControl(arbiter)
        val mission = FakeMission(arbiter)
        val camera = FakeCamera()
        val vision = FakeVision()
        val world = LandingWorld(clock, telemetry, flight, camera, vision, FakeStation(clock)).apply {
            placeAt(north = 1.0, east = -0.5, alt = 4.0, headingDeg = 40.0)
        }
        val stop = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val station = PadOnlyStation(scope, telemetry, clock)
        val planner = LandingPlanner(
            flight, mission, camera, vision, telemetry, station,
            stationLocation = { telemetry.state.value.homeLocation }, logger = logger, clock = clock, ticker = world,
        )
        val runner = FlightTestRunner(scope, flight, telemetry, stop, isCycleIdle = { true }, logger = logger)
        val test = PadLandingTest(runner, planner, logger, clock)

        suspend fun awaitFinished(): PadLandingState.Finished {
            while (test.state.value !is PadLandingState.Finished) yield()
            return test.state.value as PadLandingState.Finished
        }
    }

    private fun padTest(block: suspend PadRig.() -> Unit) = runBlocking {
        val job = Job()
        try {
            PadRig(CoroutineScope(coroutineContext + job)).block()
        } finally {
            job.cancel()
        }
    }

    @Test fun `R5 bez stacji - pelne ladowanie na plycie z punktu startu`() = padTest {
        assertEquals(CommandResult.Success, test.start())
        val result = awaitFinished()

        assertTrue(result.reason, result.success)
        assertEquals(1, result.attempts)
        assertTrue("odległość ${world.touchdownOffsetM}", world.touchdownOffsetM!! < 0.2)
        assertTrue("potwierdzenie ochrony lądowania", "confirmLanding" in flight.calls)
        assertEquals(ControlOwner.NONE, arbiter.owner.value)
        assertTrue(logger.entries.any { it.event == "test_${PadLandingTest.TEST_NAME}_result" && it.fields["success"] == true })
    }

    @Test fun `R5 bez stacji - STOP w L3 konczy test zawisem`() = padTest {
        var sent = false
        world.onStep = {
            if (!sent && planner.phase.value?.step == LandingStep.L3_DESCENT && alt < 3.5) {
                sent = true
                stop.tryEmit("test")
            }
        }
        test.start()
        val result = awaitFinished()

        assertFalse(result.success)
        assertTrue(result.reason, result.reason.contains("STOP"))
        assertTrue(world.flying)
        assertEquals(ControlOwner.NONE, arbiter.owner.value)
        assertFalse("startAutoLanding" in flight.calls)
    }

    @Test fun `R5 bez stacji - odmowa, gdy dron nie lata`() = padTest {
        telemetry.update { it.copy(isFlying = false) }
        val r = test.start()
        assertTrue(r is CommandResult.Failure)
        assertFalse((test.state.value as PadLandingState.Finished).success)
    }

    @Test fun `stacja zastepcza - dron na plycie dopiero po wylaczeniu silnikow`() = padTest {
        assertFalse(station.link.value.let { (it as com.example.djicontroller.core.model.StationLink.Connected).status!!.dronePresent })
        telemetry.update { it.copy(isFlying = false, areMotorsOn = false) }
        yield()
        val status = (station.link.value as com.example.djicontroller.core.model.StationLink.Connected).status!!
        assertTrue(status.dronePresent)
        assertTrue(status.padClear)
    }

    // ------------------------------------------------------------------ raport zgodności

    @Test fun `raport zgodnosci - wartosci, bledy kluczy i wyniki polecen`() = runBlocking {
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val logger = RecordingLogger()
        val events = CompatibilityEvents()
        val connection = FakeConnection()
        val telemetry = FakeTelemetry()
        val flight = FakeFlightControl(ControlArbiter(logger))
        val camera = FakeCamera().apply { gimbalPitchDeg.value = null }
        val vision = MutableStateFlow<VisionFrameResult?>(null)
        val monitor = CompatibilityMonitor(scope, connection, telemetry, flight, camera, vision, events) { it.source == "kamera drona" }

        fun check(id: String) = monitor.checks.value.first { it.id == id }
        suspend fun settle() = repeat(20) { yield() }
        settle()
        assertEquals(CheckState.WAITING, check("KeyUltrasonicHeight").state)
        assertEquals(CheckState.WAITING, check("KeyStartTakeoff").state)

        connection.connectMini4Pro()
        telemetry.update {
            it.copy(
                connected = true, ultrasonicHeightM = 0.3, failsafeAction = FailsafeAction.HOVER,
                velocity = Velocity3D(0.0, 0.0, 0.0), goHomeHeightM = 30, firmwareVersion = "01.00.0800",
            )
        }
        events.system("telemetry_key_failed", LogLevel.ERROR, mapOf("key" to "KeyHeightLimit", "error" to "NOT_SUPPORT"))
        events.command("takeoff", CommandResult.Success)
        events.command("landing_confirm", CommandResult.Failure("not in landing"))
        vision.value = VisionFrameResult(1, 1280, 720, emptyList(), null, 5, 10, "kamera drona")
        camera.gimbalPitchDeg.value = -90.0
        settle()

        assertEquals(CheckState.OK, check("ProductType").state)
        assertEquals("01.00.0800", check("KeyFirmwareVersion").value)
        assertEquals("0.3 m", check("KeyUltrasonicHeight").value)
        assertEquals(CheckState.FAILED, check("KeyHeightLimit").state)
        assertEquals("NOT_SUPPORT", check("KeyHeightLimit").note)
        assertEquals("zalecane: powrót do domu", check("KeyFailsafeAction").note)
        assertEquals(CheckState.OK, check("KeyStartTakeoff").state)
        assertEquals(CheckState.FAILED, check("KeyConfirmLanding").state)
        assertEquals("1280×720", check("CameraStream").value)
        assertEquals(CheckState.OK, check("KeyGimbalAttitude").state)

        // Zanik wartości po rozłączeniu nie kasuje raportu.
        telemetry.update { DroneState.UNKNOWN }
        settle()
        assertEquals(CheckState.OK, check("KeyUltrasonicHeight").state)

        val report = monitor.report("test")
        assertTrue(report, report.contains("[BŁĄD] Limit wysokości (KeyHeightLimit)"))
        assertTrue(report, report.contains("[OK]   Czujnik ToF"))
        assertTrue(report, report.contains("Podsumowanie:"))
        job.cancel()
    }
}
