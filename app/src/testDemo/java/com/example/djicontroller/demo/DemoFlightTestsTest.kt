package com.example.djicontroller.demo

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.diagnostics.AxisCheckState
import com.example.djicontroller.core.diagnostics.AxisVerdict
import com.example.djicontroller.core.diagnostics.FlightTestRunner
import com.example.djicontroller.core.diagnostics.HoverTestState
import com.example.djicontroller.core.diagnostics.MarkerHoverTest
import com.example.djicontroller.core.diagnostics.MarkerHoverTestConfig
import com.example.djicontroller.core.diagnostics.StickAxisCheck
import com.example.djicontroller.core.fake.FakeVision
import com.example.djicontroller.core.fake.RecordingLogger
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.core.port.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Testy F2 na symulatorze demo z dynamiką (τ = 0,35 s) i dryfem: R3 (zawis nad markerem)
 * oraz test osi Virtual Stick dla obu mapowań. Czas rzeczywisty (timeScale = 1).
 */
class DemoFlightTestsTest {

    private val station = GeoPoint(52.21305, 20.99620)

    private class Rig(scope: CoroutineScope, station: GeoPoint, axes: StickAxes) {
        val clock = SystemClock
        val logger = RecordingLogger()
        val arbiter = ControlArbiter(logger)
        val sim = DroneSimulator(scope, clock, logger, station, timeScale = 1.0)
        val flight = SimulatedFlight(sim, arbiter, scope, clock, logger, axes)
        val camera = SimulatedCamera(sim, scope, clock, logger)
        val vision = FakeVision()
        val stops = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val runner = FlightTestRunner(scope, flight, SimulatedTelemetry(sim), stops, { true }, logger)

        suspend fun airborne(altitude: Double) {
            sim.start()
            sim.powerOn()
            flight.takeOff()
            sim.state.first { it.isFlying == true && (it.altitudeM ?: 0.0) >= 1.1 }
            // Wznoszenie na wysokość testu (jak pilot drążkiem przed testem).
            flight.startVirtualStick()
            while (sim.currentAltitude < altitude) {
                flight.setVelocityTarget(com.example.djicontroller.core.model.Setpoint(0.0, 0.0, 1.0, 0.0))
                delay(50)
            }
            flight.stopVirtualStick()
        }

        /** „Kamera”: położenie stacji względem drona z opóźnieniem 150 ms, ~12 Hz. */
        fun feedMarker(scope: CoroutineScope): Job = scope.launch {
            while (isActive) {
                val (n, e) = sim.currentPosition.northEastTo(sim.station)
                val h = Math.toRadians(sim.currentHeading)
                val now = clock.monotonicMs()
                vision.publish(
                    MarkerObservation(
                        markerId = 16,
                        dxM = n * cos(h) + e * sin(h),
                        dyM = -n * sin(h) + e * cos(h),
                        dzM = sim.currentAltitude,
                        yawDeg = 0.0,
                        timestampMs = now - 150,
                        latencyMs = 150,
                    ),
                )
                delay(80)
            }
        }
    }

    @Test
    fun `R3 - zawis nad markerem z wiatrem utrzymuje srodek i wysokosc`() = runBlocking {
        val rig = Rig(this, station, StickAxes.ROLL_FORWARD)
        rig.airborne(3.0)
        // Start 1,5 m obok markera, dryf 0,3 m/s na wschód.
        rig.sim.windMps = 0.3
        rig.flight.startVirtualStick()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 1_500) {
            rig.flight.setVelocityTarget(com.example.djicontroller.core.model.Setpoint(1.0, 0.0, 0.0, 0.0))
            delay(50)
        }
        rig.flight.stopVirtualStick()

        val feed = rig.feedMarker(this)
        val test = MarkerHoverTest(
            rig.runner, rig.flight, rig.camera, rig.vision, SimulatedTelemetry(rig.sim), rig.logger, rig.clock,
            MarkerHoverTestConfig(settleMs = 6_000),
        )
        assertEquals(CommandResult.Success, test.start(3.0))
        delay(14_000)
        test.stop()
        val end = withTimeout(5_000) { test.state.first { it is HoverTestState.Finished } as HoverTestState.Finished }
        feed.cancel()
        val s = end.summary!!
        println("R3 demo: $s")
        val (n, e) = rig.sim.currentPosition.northEastTo(station)
        assertTrue("RMS ${s.rmsHorizontalErrorM}", s.rmsHorizontalErrorM < 0.25)
        assertTrue("min H ${s.minHeightM}", s.minHeightM > 2.8)
        assertTrue("końcowe położenie ${hypot(n, e)}", hypot(n, e) < 0.4)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `test osi rozpoznaje poprawne i zamienione mapowanie`() = runBlocking {
        for ((axes, expected) in listOf(StickAxes.ROLL_FORWARD to AxisVerdict.OK, StickAxes.PITCH_FORWARD to AxisVerdict.SWAPPED)) {
            val rig = Rig(this, station, axes)
            rig.airborne(2.0)
            val check = StickAxisCheck(rig.runner, rig.flight, SimulatedTelemetry(rig.sim), rig.logger, rig.clock)
            assertEquals(CommandResult.Success, check.start())
            val end = withTimeout(20_000) { check.state.first { it is AxisCheckState.Finished } as AxisCheckState.Finished }
            println("Axis $axes: ${end.result}")
            assertEquals(expected, end.result?.verdict)
        }
        coroutineContext.cancelChildren()
    }
}
