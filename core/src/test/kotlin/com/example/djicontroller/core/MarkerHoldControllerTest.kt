package com.example.djicontroller.core

import com.example.djicontroller.core.control.MarkerHoldConfig
import com.example.djicontroller.core.control.MarkerHoldController
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Velocity3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MarkerHoldControllerTest {

    private val still = DroneState(velocity = Velocity3D(0.0, 0.0, 0.0), headingDeg = 0.0)

    private fun obs(dx: Double, dy: Double, dz: Double, t: Long = 1_000, yaw: Double = 0.0, latency: Long? = null) =
        MarkerObservation(markerId = 3, dxM = dx, dyM = dy, dzM = dz, yawDeg = yaw, timestampMs = t, latencyMs = latency)

    @Test
    fun `leci w strone markera proporcjonalnie do bledu`() {
        val c = MarkerHoldController(MarkerHoldConfig(kpHorizontal = 0.5, kdHorizontal = 0.0))
        val cmd = c.update(obs(dx = 0.4, dy = -0.2, dz = 3.0), still, targetHeightM = 3.0)
        assertEquals(0.2, cmd.setpoint.forwardMps, 1e-9)
        assertEquals(-0.1, cmd.setpoint.rightMps, 1e-9)
        assertEquals(0.0, cmd.setpoint.upMps, 1e-9)
    }

    @Test
    fun `nasycenie zachowuje kierunek`() {
        val c = MarkerHoldController(MarkerHoldConfig(kpHorizontal = 2.0, kdHorizontal = 0.0, maxHorizontalMps = 1.0))
        val cmd = c.update(obs(dx = 3.0, dy = 4.0, dz = 5.0), still, 5.0)
        assertEquals(1.0, hypot(cmd.setpoint.forwardMps, cmd.setpoint.rightMps), 1e-9)
        assertEquals(4.0 / 3.0, cmd.setpoint.rightMps / cmd.setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `nie opada gdy blad poziomy poza stozkiem`() {
        val config = MarkerHoldConfig(descentToleranceBaseM = 0.1, descentTolerancePerM = 0.05)
        val c = MarkerHoldController(config)
        // Na 4 m tolerancja 0,3 m; błąd 0,5 m → zakaz opadania mimo celu 2 m.
        val off = c.update(obs(dx = 0.5, dy = 0.0, dz = 4.0), still, targetHeightM = 2.0)
        assertFalse(off.descentAllowed)
        assertEquals(0.0, off.setpoint.upMps, 1e-9)

        c.reset()
        val centred = c.update(obs(dx = 0.05, dy = 0.0, dz = 4.0), still, targetHeightM = 2.0)
        assertTrue(centred.descentAllowed)
        assertTrue(centred.setpoint.upMps < 0)
        assertEquals(-config.maxDescentMps, centred.setpoint.upMps, 1e-9)
    }

    @Test
    fun `wznosi sie do celu nawet poza stozkiem`() {
        val c = MarkerHoldController()
        val cmd = c.update(obs(dx = 1.0, dy = 1.0, dz = 1.5), still, targetHeightM = 3.0)
        assertTrue(cmd.setpoint.upMps > 0)
    }

    @Test
    fun `stozek zweza sie z wysokoscia`() {
        val config = MarkerHoldConfig()
        assertTrue(config.descentToleranceM(1.0) < config.descentToleranceM(8.0))
    }

    @Test
    fun `czlon D tlumi ruch w strone markera`() {
        val c = MarkerHoldController(MarkerHoldConfig(kpHorizontal = 0.5, kdHorizontal = 0.2, compensateLatency = false))
        // Dron leci do przodu 1 m/s (kurs 0 = północ) — człon D odejmuje 0,2 m/s.
        val moving = DroneState(velocity = Velocity3D(1.0, 0.0, 0.0), headingDeg = 0.0)
        val cmd = c.update(obs(dx = 1.0, dy = 0.0, dz = 3.0), moving, 3.0)
        assertEquals(0.5 - 0.2, cmd.setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `predkosc z telemetrii obrocona do BODY`() {
        val c = MarkerHoldController(MarkerHoldConfig(kpHorizontal = 0.5, kdHorizontal = 0.2, compensateLatency = false))
        // Kurs 90° (wschód), dron leci na wschód = do przodu.
        val moving = DroneState(velocity = Velocity3D(0.0, 1.0, 0.0), headingDeg = 90.0)
        val cmd = c.update(obs(dx = 0.0, dy = 0.0, dz = 3.0), moving, 3.0)
        assertEquals(-0.2, cmd.setpoint.forwardMps, 1e-9)
        assertEquals(0.0, cmd.setpoint.rightMps, 1e-9)
    }

    @Test
    fun `kompensacja opoznienia przesuwa blad o droge drona`() {
        val c = MarkerHoldController(MarkerHoldConfig(kpHorizontal = 1.0, kdHorizontal = 0.0, horizontalDeadbandM = 0.0))
        val moving = DroneState(velocity = Velocity3D(0.5, 0.0, 0.0), headingDeg = 0.0)
        // Klatka sprzed 200 ms: dron przeleciał 0,1 m do przodu, więc błąd teraz = 0,3 m.
        val cmd = c.update(obs(dx = 0.4, dy = 0.0, dz = 3.0, latency = 200), moving, 3.0)
        assertEquals(0.3, cmd.setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `bez telemetrii pochodna z kolejnych obserwacji`() {
        val c = MarkerHoldController(MarkerHoldConfig(kpHorizontal = 0.0001, kdHorizontal = 1.0, kiHorizontal = 0.0, derivativeFilterAlpha = 1.0, horizontalDeadbandM = 0.0))
        val noVelocity = DroneState()
        c.update(obs(dx = 1.0, dy = 0.0, dz = 3.0, t = 1_000), noVelocity, 3.0)
        val cmd = c.update(obs(dx = 0.9, dy = 0.0, dz = 3.0, t = 1_100), noVelocity, 3.0)
        // de/dt = −1 m/s → człon D = −1 m/s.
        assertEquals(-1.0, cmd.setpoint.forwardMps, 1e-3)
    }

    @Test
    fun `obrot do markera tylko blisko srodka i z ograniczeniem`() {
        val c = MarkerHoldController(MarkerHoldConfig(alignYaw = true, kpYaw = 1.0, maxYawRateDegS = 10.0))
        assertEquals(10.0, c.update(obs(dx = 0.1, dy = 0.0, dz = 3.0, yaw = 40.0), still, 3.0).setpoint.yawRateDegS, 1e-9)
        c.reset()
        assertEquals(0.0, c.update(obs(dx = 2.0, dy = 0.0, dz = 3.0, yaw = 40.0), still, 3.0).setpoint.yawRateDegS, 1e-9)
    }

    @Test
    fun `czlon I usuwa staly uchyb i ma ograniczenie`() {
        val c = MarkerHoldController(MarkerHoldConfig(kpHorizontal = 0.5, kdHorizontal = 0.0, kiHorizontal = 0.5, maxIntegralMps = 0.2))
        val first = c.update(obs(dx = 0.4, dy = 0.0, dz = 3.0, t = 0), still, 3.0).setpoint.forwardMps
        var last = first
        for (i in 1..50) last = c.update(obs(dx = 0.4, dy = 0.0, dz = 3.0, t = i * 100L), still, 3.0).setpoint.forwardMps
        assertTrue(last > first)
        assertEquals(0.5 * 0.4 + 0.2, last, 1e-9) // całka nasycona na 0,2 m/s
        c.reset()
        assertEquals(first, c.update(obs(dx = 0.4, dy = 0.0, dz = 3.0, t = 0), still, 3.0).setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `czlon I nie calkuje daleko od markera`() {
        val c = MarkerHoldController(MarkerHoldConfig(kpHorizontal = 0.1, kdHorizontal = 0.0, kiHorizontal = 1.0, integralZoneM = 1.0, maxHorizontalMps = 5.0))
        for (i in 0..20) c.update(obs(dx = 3.0, dy = 0.0, dz = 5.0, t = i * 100L), still, 5.0)
        assertEquals(0.3, c.update(obs(dx = 3.0, dy = 0.0, dz = 5.0, t = 2_200), still, 5.0).setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `strefa martwa wycisza szum`() {
        val c = MarkerHoldController(MarkerHoldConfig(kdHorizontal = 0.0, horizontalDeadbandM = 0.05))
        val cmd = c.update(obs(dx = 0.02, dy = -0.02, dz = 3.0), still, 3.0)
        assertTrue(cmd.setpoint.isHover)
    }
}
