package com.example.djicontroller.core

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.fake.FakeCamera
import com.example.djicontroller.core.fake.FakeFlightControl
import com.example.djicontroller.core.fake.FakeMission
import com.example.djicontroller.core.fake.FakeStation
import com.example.djicontroller.core.fake.FakeTelemetry
import com.example.djicontroller.core.fake.FakeVision
import com.example.djicontroller.core.fake.RecordingLogger
import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.landing.LandingPlanner
import com.example.djicontroller.core.landing.LandingPlannerConfig
import com.example.djicontroller.core.model.CommandResult
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.Velocity3D
import com.example.djicontroller.core.port.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Planer L1–L5 na prostym modelu kinematycznym: dron wykonuje ostatni punkt zadany Virtual Stick
 * (czas przyspieszony ×[timeScale]), kamera „widzi” stację, gdy jest w polu widzenia.
 */
class LandingPlannerTest {

    private class World(val scope: CoroutineScope, startNorth: Double = 0.6, startEast: Double = -0.4, startAlt: Double = 8.0) {
        val logger = RecordingLogger()
        val arbiter = ControlArbiter(logger)
        val flight = FakeFlightControl(arbiter)
        val mission = FakeMission(arbiter)
        val camera = FakeCamera()
        val vision = FakeVision()
        val station = FakeStation(SystemClock)
        val telemetry = FakeTelemetry(
            READY_ON_STATION.copy(
                isFlying = true,
                areMotorsOn = true,
                location = STATION.offsetBy(startNorth, startEast),
                altitudeM = startAlt,
            ),
        )

        @Volatile var north = startNorth
        @Volatile var east = startEast
        @Volatile var alt = startAlt
        @Volatile var heading = 30.0
        @Volatile var markerHidden = false

        /** Zamrożony potok wizyjny (jak przycięcie emulatora z logu 2026-11-09): brak nowych wyników. */
        @Volatile var visionFrozen = false

        /** Stałe znoszenie na wschód w trakcie Virtual Stick (wiatr niedokładnie kompensowany przez drona), m/s. */
        @Volatile var driftEastMps = 0.0
        @Volatile var landingProtection = true
        @Volatile private var autoLanding = false
        @Volatile private var confirmed = false
        @Volatile private var landed = false
        val timeScale = 4.0

        /** Stacja widzi drona na płycie, gdy wylądował w promieniu tylu metrów. */
        var padRadiusM = 0.3

        fun planner(config: LandingPlannerConfig = fastConfig()) = LandingPlanner(
            flight, mission, camera, vision, telemetry, station, { STATION }, logger, SystemClock, config,
        )

        fun start(): Job = scope.launch {
            station.publish(dronePresent = false)
            flight.onStartAutoLanding = { autoLanding = true; confirmed = false; CommandResult.Success }
            flight.onConfirmLanding = { confirmed = true }
            var lastObsAt = 0L
            var lastStationAt = 0L
            while (isActive) {
                val dt = 0.02 * timeScale
                var vn = 0.0
                var ve = 0.0
                var vu = 0.0
                if (arbiter.owner.value == ControlOwner.VIRTUAL_STICK) {
                    val sp = flight.setpoints.lastOrNull() ?: Setpoint.HOVER
                    val h = Math.toRadians(heading)
                    vn = sp.forwardMps * cos(h) - sp.rightMps * sin(h)
                    ve = sp.forwardMps * sin(h) + sp.rightMps * cos(h)
                    vu = sp.upMps
                    heading += sp.yawRateDegS * dt
                } else if (autoLanding && alt > 0) {
                    val protect = landingProtection && !confirmed && alt <= 0.5
                    telemetry.update { it.copy(isLandingConfirmationNeeded = protect) }
                    vu = if (protect) 0.0 else -1.0
                }
                north += vn * dt
                east += ve * dt
                if (arbiter.owner.value == ControlOwner.VIRTUAL_STICK) east += driftEastMps * dt
                alt = max(0.0, alt + vu * dt)
                if (autoLanding && alt <= 0.0) {
                    autoLanding = false
                    landed = true
                    arbiter.release(ControlOwner.AUTO_LANDING)
                    telemetry.update { it.copy(isFlying = false, areMotorsOn = false, isLandingConfirmationNeeded = false) }
                }
                telemetry.update {
                    it.copy(
                        location = STATION.offsetBy(north, east),
                        altitudeM = alt,
                        ultrasonicHeightM = if (alt < 5) alt else null,
                        headingDeg = heading,
                        velocity = Velocity3D(vn, ve, -vu),
                    )
                }
                // Stacja wysyła status co 200 ms (jak heartbeat F5).
                if (SystemClock.monotonicMs() - lastStationAt >= 200) {
                    lastStationAt = SystemClock.monotonicMs()
                    station.publish(dronePresent = landed && hypot(north, east) <= padRadiusM)
                }
                // Kamera ~12 Hz, opóźnienie 60 ms, pole widzenia ±(0,7·h).
                val now = SystemClock.monotonicMs()
                if (now - lastObsAt >= 80 && !visionFrozen) {
                    lastObsAt = now
                    val visible = !markerHidden && alt in 0.3..12.0 && hypot(north, east) <= max(0.3, alt * 0.7) &&
                        (camera.gimbalPitchDeg.value ?: 0.0) <= -85.0
                    vision.publish(if (visible) observation(now) else null)
                }
                delay(20)
            }
        }

        private fun observation(now: Long): MarkerObservation {
            val h = Math.toRadians(heading)
            val n = -north
            val e = -east
            return MarkerObservation(
                markerId = 3,
                dxM = n * cos(h) + e * sin(h),
                dyM = -n * sin(h) + e * cos(h),
                dzM = alt,
                yawDeg = -heading,
                timestampMs = now - 60,
                latencyMs = 60,
            )
        }
    }

    private fun landingTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        try {
            block()
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    @Test
    fun `pelne ladowanie L1-L5 z ochrona ladowania`() = landingTest {
        val w = World(this)
        w.start()
        val outcome = w.planner().execute(1)
        assertEquals(LandingOutcome.Landed, outcome)
        val f = w.flight
        assertTrue("gimbalDown" in w.camera.calls)
        assertTrue("startVirtualStick" in f.calls)
        assertTrue("stopVirtualStick" in f.calls)
        assertTrue(f.calls.indexOf("stopVirtualStick") < f.calls.indexOf("startAutoLanding"))
        // KeyConfirmLanding wysyła planer, nie adapter (R5).
        assertEquals(false, f.lastAutoConfirm)
        assertTrue("confirmLanding" in f.calls)
        assertTrue("landing_confirmation_needed" in w.logger.events())
        assertTrue(hypot(w.north, w.east) <= 0.15)
        assertTrue(w.vision.users.value.isEmpty())
        val phases = w.logger.events().filter { it == "landing_phase" }
        assertTrue(phases.size >= 5)
    }

    @Test
    fun `opadanie tylko w stozku - nigdy nie schodzi przy duzym bledzie`() = landingTest {
        val w = World(this, startNorth = 2.0, startEast = 1.5, startAlt = 8.0)
        w.start()
        assertEquals(LandingOutcome.Landed, w.planner().execute(1))
        // Każdy krok L3 z opadaniem miał błąd ≤ tolerancji (log landing_step).
        val steps = w.logger.entries.filter { it.event == "landing_step" }
        assertTrue(steps.isNotEmpty())
        for (s in steps) {
            val up = s.fields["up"] as Double
            if (up < 0) assertTrue("opadanie poza stożkiem: $s", (s.fields["err"] as Double) <= (s.fields["tol"] as Double) + 1e-9)
        }
    }

    @Test
    fun `stale znoszenie - czlon I sprowadza drona do srodka i laduje`() = landingTest {
        val w = World(this)
        w.driftEastMps = 0.15
        w.start()
        val planner = LandingPlanner(
            w.flight, w.mission, w.camera, w.vision, w.telemetry, w.station, { STATION }, w.logger, SystemClock,
            fastConfig(), com.example.djicontroller.core.landing.LandingControllerConfig(ki = 0.3),
        )
        assertEquals(LandingOutcome.Landed, planner.execute(1))
        assertTrue("odległość od środka ${hypot(w.north, w.east)}", hypot(w.north, w.east) <= 0.2)
    }

    @Test
    fun `brak markera w L2 - wzniesienie i MarkerLost`() = landingTest {
        val w = World(this, startAlt = 8.0)
        w.markerHidden = true
        w.start()
        val outcome = w.planner(fastConfig().copy(maxSearchClimbs = 1)).execute(1)
        assertTrue(outcome is LandingOutcome.MarkerLost)
        assertTrue("landing_search_climb" in w.logger.events())
        assertTrue(w.alt > 9.5)
        assertTrue(w.flight.setpoints.any { it.upMps > 0 })
        assertEquals(ControlOwner.NONE, w.arbiter.owner.value)
    }

    @Test
    fun `utrata markera w L3 - natychmiast bez opadania, potem wzniesienie i MarkerLost`() = landingTest {
        val w = World(this)
        w.start()
        val planner = w.planner()
        val run = async { planner.execute(1) }
        // Czekamy, aż dron zejdzie poniżej 5 m w L3, potem zasłaniamy marker.
        while (w.alt > 5.0) delay(20)
        w.markerHidden = true
        val hiddenAt = w.flight.setpoints.size
        val altAtLoss = w.alt
        val outcome = run.await()
        assertTrue(outcome is LandingOutcome.MarkerLost)
        // Po utracie żadnego opadania, a na końcu wzniesienie.
        val after = w.flight.setpoints.drop(hiddenAt + 2)
        assertTrue(after.none { it.upMps < 0 })
        assertTrue(w.alt > altAtLoss + 1.0)
        assertTrue("landing_marker_lost" in w.logger.events())
    }

    @Test
    fun `krotka utrata markera - zawis i dalej laduje`() = landingTest {
        val w = World(this)
        w.start()
        val run = async { w.planner().execute(1) }
        while (w.alt > 4.0) delay(20)
        w.markerHidden = true
        delay(150) // < markerLostTimeoutMs
        w.markerHidden = false
        assertEquals(LandingOutcome.Landed, run.await())
    }

    @Test
    fun `przestoj detekcji w L3 - zawis bez opadania i dalej laduje (log 2026-11-09)`() = landingTest {
        val w = World(this)
        w.start()
        val run = async { w.planner().execute(1) }
        while (w.alt > 3.3) delay(20)
        w.visionFrozen = true
        val frozenAt = w.flight.setpoints.size
        delay(2_500) // > markerLostTimeoutMs, < visionStallTimeoutMs
        // Pierwsze ~300 ms: ostatnia klatka jeszcze świeża (≤ blindDescentMaxAgeMs + okres kamery).
        val duringStall = w.flight.setpoints.drop(frozenAt + 7)
        w.visionFrozen = false
        assertEquals(LandingOutcome.Landed, run.await())
        assertTrue("w przestoju bez opadania", duringStall.none { it.upMps < 0 })
        assertFalse("landing_marker_lost" in w.logger.events())
    }

    @Test
    fun `L5 - stacja nie widzi drona na plycie`() = landingTest {
        val w = World(this)
        w.padRadiusM = -1.0 // stacja nigdy nie potwierdza
        w.start()
        val outcome = w.planner().execute(1)
        assertTrue(outcome is LandingOutcome.Aborted)
        assertTrue((outcome as LandingOutcome.Aborted).reason.contains("L5"))
    }

    @Test
    fun `L1 zatrzymuje misje WPML przed Virtual Stick`() = landingTest {
        val w = World(this)
        w.mission.progress.value = w.mission.progress.value.copy(status = com.example.djicontroller.core.model.MissionStatus.EXECUTING)
        w.start()
        w.planner().execute(1)
        assertTrue("stop" in w.mission.calls)
        assertTrue(w.mission.calls.indexOf("stop") >= 0 && w.flight.calls.contains("startVirtualStick"))
    }

    @Test
    fun `L1 - dron daleko od stacji przerywa ladowanie`() = landingTest {
        val w = World(this, startNorth = 20.0, startEast = 0.0)
        w.start()
        val outcome = w.planner(fastConfig().copy(approachTimeoutMs = 300)).execute(1)
        assertTrue(outcome is LandingOutcome.Aborted)
        assertFalse("startVirtualStick" in w.flight.calls)
    }

    @Test
    fun `trzy nieudane proby konczy AWARIA - orkiestrator z planerem`() = runBlocking {
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val rig = TestRig(scope, customLanding = {
            LandingPlanner(
                flight, mission, camera, vision, telemetry, station, { STATION }, logger, SystemClock,
                // Atrapa misji kończy patrol nad stacją na 30 m i nie zmienia wysokości — bez korekty L1.
                fastConfig().copy(maxSearchClimbs = 0, approachMaxAltitudeM = 40.0),
            )
        })
        rig.orchestrator.start()
        try {
            rig.orchestrator.startCycle(testRoute())
            val failure = rig.awaitState(timeoutMs = 20_000) { it is CycleState.Failure } as CycleState.Failure
            assertTrue(failure.cause, failure.cause.contains("3 razy"))
            assertEquals(3, rig.logger.entries.count { it.event == "landing_start" })
            assertEquals(ControlOwner.NONE, rig.arbiter.owner.value)
        } finally {
            job.cancel()
        }
    }

    companion object {
        fun fastConfig() = LandingPlannerConfig(
            approachTimeoutMs = 3_000,
            gimbalTimeoutMs = 500,
            searchTimeoutMs = 600,
            searchClimbM = 2.0,
            maxSearchClimbs = 2,
            markerLostTimeoutMs = 400,
            lostClimbM = 2.0,
            verticalAdjustMps = 1.5,
            touchdownTimeoutMs = 5_000,
            confirmRetryMs = 500,
            stationConfirmTimeoutMs = 1_000,
        )
    }
}
