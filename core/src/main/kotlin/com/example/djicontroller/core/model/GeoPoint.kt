package com.example.djicontroller.core.model

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pozycja WGS84 w stopniach dziesiętnych.
 *
 * MSDK zwraca `0.0 / 0.0` albo NaN, gdy dron nie ma fixa GPS, dlatego każdy
 * konsument powinien sprawdzać [isValid] przed użyciem pozycji.
 */
data class GeoPoint(val latitude: Double, val longitude: Double) {

    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

    /** Odległość po powierzchni Ziemi (wzór haversine), w metrach. */
    fun distanceTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Azymut początkowy do [other] w stopniach 0..360 (0 = północ, zgodnie z ruchem wskazówek zegara). */
    fun bearingTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Punkt przesunięty o [northM] / [eastM] metrów (przybliżenie lokalnie płaskie,
     * dokładne do kilku centymetrów na dystansach rzędu kilometra).
     */
    fun offsetBy(northM: Double, eastM: Double): GeoPoint {
        val dLat = northM / EARTH_RADIUS_M
        val dLon = eastM / (EARTH_RADIUS_M * cos(Math.toRadians(latitude)))
        return GeoPoint(latitude + Math.toDegrees(dLat), longitude + Math.toDegrees(dLon))
    }

    /** Przesunięcie do [other] w metrach jako para (północ, wschód) — do regulatorów i logów. */
    fun northEastTo(other: GeoPoint): Pair<Double, Double> {
        val d = distanceTo(other)
        val b = Math.toRadians(bearingTo(other))
        return Pair(d * cos(b), d * sin(b))
    }

    companion object {
        /** Średni promień Ziemi (IUGG), m. */
        const val EARTH_RADIUS_M = 6_371_008.8
    }
}

/** Prędkość w układzie NED (północ, wschód, w dół), m/s — tak raportuje ją `KeyAircraftVelocity`. */
data class Velocity3D(val northMps: Double, val eastMps: Double, val downMps: Double) {
    val horizontalMps: Double get() = hypot(northMps, eastMps)
}
