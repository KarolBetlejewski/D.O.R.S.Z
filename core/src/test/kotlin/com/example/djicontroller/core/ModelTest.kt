package com.example.djicontroller.core

import com.example.djicontroller.core.control.ControlArbiter
import com.example.djicontroller.core.fake.FakeClock
import com.example.djicontroller.core.fake.RecordingLogger
import com.example.djicontroller.core.logging.JsonLines
import com.example.djicontroller.core.logging.JsonLinesLogger
import com.example.djicontroller.core.model.ControlOwner
import com.example.djicontroller.core.model.CycleStage
import com.example.djicontroller.core.model.CycleState
import com.example.djicontroller.core.model.GeoPoint
import com.example.djicontroller.core.model.PatrolRoute
import com.example.djicontroller.core.model.PatrolWaypoint
import com.example.djicontroller.core.model.ReturnReason
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.SetpointLimits
import com.example.djicontroller.core.model.isAirborneStage
import com.example.djicontroller.core.model.label
import com.example.djicontroller.core.model.stage
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class GeoPointTest {
    private val warsaw = GeoPoint(52.2297, 21.0122)

    @Test fun `one degree of latitude is about 111 km`() {
        val d = GeoPoint(52.0, 21.0).distanceTo(GeoPoint(53.0, 21.0))
        assertEquals(111_195.0, d, 50.0)
    }

    @Test fun `offset and distance are consistent`() {
        val p = warsaw.offsetBy(northM = 300.0, eastM = 400.0)
        assertEquals(500.0, warsaw.distanceTo(p), 0.5)
        val (n, e) = warsaw.northEastTo(p)
        assertEquals(300.0, n, 0.5)
        assertEquals(400.0, e, 0.5)
    }

    @Test fun `bearing east is 90 degrees`() {
        assertEquals(90.0, warsaw.bearingTo(warsaw.offsetBy(0.0, 100.0)), 0.1)
    }

    @Test fun `zero and NaN positions are invalid`() {
        assertFalse(GeoPoint(0.0, 0.0).isValid)
        assertFalse(GeoPoint(Double.NaN, 21.0).isValid)
        assertFalse(GeoPoint(91.0, 21.0).isValid)
        assertTrue(warsaw.isValid)
    }
}

class SetpointTest {
    private val limits = SetpointLimits(maxHorizontalMps = 2.0, maxVerticalMps = 1.0, maxYawRateDegS = 30.0)

    @Test fun `horizontal speed is limited as a vector`() {
        val s = Setpoint(3.0, 4.0, 0.0, 0.0).clamped(limits)
        assertEquals(2.0, hypot(s.forwardMps, s.rightMps), 1e-9)
        assertEquals(4.0 / 3.0, s.rightMps / s.forwardMps, 1e-9) // kierunek zachowany
    }

    @Test fun `vertical and yaw are clamped`() {
        val s = Setpoint(0.0, 0.0, -5.0, 90.0).clamped(limits)
        assertEquals(-1.0, s.upMps, 0.0)
        assertEquals(30.0, s.yawRateDegS, 0.0)
    }

    @Test fun `NaN or infinity gives hover`() {
        assertTrue(Setpoint(Double.NaN, 0.0, 0.0, 0.0).clamped(limits).isHover)
        assertTrue(Setpoint(0.0, 0.0, Double.POSITIVE_INFINITY, 0.0).clamped(limits).isHover)
    }
}

class PatrolRouteTest {
    private val station = GeoPoint(52.2297, 21.0122)
    private fun route(vararg points: Pair<Double, Double>) = PatrolRoute(
        id = "r1",
        name = "test",
        station = station,
        waypoints = points.map { (n, e) -> PatrolWaypoint(station.offsetBy(n, e), 30.0) },
    )

    @Test fun `mission appends station approach as last waypoint`() {
        val r = route(100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0)
        val wps = r.missionWaypoints()
        assertEquals(4, wps.size)
        assertEquals(station, wps.last().location)
        assertEquals(8.0, wps.last().altitudeM, 0.0)
    }

    @Test fun `plan from breakpoint maps indices back to the full route`() {
        val r = route(100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0)
        val plan = r.toMissionPlan("p", fromIndex = 1)
        assertEquals(3, plan.waypoints.size)
        assertEquals(1, plan.patrolIndexOf(0))
        assertEquals(2, plan.patrolIndexOf(1))
        assertNull(plan.patrolIndexOf(2)) // punkt nad stacją
    }

    @Test fun `length includes the leg back to the station`() {
        val r = route(100.0 to 0.0)
        assertEquals(200.0, r.lengthM() + station.distanceTo(r.waypoints[0].location), 1.0)
    }

    @Test fun `validation reports altitude, distance and empty route`() {
        assertTrue(route().validate().any { it.message.contains("żadnego punktu") })
        val bad = PatrolRoute(
            "r2", "x", station,
            listOf(
                PatrolWaypoint(station.offsetBy(50.0, 0.0), 150.0),
                PatrolWaypoint(station.offsetBy(5_000.0, 0.0), 30.0),
            ),
        )
        val issues = bad.validate()
        assertTrue(issues.any { it.waypointIndex == 0 && it.message.contains("Wysokość") })
        assertTrue(issues.any { it.waypointIndex == 1 && it.message.contains("od stacji") })
        assertTrue(route(100.0 to 0.0).validate().isEmpty())
    }

    @Test fun `return route climbs, flies and descends over the station`() {
        val from = station.offsetBy(400.0, 0.0)
        val r = PatrolRoute.returnRoute("ret", from, station, 30.0, 8.0, 6.0)
        val wps = r.missionWaypoints()
        assertEquals(listOf(30.0, 30.0, 8.0), wps.map { it.altitudeM })
        assertEquals(from, wps.first().location)
        assertEquals(station, wps.last().location)
    }
}

class CycleStateExtTest {
    @Test fun `holding reports the stage it interrupted`() {
        val s = CycleState.Holding(CycleState.Patrolling(2, 6))
        assertEquals(CycleStage.PATROL, s.stage)
        assertTrue(s.isAirborneStage)
        assertEquals("Patrol · punkt 3 z 6", CycleState.Patrolling(2, 6).label())
        assertFalse(CycleState.Charging.isAirborneStage)
        assertEquals(CycleStage.RETURN, CycleState.Returning(ReturnReason.OPERATOR, null).stage)
    }
}

class ControlArbiterTest {
    @Test fun `only one owner at a time`() {
        val a = ControlArbiter(RecordingLogger())
        assertTrue(a.tryAcquire(ControlOwner.MISSION))
        assertTrue(a.tryAcquire(ControlOwner.MISSION))
        assertFalse(a.tryAcquire(ControlOwner.VIRTUAL_STICK))
        assertFalse(a.release(ControlOwner.VIRTUAL_STICK))
        assertTrue(a.release(ControlOwner.MISSION))
        assertTrue(a.tryAcquire(ControlOwner.VIRTUAL_STICK))
        assertEquals(ControlOwner.VIRTUAL_STICK, a.owner.value)
    }

    @Test fun `aircraft sync overrides owner`() {
        val log = RecordingLogger()
        val a = ControlArbiter(log)
        a.tryAcquire(ControlOwner.MISSION)
        a.syncFromAircraft(ControlOwner.NONE, "misja zakończona")
        assertEquals(ControlOwner.NONE, a.owner.value)
        assertTrue("control_sync" in log.events())
    }
}

class JsonLinesTest {
    @Test fun `escapes strings and maps NaN to null`() {
        val s = JsonLines.encodeObject(linkedMapOf("a" to "x\"y\n", "b" to Double.NaN, "c" to listOf(1, true, null)))
        assertEquals("""{"a":"x\"y\n","b":null,"c":[1,true,null]}""", s)
    }

    @Test fun `logger writes one line with time, category and fields`() {
        val lines = mutableListOf<String>()
        val clock = FakeClock(500)
        val logger = JsonLinesLogger(clock, { lines += it }, minLevel = LogLevel.INFO)
        logger.log(LogCategory.STATE, LogLevel.INFO, "transition", mapOf("to" to "Ready", "ts" to "nadpisane"))
        logger.log(LogCategory.TELEMETRY, LogLevel.DEBUG, "drone", emptyMap())
        assertEquals(1, lines.size)
        assertEquals("""{"ts":${clock.wallMs()},"mono":500,"cat":"STATE","lvl":"INFO","ev":"transition","to":"Ready"}""", lines[0])
    }
}
