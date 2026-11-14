package com.example.djicontroller.core

import com.example.djicontroller.core.landing.DerivativeSource
import com.example.djicontroller.core.landing.HeightSource
import com.example.djicontroller.core.landing.LandingController
import com.example.djicontroller.core.landing.LandingControllerConfig
import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import com.example.djicontroller.core.model.Velocity3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class LandingControllerTest {

    /** Dron stoi w miejscu, kurs 0 — prędkość z telemetrii znana (D = 0). */
    private val still = DroneState(velocity = Velocity3D(0.0, 0.0, 0.0), headingDeg = 0.0)
    private val config = LandingControllerConfig()

    private fun obs(dx: Double, dy: Double, dz: Double, yaw: Double = 0.0, t: Long = 1_000, id: Int = 16) =
        MarkerObservation(id, dx, dy, dz, yaw, t)

    @Test
    fun `poza stozkiem vz = 0, w stozku opadanie`() {
        val c = LandingController()
        // h = 4 m: tolerancja 0,12 + 0,08·4 = 0,44 m.
        val inside = c.update(obs(0.3, 0.2, 4.0), still, 1_000)
        assertTrue(inside.withinCone)
        assertTrue(inside.descentAllowed)
        assertTrue("w stożku opada", inside.setpoint.upMps < 0)
        assertEquals(-config.maxDescentSpeedMps(4.0), inside.setpoint.upMps, 1e-9)

        c.reset()
        val outside = c.update(obs(0.4, 0.25, 4.0), still, 1_000) // 0,47 m > 0,44 m
        assertFalse(outside.withinCone)
        assertEquals("poza stożkiem tylko centrowanie", 0.0, outside.setpoint.upMps, 0.0)
    }

    @Test
    fun `sam dx albo sam dy poza tolerancja blokuje opadanie`() {
        val c = LandingController()
        val tol = config.maxHorizontalErrorM(2.0)
        assertEquals(0.0, c.update(obs(tol + 0.01, 0.0, 2.0), still, 1_000).setpoint.upMps, 0.0)
        c.reset()
        assertEquals(0.0, c.update(obs(0.0, -(tol + 0.01), 2.0), still, 1_000).setpoint.upMps, 0.0)
        c.reset()
        assertTrue(c.update(obs(tol * 0.5, -tol * 0.5, 2.0), still, 1_000).setpoint.upMps < 0)
    }

    @Test
    fun `stozek i predkosci maleja razem z wysokoscia`() {
        assertEquals(0.12 + 0.08 * 8, config.maxHorizontalErrorM(8.0), 1e-9)
        assertEquals("przy przejściu do L4 (0,5 m)", 0.16, config.maxHorizontalErrorM(0.5), 1e-9)
        assertTrue(config.maxHorizontalErrorM(0.5) < config.maxHorizontalErrorM(2.0))
        assertTrue(config.maxHorizontalErrorM(2.0) < config.maxHorizontalErrorM(8.0))
        assertTrue(config.maxHorizontalSpeedMps(0.5) < config.maxHorizontalSpeedMps(4.0))
        assertTrue(config.maxDescentSpeedMps(0.5) < config.maxDescentSpeedMps(4.0))

        // Ten sam (wycentrowany) dron: nisko opada wolniej niż wysoko.
        val c = LandingController()
        val high = c.update(obs(0.0, 0.0, 6.0), still, 1_000).setpoint.upMps
        c.reset()
        val low = c.update(obs(0.0, 0.0, 1.0), still, 1_000).setpoint.upMps
        assertTrue("wysoko szybciej w dół", high < low)
        assertTrue(low < 0)

        // Duży błąd: nasycenie prędkości poziomej zależne od wysokości, kierunek zachowany (3:4).
        c.reset()
        val sat = c.update(obs(3.0, 4.0, 0.8), still, 1_000).setpoint
        assertEquals(config.maxHorizontalSpeedMps(0.8), hypot(sat.forwardMps, sat.rightMps), 1e-9)
        assertEquals(4.0 / 3.0, sat.rightMps / sat.forwardMps, 1e-9)
    }

    @Test
    fun `znaki osi - dron leci do markera, nie od niego`() {
        val c = LandingController()
        assertTrue("marker z przodu → do przodu", c.update(obs(1.0, 0.0, 5.0), still, 1_000).setpoint.forwardMps > 0)
        c.reset()
        assertTrue("marker z tyłu → do tyłu", c.update(obs(-1.0, 0.0, 5.0), still, 1_000).setpoint.forwardMps < 0)
        c.reset()
        assertTrue("marker z prawej → w prawo", c.update(obs(0.0, 1.0, 5.0), still, 1_000).setpoint.rightMps > 0)
        c.reset()
        assertTrue("marker z lewej → w lewo", c.update(obs(0.0, -1.0, 5.0), still, 1_000).setpoint.rightMps < 0)
    }

    @Test
    fun `blad kursu blokuje opadanie i obraca drona`() {
        val c = LandingController()
        val skewed = c.update(obs(0.0, 0.0, 3.0, yaw = 25.0), still, 1_000)
        assertFalse(skewed.yawAligned)
        assertFalse(skewed.descentAllowed)
        assertEquals(0.0, skewed.setpoint.upMps, 0.0)
        assertTrue("obrót w prawo", skewed.setpoint.yawRateDegS > 0)

        c.reset()
        assertTrue(c.update(obs(0.0, 0.0, 3.0, yaw = 5.0), still, 1_000).descentAllowed)

        val noAlign = LandingController(LandingControllerConfig(alignYaw = false))
        val cmd = noAlign.update(obs(0.0, 0.0, 3.0, yaw = 40.0), still, 1_000)
        assertTrue("bez wyrównywania kurs nie blokuje", cmd.descentAllowed)
        assertEquals(0.0, cmd.setpoint.yawRateDegS, 0.0)
    }

    @Test
    fun `czlon D z predkosci z telemetrii tlumi ruch`() {
        val c = LandingController(LandingControllerConfig(kp = 0.5, kd = 0.2, compensateLatency = false))
        val moving = DroneState(velocity = Velocity3D(0.5, 0.0, 0.0), headingDeg = 0.0)
        val cmd = c.update(obs(1.0, 0.0, 8.0), moving, 1_000)
        assertEquals(DerivativeSource.TELEMETRY, cmd.derivativeSource)
        assertEquals(0.5 * 1.0 - 0.2 * 0.5, cmd.setpoint.forwardMps, 1e-9)
    }

    @Test
    fun `pochodna z klatek liczona tylko dla nowej klatki`() {
        val cfg = LandingControllerConfig(kp = 0.5, kd = 1.0, derivativeFilterAlpha = 1.0, compensateLatency = false)
        val c = LandingController(cfg)
        val noVelocity = DroneState() // brak KeyAircraftVelocity
        val first = c.update(obs(1.0, 0.0, 8.0, t = 1_000), noVelocity, 1_000)
        assertEquals(DerivativeSource.NONE, first.derivativeSource)
        assertEquals(0.5, first.setpoint.forwardMps, 1e-9)

        val second = c.update(obs(0.9, 0.0, 8.0, t = 1_100), noVelocity, 1_100)
        assertEquals(DerivativeSource.FRAMES, second.derivativeSource)
        assertEquals(0.5 * 0.9 + 1.0 * (-1.0), second.setpoint.forwardMps, 1e-9)

        // Ta sama klatka powtórzona w pętli 20 Hz: bez dzielenia przez zero, ten sam wynik.
        val repeated = c.update(obs(0.9, 0.0, 8.0, t = 1_100), noVelocity, 1_150)
        assertEquals(second.setpoint, repeated.setpoint)
        assertTrue(repeated.setpoint.forwardMps.isFinite())
    }

    @Test
    fun `zmiana markera zeruje pochodna - bez szarpniecia`() {
        val cfg = LandingControllerConfig(kp = 0.5, kd = 1.0, derivativeFilterAlpha = 1.0, compensateLatency = false)
        val c = LandingController(cfg)
        val noVelocity = DroneState()
        c.update(obs(0.30, 0.0, 1.3, t = 1_000, id = 16), noVelocity, 1_000)
        c.update(obs(0.25, 0.0, 1.25, t = 1_100, id = 16), noVelocity, 1_100)
        // Mały marker daje nieco inną pozę (0,15 zamiast ~0,2): bez resetu D dałoby skok −1 m/s.
        val switched = c.update(obs(0.15, 0.0, 1.2, t = 1_200, id = 3), noVelocity, 1_200)
        assertTrue(switched.markerSwitched)
        assertEquals(DerivativeSource.NONE, switched.derivativeSource)
        assertEquals(0.5 * 0.15, switched.setpoint.forwardMps, 1e-9)
        val next = c.update(obs(0.14, 0.0, 1.15, t = 1_300, id = 3), noVelocity, 1_300)
        assertFalse(next.markerSwitched)
        assertEquals(DerivativeSource.FRAMES, next.derivativeSource)
        assertTrue("po zmianie pochodna mała", abs(next.setpoint.forwardMps - 0.5 * 0.14) < 0.11)
    }

    @Test
    fun `kompensacja opoznienia obrazu predkoscia drona`() {
        val c = LandingController(LandingControllerConfig(kd = 0.0))
        val moving = DroneState(velocity = Velocity3D(0.5, 0.0, 0.0), headingDeg = 0.0)
        // Klatka sprzed 300 ms, dron leci 0,5 m/s do przodu → marker jest już 0,15 m bliżej.
        val cmd = c.update(obs(1.0, 0.0, 6.0, t = 1_000), moving, 1_300)
        assertEquals(0.85, cmd.errorForwardM, 1e-9)
    }

    @Test
    fun `wysokosc - ToF, potem marker, potem barometr`() {
        val c = LandingController()
        val tof = DroneState(ultrasonicHeightM = 1.0, altitudeM = 1.4, velocity = Velocity3D(0.0, 0.0, 0.0), headingDeg = 0.0)
        val cmd = c.update(obs(0.25, 0.0, 3.0), tof, 1_000) // tol(1 m) = 0,20 m, tol(3 m) = 0,36 m
        assertEquals(HeightSource.ULTRASONIC, cmd.heightSource)
        assertEquals(config.maxHorizontalErrorM(1.0), cmd.toleranceM, 1e-9)
        assertFalse("ToF zaostrza stożek", cmd.descentAllowed)

        val tooHigh = tof.copy(ultrasonicHeightM = 5.0)
        assertEquals(HeightSource.MARKER, c.estimateHeight(obs(0.0, 0.0, 4.6), tooHigh)?.source)
        assertEquals(HeightSource.ALTITUDE, c.estimateHeight(null, tooHigh)?.source)
        assertEquals(1.4, c.estimateHeight(null, tooHigh)!!.heightM, 1e-9)
        assertNull(c.estimateHeight(null, DroneState()))
    }

    @Test
    fun `dane niefizyczne daja zawis`() {
        val c = LandingController()
        val cmd = c.update(obs(Double.NaN, 0.0, 3.0), still, 1_000)
        assertEquals(Setpoint.HOVER, cmd.setpoint)
        assertFalse(cmd.descentAllowed)
    }
}
