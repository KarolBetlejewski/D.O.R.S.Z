package com.example.djicontroller.demo

import com.example.djicontroller.core.fake.FakeVision
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Wizja dla testów JVM bez OpenCV: położenie stacji liczone z geometrii symulatora i podawane
 * do [FakeVision] tak, jak robi to potok :vision (~12 Hz, opóźnienie 80 ms). Marker „widać”,
 * gdy gimbal patrzy w dół, stacja jest w polu widzenia (FOV ≈ 74°) i marker nie jest zasłonięty.
 */
fun CoroutineScope.feedMarkerFromSimulator(sim: DroneSimulator, vision: FakeVision, clock: Clock): Job = launch {
    while (isActive) {
        vision.publish(observe(sim, clock))
        delay(80)
    }
}

private fun observe(sim: DroneSimulator, clock: Clock): MarkerObservation? {
    if (sim.markerHidden || !sim.poweredOn || sim.gimbalPitchDeg > -85.0) return null
    val altitude = max(sim.currentAltitude, 0.05)
    val (n, e) = sim.currentPosition.northEastTo(sim.station)
    if (hypot(n, e) > altitude * 0.75 || altitude > 12.0 || altitude < 0.3) return null
    val h = Math.toRadians(sim.currentHeading)
    val now = clock.monotonicMs()
    return MarkerObservation(
        markerId = if (altitude > 2.0) 16 else 3,
        dxM = n * cos(h) + e * sin(h),
        dyM = -n * sin(h) + e * cos(h),
        dzM = altitude,
        // Płyta „góra” = północ: błąd kursu to −kurs drona (znormalizowany do ±180°).
        yawDeg = ((-sim.currentHeading + 540.0) % 360.0) - 180.0,
        timestampMs = now - 80,
        latencyMs = 80,
    )
}
