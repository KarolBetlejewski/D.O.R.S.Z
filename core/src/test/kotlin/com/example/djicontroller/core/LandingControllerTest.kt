package com.example.djicontroller.core

import com.example.djicontroller.core.landing.LandingController
import com.example.djicontroller.core.landing.LandingControllerConfig
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Velocity3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class LandingControllerTest {

    private val still = DroneState(velocity = Velocity3D(0.0, 0.0, 0.0), headingDeg = 0.0)
    private val config = LandingControllerConfig()

    private fun obs(dx: Double, dy: Double, dz: Double, yaw: Double = 0.0, t: Long = 1_000) =
        MarkerObservation(3, dx, dy, dz, yaw, t)

    @Test
    fun `stozek zweza sie z wysokoscia`() {
        assertEquals(0.08 + 0.06 * 8, config.toleranceM(8.0), 1e-9)
        assertEquals(0.08 + 0.06 * 0.5, config.toleranceM(0.5), 1e-9)
        assertTrue(config.toleranceM(0.5) < config.toleranceM(2.0))
        assertTrue(config.toleranceM(2.0) < config.toleranceM(8.0))
    }

    @Test
    fun `limity predkosci maleja z wysokoscia`() {
        assertTrue(config.maxHorizontalMps(0.5) < config.maxHorizontalMps(8.0))
        assertTrue(config.maxDescentMps(0.5) < config.maxDescentMps(8.0))
        assertEquals(config.maxHorizontalMps, config.maxHorizontalMps(50.0), 1e-9)
        assertEquals(config.minDescentMps, config.maxDescentMps(0.0), 1e-9)
    }

    @Test
    fun `opadanie tylko wewnatrz stozka`() {
        val c = LandingController()
        // 4 m: tolerancja 0,32 m.
        val inside = c.update(obs(0.2, 0.1, 4.0), still)
        assertTrue(inside.descentAllowed)
        assertEquals(-config.maxDescentMps(4.0), inside.setpoint.upMps, 1e-9)

        c.reset()
        val outside = c.update(obs(0.4, 0.2, 4.0), still)
        assertFalse(outside.descentAllowed)
        assertTrue("poza stożkiem bez opadania", outside.setpoint.upMps >= 0.0)
    }

    @Test
    fun `ten sam blad pozwala opadac wysoko, a nisko juz nie`() {
        val c = LandingController()
        assertTrue(c.update(obs(0.25, 0.0, 6.0), still).descentAllowed)
        c.reset()
        assertFalse(c.update(obs(0.25, 0.0, 1.0), still).descentAllowed)
    }

    @Test
    fun `nisko i daleko od srodka - lekkie wznoszenie`() {
        val c = LandingController()
        val cmd = c.update(obs(0.6, 0.0, 1.0), still) // tol(1 m) = 0,14 m; 0,6 > 3·tol
        assertEquals(config.correctiveClimbMps, cmd.setpoint.upMps, 1e-9)
    }

    @Test
    fun `nasycenie predkosci poziomej zalezne od wysokosci`() {
        val c = LandingController()
        val low = c.update(obs(3.0, 4.0, 0.8), still)
        assertEquals(config.maxHorizontalMps(0.8), hypot(low.setpoint.forwardMps, low.setpoint.rightMps), 1e-9)
        assertEquals(4.0 / 3.0, low.setpoint.rightMps / low.setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `czlon D z predkosci z telemetrii tlumi ruch`() {
        val c = LandingController(LandingControllerConfig(kp = 0.5, kd = 0.2, compensateLatency = false))
        val moving = DroneState(velocity = Velocity3D(0.5, 0.0, 0.0), headingDeg = 0.0)
        val cmd = c.update(obs(1.0, 0.0, 8.0), moving)
        assertEquals(0.5 - 0.1, cmd.setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `bez telemetrii pochodna z filtrowanej roznicy obserwacji`() {
        val c = LandingController(LandingControllerConfig(kp = 0.0001, kd = 1.0, ki = 0.0, derivativeFilterAlpha = 1.0))
        c.update(obs(1.0, 0.0, 8.0, t = 1_000), DroneState())
        val cmd = c.update(obs(0.8, 0.0, 8.0, t = 1_200), DroneState())
        assertEquals(-1.0, cmd.setpoint.forwardMps, 1e-3)
    }

    @Test
    fun `czlon I usuwa staly uchyb od znoszenia i jest ograniczony`() {
        val c = LandingController(LandingControllerConfig(kp = 0.5, kd = 0.0, ki = 0.5, maxIntegralMps = 0.2, compensateLatency = false))
        val first = c.update(obs(0.3, 0.0, 6.0, t = 0), still).setpoint.forwardMps
        var last = first
        for (i in 1..40) last = c.update(obs(0.3, 0.0, 6.0, t = i * 100L), still).setpoint.forwardMps
        assertTrue(last > first)
        assertEquals(0.5 * 0.3 + 0.2, last, 1e-9)
        c.reset()
        assertEquals(first, c.update(obs(0.3, 0.0, 6.0, t = 0), still).setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `czlon I nie calkuje daleko od srodka`() {
        val c = LandingController(LandingControllerConfig(kp = 0.1, kd = 0.0, ki = 1.0, integralZoneM = 1.0, compensateLatency = false))
        for (i in 0..20) c.update(obs(3.0, 0.0, 8.0, t = i * 100L), still)
        assertEquals(0.3, c.update(obs(3.0, 0.0, 8.0, t = 2_200), still).setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `nisko opadanie czeka na wyrownanie kursu`() {
        val c = LandingController()
        val skewed = c.update(obs(0.0, 0.0, 1.0, yaw = 25.0), still)
        assertFalse(skewed.descentAllowed)
        assertTrue(skewed.setpoint.yawRateDegS > 0)
        c.reset()
        assertTrue(c.update(obs(0.0, 0.0, 1.0, yaw = 5.0), still).descentAllowed)
        c.reset()
        // Wysoko kurs nie blokuje opadania.
        assertTrue(c.update(obs(0.0, 0.0, 6.0, yaw = 25.0), still).descentAllowed)
    }

    @Test
    fun `wysokosc z ToF zaostrza stozek`() {
        val c = LandingController()
        // Marker mówi 3 m, ale planer podaje ToF 1 m → tolerancja liczona dla 1 m.
        val cmd = c.update(obs(0.2, 0.0, 3.0), still, heightM = 1.0)
        assertEquals(config.toleranceM(1.0), cmd.toleranceM, 1e-9)
        assertFalse(cmd.descentAllowed)
    }
}
