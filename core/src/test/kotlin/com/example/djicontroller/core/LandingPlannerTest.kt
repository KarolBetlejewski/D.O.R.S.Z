package com.example.djicontroller.core

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.fake.FakeCamera
import com.example.djicontroller.core.fake.FakeClock
import com.example.djicontroller.core.fake.FakeFlightControl
import com.example.djicontroller.core.fake.FakeMission
import com.example.djicontroller.core.fake.FakeStation
import com.example.djicontroller.core.fake.FakeTelemetry
import com.example.djicontroller.core.fake.FakeVision
import com.example.djicontroller.core.fake.RecordingLogger
import com.example.djicontroller.core.landing.LandingOutcome
import com.example.djicontroller.core.landing.LandingPlanner
import com.example.djicontroller.core.landing.LandingPlannerConfig
import com.example.djicontroller.core.landing.LandingStep
import com.example.djicontroller.core.model.ControlLoss
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.port.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Procedura L1–L5 na czasie wirtualnym ([LandingWorld] jest Tickerem planera): cały lot nad stacją
 * liczy się w milisekundach, wynik jest deterministyczny.
 */
class LandingPlannerTest {

    private class Setup(val scope: CoroutineScope, config: LandingPlannerConfig = LandingPlannerConfig()) {
        val clock = FakeClock()
        val logger = RecordingLogger()
        val arbiter = ControlArbiter(logger)
        val telemetry = FakeTelemetry(DroneState(connected = true, isFlying = true, areMotorsOn = true, homeLocation = STATION))
        val flight = FakeFlightControl(arbiter)
        val mission = FakeMission(arbiter)
        val camera = FakeCamera()
        val vision = FakeVision()
        val station = FakeStation(clock).apply { publish(dronePresent = false) }
        val world = LandingWorld(clock, telemetry, flight, camera, vision, station).apply {
            placeAt(north = 0.6, east = -0.4, alt = 8.0, headingDeg = 30.0)
        }
        val planner = LandingPlanner(
            flight, mission, camera, vision, telemetry, station, { STATION }, logger, clock,
            ticker = world, config = config,
        )

        fun phases(): List<String> = logger.entries.filter { it.event == "landing_phase" }.map { it.fields["to"] as String }
        fun events(): List<String> = logger.entries.map { it.event }
        fun indexOf(event: String): Int = events().indexOf(event)
        fun has(event: String): Boolean = event in events()
    }

    private fun landingTest(config: LandingPlannerConfig = LandingPlannerConfig(), block: suspend Setup.() -> Unit) =
        runBlocking { Setup(this, config).block() }

    private val allSteps = listOf("L1_APPROACH", "L2_SEARCH", "L3_DESCENT", "L4_TOUCHDOWN", "L5_VERIFY")

    // ------------------------------------------------------------------ pełna ścieżka

    @Test fun `pelna sciezka L1-L5 konczy sie wyladowaniem na srodku plyty`() = landingTest {
        val outcome = planner.execute(1)

        assertEquals(LandingOutcome.Landed, outcome)
        assertEquals(allSteps, phases())
        // Z2: Virtual Stick wyłączony PRZED auto-lądowaniem; planer potwierdza sam (R5).
        val calls = flight.calls
        assertTrue(calls.indexOf("stopVirtualStick") in 0 until calls.indexOf("startAutoLanding"))
        assertEquals(false, flight.lastAutoConfirm)
        assertTrue("potwierdzenie ochrony lądowania", "confirmLanding" in calls)
        assertTrue(indexOf("landing_confirmation_needed") < indexOf("landing_confirm"))
        assertTrue(indexOf("landing_confirm") < indexOf("landing_motors_off"))
        // Wylądował na środku, z kursem płyty.
        assertTrue("odległość ${world.touchdownOffsetM}", world.touchdownOffsetM!! < 0.15)
        assertTrue("kurs ${world.touchdownHeadingDeg}", abs(world.touchdownHeadingDeg!!) <= 10.0)
        // Każde opadanie wyliczone przez PD było wewnątrz stożka.
        val pd = logger.entries.filter { it.event == "landing_pd" }
        assertTrue(pd.size > 50)
        for (e in pd) {
            if ((e.fields["up"] as Double) < 0) assertTrue(e.toString(), (e.fields["err"] as Double) <= (e.fields["tol"] as Double))
        }
        assertTrue("zmiana markera 16 → 3 w trakcie opadania", pd.any { it.fields["switch"] == true })
        // Sprzątanie.
        assertTrue(vision.users.value.isEmpty())
        assertNull(planner.phase.value)
        assertEquals(ControlOwner.NONE, arbiter.owner.value)
    }

    @Test fun `orkiestrator - powrot, L1-L5, ShuttingDown i dalej do Ready`() {
        var world: LandingWorld? = null
        var planner: LandingPlanner? = null
        rigTest(landingFactory = {
            val w = LandingWorld(clock, telemetry, flight, camera, vision, station).also { it.syncFromTelemetry = true; world = it }
            LandingPlanner(flight, mission, camera, vision, telemetry, station, { STATION }, logger, clock, ticker = w)
                .also { planner = it }
        }) {
            orchestrator.startCycle(testRoute())
            awaitState(10_000) { it == CycleState.Ready }
            val t = transitions()
            val landing = t.indexOf("Landing(attempt=1)")
            assertTrue(t.toString(), landing >= 0)
            assertEquals("ShuttingDown", t[landing + 1])
            assertEquals(allSteps, logger.entries.filter { it.event == "landing_phase" }.map { it.fields["to"] })
            assertTrue(world!!.touchdownOffsetM!! < 0.15)
            assertNull(planner!!.phase.value)
        }
    }

    // ------------------------------------------------------------------ L1

    @Test fun `L1 zatrzymuje misje WPML przed wlaczeniem Virtual Stick`() = landingTest {
        mission.upload(testRoute().toMissionPlan("p1"))
        mission.start("p1")
        assertEquals(ControlOwner.MISSION, arbiter.owner.value)

        assertEquals(LandingOutcome.Landed, planner.execute(1))
        assertTrue("stop" in mission.calls)
        assertTrue(indexOf("landing_mission_stop") in 0 until indexOf("landing_vs_start"))
    }

    @Test fun `L1 blokuje przy zajetej plycie do jej zwolnienia`() = landingTest {
        station.publish(padClear = false, dronePresent = false)
        var outcome: LandingOutcome? = null
        val job = scope.launch { outcome = planner.execute(1) }
        val start = clock.monotonicMs()
        while (clock.monotonicMs() - start < 20_000) yield()

        assertEquals(LandingStep.L1_APPROACH, planner.phase.value?.step)
        assertFalse("bez Virtual Stick w L1", "startVirtualStick" in flight.calls)
        assertTrue(planner.alarm.value!!.contains("płyta lądowiska zajęta"))
        assertTrue("alarm powtarzany", events().count { it == "landing_station_blocked" } >= 3)
        assertEquals(8.0, world.alt, 0.01) // zawis

        station.publish(padClear = true, dronePresent = false)
        job.join()
        assertEquals(LandingOutcome.Landed, outcome)
        assertTrue(has("landing_station_ok"))
        assertNull(planner.alarm.value)
    }

    @Test fun `L1 blokuje bez lacza ze stacja`() = landingTest {
        station.disconnect()
        var outcome: LandingOutcome? = null
        val job = scope.launch { outcome = planner.execute(1) }
        val start = clock.monotonicMs()
        while (clock.monotonicMs() - start < 10_000) yield()

        assertEquals(LandingStep.L1_APPROACH, planner.phase.value?.step)
        assertTrue(planner.alarm.value!!.contains("brak łącza ze stacją"))
        assertFalse("startVirtualStick" in flight.calls)

        station.publish(dronePresent = false)
        job.join()
        assertEquals(LandingOutcome.Landed, outcome)
    }

    @Test fun `L1 odmawia zbyt daleko od stacji`() = landingTest {
        world.placeAt(north = 25.0, east = 0.0, alt = 8.0)
        val outcome = planner.execute(1)
        assertTrue(outcome.toString(), outcome is LandingOutcome.Aborted)
        assertFalse("startVirtualStick" in flight.calls)
    }

    // ------------------------------------------------------------------ L2

    @Test fun `L2 bez markera - zejscie na 4,5 m, wzniesienie o 2 m, potem nieudana proba`() = landingTest {
        world.markerVisible = { false }
        val start = clock.monotonicMs()
        val outcome = planner.execute(1)

        assertTrue(outcome.toString(), outcome is LandingOutcome.MarkerLost)
        // 8 m (powrót misji) jest ponad zakresem pewnej detekcji → zejście na 4,5 m, szukanie, wzniesienie o 2 m.
        val adjust = logger.entries.first { it.event == "landing_search_altitude" }
        assertEquals(4.5, adjust.fields["to"] as Double, 1e-9)
        val climb = logger.entries.single { it.event == "landing_search_climb" }
        assertEquals(minOf((climb.fields["from"] as Double) + 2.0, 6.5), climb.fields["to"] as Double, 1e-9)
        assertTrue(has("landing_marker_not_found"))
        assertTrue("nie wyżej niż sufit poszukiwań", world.maxAltitudeSeen <= 8.05)
        assertTrue("2 × 5 s szukania", clock.monotonicMs() - start >= 10_000)
        assertEquals(listOf("L1_APPROACH", "L2_SEARCH"), phases())
        assertEquals(ControlOwner.NONE, arbiter.owner.value)
        assertTrue(vision.users.value.isEmpty())
    }

    @Test fun `L2 marker znaleziony po wzniesieniu - ladowanie dalej`() = landingTest {
        var seen = false
        // Marker widoczny dopiero po wzniesieniu w L2 (np. GPS znosi płytę poza kadr na 6 m).
        world.markerVisible = { if (alt >= 6.0 && has("landing_search_climb")) seen = true; seen }
        assertEquals(LandingOutcome.Landed, planner.execute(1))
        assertTrue(has("landing_search_climb"))
        assertTrue(camera.calls.contains("gimbalDown"))
    }

    @Test fun `L2 gimbal bez potwierdzenia -90 przerywa`() = landingTest {
        camera.gimbalPitchDeg.value = 0.0
        val stuck = object : com.example.djicontroller.core.port.CameraPort by camera {
            override suspend fun pointGimbalDown() = com.example.djicontroller.core.model.CommandResult.Success
        }
        val p = LandingPlanner(flight, mission, stuck, vision, telemetry, station, { STATION }, logger, clock, ticker = world)
        val outcome = p.execute(1)
        assertTrue(outcome.toString(), outcome is LandingOutcome.Aborted && outcome.reason.contains("Gimbal"))
        assertFalse("startVirtualStick" in flight.calls)
    }

    // ------------------------------------------------------------------ L3

    @Test fun `L3 krotki zanik markera - natychmiast HOVER, potem ladowanie dalej`() = landingTest {
        var hideFrom: Long? = null
        world.markerVisible = {
            if (alt < 4.0 && hideFrom == null) hideFrom = clock.monotonicMs()
            val h = hideFrom
            h == null || clock.monotonicMs() - h > 600
        }
        assertEquals(LandingOutcome.Landed, planner.execute(1))
        assertFalse(has("landing_marker_lost"))
        assertTrue(has("landing_marker_dropout"))
        assertTrue(has("landing_marker_back"))
        val h = hideFrom!!
        val gap = world.samples.filter { it.t in (h + 200)..(h + 550) }
        assertTrue(gap.isNotEmpty())
        assertTrue("w czasie zaniku tylko HOVER: $gap", gap.all { it.setpoint == Setpoint.HOVER })
    }

    @Test fun `L3 dluga utrata - zawis, wzniesienie i nieudana proba`() = landingTest {
        var lostAt: Long? = null
        var lostAlt = 0.0
        world.markerVisible = {
            if (alt < 3.0 && lostAt == null) { lostAt = clock.monotonicMs(); lostAlt = alt }
            lostAt == null
        }
        val outcome = planner.execute(1)

        assertTrue(outcome.toString(), outcome is LandingOutcome.MarkerLost)
        assertTrue(has("landing_marker_lost"))
        assertTrue(has("landing_recovery_climb"))
        val after = world.samples.filter { it.t > lostAt!! }
        assertTrue("po utracie bez opadania", after.filter { it.owner == ControlOwner.VIRTUAL_STICK }.all { it.setpoint.upMps >= 0.0 })
        assertTrue("wzniesienie ~2 m: utrata na $lostAlt, max ${after.maxOf { it.alt }}", after.maxOf { it.alt } >= lostAlt + 1.5)
        assertEquals(ControlOwner.NONE, arbiter.owner.value)
    }

    @Test fun `L3 trzecia utrata - bez wznoszenia, tylko zawis`() = landingTest {
        var lostAt: Long? = null
        var lostAlt = 0.0
        world.markerVisible = {
            if (alt < 4.0 && lostAt == null) { lostAt = clock.monotonicMs(); lostAlt = alt }
            lostAt == null
        }
        val outcome = planner.execute(3)

        assertTrue(outcome is LandingOutcome.MarkerLost)
        assertFalse(has("landing_recovery_climb"))
        assertTrue(has("landing_attempts_exhausted"))
        assertTrue(world.samples.filter { it.t > lostAt!! }.all { it.alt <= lostAlt + 0.1 })
    }

    @Test fun `orkiestrator - trzy utraty markera daja Failure`() {
        rigTest(landingFactory = {
            val w = LandingWorld(clock, telemetry, flight, camera, vision, station).also {
                it.syncFromTelemetry = true
                it.markerVisible = { alt >= 4.0 }
            }
            LandingPlanner(flight, mission, camera, vision, telemetry, station, { STATION }, logger, clock, ticker = w)
        }) {
            orchestrator.startCycle(testRoute())
            val failure = awaitState(10_000) { it is CycleState.Failure } as CycleState.Failure
            assertTrue(failure.cause, failure.cause.contains("Marker utracony 3 razy"))
            val t = transitions()
            assertTrue(t.toString(), t.containsAll(listOf("Landing(attempt=1)", "Landing(attempt=2)", "Landing(attempt=3)")))
            assertEquals(3, logger.entries.count { it.event == "landing_marker_lost" })
            assertEquals("tylko 2 wzniesienia", 2, logger.entries.count { it.event == "landing_recovery_climb" })
            assertEquals(ControlOwner.NONE, arbiter.owner.value)
        }
    }

    @Test fun `L3 krotkie zawieszenie obrazu - zawis bez utraty markera`() = landingTest {
        var frozenAt: Long? = null
        world.visionFrozen = {
            if (alt < 3.0 && frozenAt == null) frozenAt = clock.monotonicMs()
            frozenAt?.let { clock.monotonicMs() - it < 1_500 } == true
        }
        assertEquals(LandingOutcome.Landed, planner.execute(1))
        assertFalse(has("landing_marker_lost"))
        val back = logger.entries.first { it.event == "landing_marker_back" }
        assertTrue(back.toString(), (back.fields["stalledMs"] as Long) > 0)
    }

    @Test fun `L3 dlugie zawieszenie obrazu - utrata`() = landingTest {
        var frozenAt: Long? = null
        world.visionFrozen = {
            if (alt < 3.0 && frozenAt == null) frozenAt = clock.monotonicMs()
            frozenAt != null
        }
        val outcome = planner.execute(1)
        assertTrue(outcome.toString(), outcome is LandingOutcome.MarkerLost && outcome.reason.contains("Brak nowych klatek"))
    }

    @Test fun `utrata sterowania Virtual Stick przerywa ladowanie`() = landingTest {
        var emitted = false
        world.onStep = {
            if (!emitted && planner.phase.value?.step == LandingStep.L3_DESCENT && alt < 6.0) {
                emitted = true
                flight.controlLost.tryEmit(ControlLoss("RC_PAUSE_STOP", takenOverByRc = true))
            }
        }
        val outcome = planner.execute(1)
        assertTrue(outcome.toString(), outcome is LandingOutcome.Aborted && outcome.reason.contains("Utrata sterowania"))
        assertEquals(ControlOwner.NONE, arbiter.owner.value)
        assertFalse("startAutoLanding" in flight.calls)
    }

    // ------------------------------------------------------------------ odporność (warunki z emulatora)

    /**
     * Warunki z logów i zrzutów z emulatora: duży marker niewykrywany powyżej 5,6 m (na 6 m w zawisie
     * potrafi nie wykryć się wcale), dodatkowo 25 % klatek bez pozy, szum, fizyka 3× szybsza niż zegar (demo), opóźnienie i błąd GPS.
     */
    private fun Setup.emulatorConditions(seed: Long) {
        val r = java.util.Random(seed)
        world.timeScale = 3.0
        world.tauS = 0.35
        world.frameIntervalMs = 83
        world.latencyMs = 60
        // Z emulatora: na 6 m w nieruchomym zawisie marker potrafi nie wykryć się wcale, na 8 m poza odrzucana.
        world.detectionCeilingM = 5.6
        world.random.setSeed(seed)
        world.poseRejected = { random.nextDouble() < 0.25 }
        world.noiseFraction = 0.004
        world.yawNoiseDeg = 2.0
        world.gpsBiasNorthM = r.nextDouble() * 3 - 1.5
        world.gpsBiasEastM = r.nextDouble() * 3 - 1.5
        world.placeAt(north = r.nextDouble() * 4 - 2, east = r.nextDouble() * 4 - 2, alt = 8.0, headingDeg = r.nextDouble() * 360 - 180)
    }

    @Test fun `warunki z emulatora - laduje w pierwszej probie`() {
        for (seed in 1L..12L) landingTest {
            emulatorConditions(seed)
            val outcome = planner.execute(1)
            assertEquals("seed $seed: ${logger.entries.filter { it.category == LogCategory.SAFETY }.map { it.event }}", LandingOutcome.Landed, outcome)
            assertTrue("seed $seed: odległość ${world.touchdownOffsetM}", world.touchdownOffsetM!! < 0.2)
            assertFalse("seed $seed", has("landing_marker_lost"))
        }
    }

    @Test fun `warunki z emulatora - pelny cykl bez ponownych prob`() {
        var world: LandingWorld? = null
        rigTest(landingFactory = {
            val w = LandingWorld(clock, telemetry, flight, camera, vision, station).also {
                it.syncFromTelemetry = true
                it.timeScale = 3.0
                it.tauS = 0.35
                it.detectionCeilingM = 5.6
                it.poseRejected = { random.nextDouble() < 0.25 }
                it.noiseFraction = 0.004
                world = it
            }
            LandingPlanner(flight, mission, camera, vision, telemetry, station, { STATION }, logger, clock, ticker = w)
        }) {
            orchestrator.startCycle(testRoute())
            awaitState(10_000) { it == CycleState.Ready }
            assertFalse(transitions().toString(), "Landing(attempt=2)" in transitions())
            assertTrue(world!!.touchdownOffsetM!! < 0.2)
        }
    }

    // ------------------------------------------------------------------ L4/L5

    @Test fun `L5 bez potwierdzenia stacji - przerwanie`() = landingTest {
        world.stationDetectsDrone = false
        val outcome = planner.execute(1)
        assertTrue(outcome.toString(), outcome is LandingOutcome.Aborted && outcome.reason.contains("Brak potwierdzenia"))
        assertEquals(LandingStep.L5_VERIFY.name, phases().last())
        assertFalse(world.motorsOn)
    }

    @Test fun `stary status stacji sprzed przyziemienia nie wystarcza`() = landingTest {
        station.publish(dronePresent = true) // np. status sprzed startu
        world.stationDetectsDrone = false     // po przyziemieniu stacja drona nie widzi
        val outcome = planner.execute(1)
        assertTrue(outcome.toString(), outcome is LandingOutcome.Aborted)
    }

    // ------------------------------------------------------------------ STOP

    @Test fun `STOP w L3 - natychmiast HOVER i oddanie Virtual Stick`() = landingTest {
        var outcome: LandingOutcome? = null
        lateinit var job: Job
        world.onStep = {
            if (planner.phase.value?.step == LandingStep.L3_DESCENT && alt < 6.0 && job.isActive) job.cancel()
        }
        job = scope.launch { outcome = planner.execute(1) }
        job.join()

        assertNull("anulowane", outcome)
        assertEquals("stopVirtualStick", flight.calls.last())
        assertEquals(Setpoint.HOVER, flight.setpoints.last())
        assertEquals(ControlOwner.NONE, arbiter.owner.value)
        assertTrue(vision.users.value.isEmpty())
        assertTrue(world.flying)
        // Po STOP opadanie wygasa z bezwładnością (τ ≈ 0,3 s), potem dron stoi w miejscu.
        val altAtStop = world.alt
        world.sleep(1_000)
        assertTrue("wyhamowanie ${altAtStop - world.alt} m", altAtStop - world.alt < 0.3)
        val settled = world.alt
        world.sleep(3_000)
        assertEquals("dron wisi", settled, world.alt, 0.01)
        assertTrue(has("landing_end"))
    }

    @Test fun `STOP w L4 - przerwanie auto-ladowania i zawis`() = landingTest {
        var outcome: LandingOutcome? = null
        lateinit var job: Job
        world.onStep = {
            if (planner.phase.value?.step == LandingStep.L4_TOUCHDOWN && alt < 0.45 && job.isActive) job.cancel()
        }
        job = scope.launch { outcome = planner.execute(1) }
        job.join()

        assertNull(outcome)
        assertTrue("cancelAutoLanding" in flight.calls)
        assertEquals(ControlOwner.NONE, arbiter.owner.value)
        assertTrue(world.flying)
        val altAtStop = world.alt
        world.sleep(1_000)
        assertTrue("wyhamowanie ${altAtStop - world.alt} m", altAtStop - world.alt < 0.2)
        val settled = world.alt
        world.sleep(3_000)
        assertEquals("dron wisi", settled, world.alt, 0.01)
        assertTrue(world.flying)
    }

    @Test fun `STOP z nadzoru w trakcie L3 - ZAWIS orkiestratora, wznowienie konczy cykl`() {
        var planner: LandingPlanner? = null
        rigTest(landingFactory = {
            val w = LandingWorld(clock, telemetry, flight, camera, vision, station).also { it.syncFromTelemetry = true }
            var stopped = false
            w.onStep = {
                if (!stopped && planner?.phase?.value?.step == LandingStep.L3_DESCENT && alt < 6.0) {
                    stopped = true
                    safety.stop("test")
                }
            }
            LandingPlanner(flight, mission, camera, vision, telemetry, station, { STATION }, logger, clock, ticker = w)
                .also { planner = it }
        }) {
            orchestrator.startCycle(testRoute())
            val holding = awaitState(10_000) { it is CycleState.Holding } as CycleState.Holding
            assertEquals(CycleState.Landing(1), holding.resumeTo)
            awaitCondition(what = "sprzątanie lądowania") { logger.entries.any { it.event == "landing_end" } }
            assertTrue("stopVirtualStick" in flight.calls)
            assertEquals(ControlOwner.NONE, arbiter.owner.value)
            assertFalse("startAutoLanding" in flight.calls)

            assertEquals(com.example.djicontroller.core.model.CommandResult.Success, orchestrator.resume())
            awaitState(10_000) { it == CycleState.Ready }
            assertNotNull(logger.entries.firstOrNull { it.event == "landing_verified" })
        }
    }
}
