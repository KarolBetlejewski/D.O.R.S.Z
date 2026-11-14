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
import kotlin.math.sin
import kotlin.math.tan

/**
 * Detekcja markera liczona geometrycznie z pozycji symulatora — dla testów JVM, w których
 * nie ma natywnego OpenCV. W aplikacji demo te same klatki przechodzą przez prawdziwy detektor z :vision.
 *
 * Publikuje wynik co [intervalMs], tylko gdy ktoś korzysta z detekcji (jak `VisionPipeline`):
 * marker widoczny przy gimbalu w dół, w polu widzenia kamery i do 14 m wysokości.
 */
fun CoroutineScope.feedMarkerFromSimulator(
    sim: DroneSimulator,
    vision: FakeVision,
    clock: Clock,
    intervalMs: Long = 80,
): Job = launch {
    while (isActive) {
        delay(intervalMs)
        if (vision.users.value.isEmpty()) continue
        val now = clock.monotonicMs()
        vision.publish(observe(sim, now), frameTimestampMs = now)
    }
}

private fun observe(sim: DroneSimulator, now: Long): MarkerObservation? {
    if (sim.gimbalPitchDeg > -85.0 || sim.markerHidden) return null
    val altitude = sim.currentAltitude
    if (altitude < 0.15 || altitude > 14.0) return null
    val (n, e) = sim.currentPosition.northEastTo(sim.station) // od drona do środka płyty
    if (hypot(n, e) > altitude * tan(Math.toRadians(FOV_HALF_DEG))) return null
    val h = Math.toRadians(sim.currentHeading)
    var yaw = (0.0 - sim.currentHeading) % 360.0
    if (yaw > 180) yaw -= 360
    if (yaw <= -180) yaw += 360
    return MarkerObservation(
        markerId = if (altitude < 1.2) 3 else 16,
        dxM = n * cos(h) + e * sin(h),
        dyM = -n * sin(h) + e * cos(h),
        dzM = altitude,
        yawDeg = yaw,
        timestampMs = now,
        latencyMs = 0,
    )
}

private const val FOV_HALF_DEG = 35.0
