package com.example.djicontroller.core.landing

import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Nastawy regulatora lądowania (sekcja 5.5). Wszystkie limity zależą od wysokości `h` nad markerem —
 * im niżej, tym ciaśniejszy stożek i wolniejszy ruch:
 *
 * - tolerancja pozioma (stożek): `tol(h) = coneBaseM + coneSlope · h`
 * - maks. prędkość pozioma: `vH(h) = clamp(minHorizontalMps + horizontalPerM · h, …, maxHorizontalMps)`
 * - maks. prędkość opadania: `vZ(h) = clamp(minDescentMps + descentPerM · h, …, maxDescentMps)`
 */
data class LandingControllerConfig(
    /** Wzmocnienie P w poziomie (m błędu → m/s). */
    val kp: Double = 0.6,
    /** Wzmocnienie D w poziomie (m/s zmiany błędu → m/s). */
    val kd: Double = 0.2,
    /**
     * Człon I (ograniczony): usuwa stały uchyb od znoszenia (wiatr, błąd prędkości GPS). Sam PD przy stałym
     * znoszeniu ustala się obok środka — wysoko to nie przeszkadza, ale nisko stożek ma kilkanaście cm
     * i dron przestaje schodzić. Całkowanie tylko blisko środka ([integralZoneM]); 0 = czysty PD.
     */
    val ki: Double = 0.0,
    val integralZoneM: Double = 1.0,
    val maxIntegralMps: Double = 0.5,
    /** Filtr pochodnej z obserwacji (gdy brak prędkości z telemetrii), 0..1. */
    val derivativeFilterAlpha: Double = 0.35,
    /** Kompensacja opóźnienia obrazu prędkością drona (`KeyAircraftVelocity`). */
    val compensateLatency: Boolean = true,
    val maxCompensatedLatencyMs: Long = 400,

    val coneBaseM: Double = 0.08,
    val coneSlope: Double = 0.06,

    val minHorizontalMps: Double = 0.2,
    val horizontalPerM: Double = 0.12,
    val maxHorizontalMps: Double = 1.0,

    val minDescentMps: Double = 0.15,
    val descentPerM: Double = 0.07,
    val maxDescentMps: Double = 0.6,

    /** Gdy błąd poziomy przekroczy tyle tolerancji, dron lekko się wznosi zamiast tylko wisieć. */
    val climbAboveToleranceFactor: Double = 3.0,
    val correctiveClimbMps: Double = 0.2,
    /** Wznoszenie korekcyjne tylko poniżej tej wysokości (wyżej marker i tak jest w kadrze), m. */
    val correctiveClimbBelowM: Double = 2.0,

    /** Obracanie do osi markera — potrzebne, żeby dron usiadł na płycie we właściwej orientacji. */
    val alignYaw: Boolean = true,
    val kpYaw: Double = 0.8,
    val maxYawRateDegS: Double = 20.0,
    /** Obrót dopiero, gdy błąd poziomy ≤ tyle tolerancji. */
    val yawWithinToleranceFactor: Double = 2.0,
    /** Opadanie wstrzymane, gdy błąd kursu > tyle stopni (poniżej [yawGateBelowM]). */
    val maxYawErrorForDescentDeg: Double = 10.0,
    val yawGateBelowM: Double = 2.0,
) {
    init {
        require(kp > 0) { "kp musi być dodatnie" }
        require(coneBaseM > 0 && coneSlope >= 0) { "Stożek: podstawa > 0, nachylenie ≥ 0" }
        require(minHorizontalMps in 0.0..maxHorizontalMps) { "Prędkość pozioma: min ≤ max" }
        require(minDescentMps in 0.0..maxDescentMps) { "Prędkość opadania: min ≤ max" }
        require(derivativeFilterAlpha in 0.0..1.0) { "Filtr pochodnej: 0..1" }
        require(ki >= 0 && maxIntegralMps >= 0) { "Człon I: ki ≥ 0, limit ≥ 0" }
    }

    fun toleranceM(heightM: Double): Double = coneBaseM + coneSlope * max(0.0, heightM)

    fun maxHorizontalMps(heightM: Double): Double =
        (minHorizontalMps + horizontalPerM * max(0.0, heightM)).coerceIn(minHorizontalMps, maxHorizontalMps)

    fun maxDescentMps(heightM: Double): Double =
        (minDescentMps + descentPerM * max(0.0, heightM)).coerceIn(minDescentMps, maxDescentMps)
}

/** Wynik kroku regulatora — punkt zadany i wielkości do logu (R3/R5) i UI. */
data class LandingCommand(
    val setpoint: Setpoint,
    val heightM: Double,
    val horizontalErrorM: Double,
    val toleranceM: Double,
    val descentAllowed: Boolean,
)

/**
 * Regulator PD lądowania precyzyjnego (F3, sekcja 5.5). Czysty Kotlin, bez wątków.
 *
 * Poziomo: `v = Kp·e + Kd·de/dt + Ki·∫e` (PD z małym, ograniczonym członem I przeciw znoszeniu),
 * gdzie `e` — położenie środka lądowiska względem drona (BODY),
 * a `de/dt` = −prędkość drona z telemetrii (gdy brak — filtrowana różnica obserwacji). Wynik ograniczony
 * do `vH(h)` z zachowaniem kierunku.
 *
 * Pionowo — zasada bezpieczeństwa: opadanie (`up < 0`) TYLKO, gdy `hypot(dx, dy) ≤ tol(h)`
 * (a nisko także, gdy dron jest obrócony zgodnie z płytą). Poza stożkiem dron trzyma wysokość,
 * a nisko i daleko od środka — lekko się wznosi.
 */
class LandingController(val config: LandingControllerConfig = LandingControllerConfig()) {

    private var previous: MarkerObservation? = null
    private var rateForward = 0.0
    private var rateRight = 0.0
    private var integralForward = 0.0
    private var integralRight = 0.0

    fun reset() {
        previous = null
        rateForward = 0.0
        rateRight = 0.0
        integralForward = 0.0
        integralRight = 0.0
    }

    /**
     * @param heightM wysokość nad płytą do stożka i limitów; domyślnie `dz` z markera.
     *   Planer może podać tu wartość z `KeyUltrasonicHeight`, gdy jest wiarygodniejsza nisko nad płytą.
     */
    fun update(observation: MarkerObservation, drone: DroneState, heightM: Double = observation.dzM): LandingCommand {
        val c = config
        val body = drone.bodyVelocity()

        var ef = observation.dxM
        var er = observation.dyM
        val latency = observation.latencyMs
        if (c.compensateLatency && body != null && latency != null && latency > 0) {
            val t = latency.coerceAtMost(c.maxCompensatedLatencyMs) / 1000.0
            ef -= body.forwardMps * t
            er -= body.rightMps * t
        }

        val prevObs = previous
        val dtObs = prevObs?.let { (observation.timestampMs - it.timestampMs) / 1000.0 }?.takeIf { it in 0.01..0.5 }

        val dF: Double
        val dR: Double
        if (body != null) {
            dF = -body.forwardMps
            dR = -body.rightMps
        } else {
            val prev = previous
            val dt = prev?.let { (observation.timestampMs - it.timestampMs) / 1000.0 }
            if (prev != null && dt != null && dt in 0.01..1.0) {
                val a = c.derivativeFilterAlpha
                rateForward = a * (observation.dxM - prev.dxM) / dt + (1 - a) * rateForward
                rateRight = a * (observation.dyM - prev.dyM) / dt + (1 - a) * rateRight
            }
            dF = rateForward
            dR = rateRight
        }
        previous = observation

        val h = max(0.0, heightM)
        val tolerance = c.toleranceM(h)
        val error = hypot(ef, er)

        if (c.ki > 0 && dtObs != null && error <= c.integralZoneM) {
            integralForward += c.ki * ef * dtObs
            integralRight += c.ki * er * dtObs
            val mag = hypot(integralForward, integralRight)
            if (mag > c.maxIntegralMps) {
                integralForward *= c.maxIntegralMps / mag
                integralRight *= c.maxIntegralMps / mag
            }
        }

        var vf = c.kp * ef + c.kd * dF + integralForward
        var vr = c.kp * er + c.kd * dR + integralRight
        val vMax = c.maxHorizontalMps(h)
        val v = hypot(vf, vr)
        if (v > vMax) {
            vf *= vMax / v
            vr *= vMax / v
        }

        val yawAligned = h > c.yawGateBelowM || !c.alignYaw || abs(observation.yawDeg) <= c.maxYawErrorForDescentDeg
        val descentAllowed = error <= tolerance && yawAligned
        val up = when {
            descentAllowed -> -c.maxDescentMps(h)
            error > tolerance * c.climbAboveToleranceFactor && h < c.correctiveClimbBelowM -> c.correctiveClimbMps
            else -> 0.0
        }

        val yawRate = if (c.alignYaw && error <= tolerance * c.yawWithinToleranceFactor) {
            (c.kpYaw * observation.yawDeg).coerceIn(-c.maxYawRateDegS, c.maxYawRateDegS)
        } else {
            0.0
        }

        return LandingCommand(
            setpoint = Setpoint(vf, vr, up, yawRate),
            heightM = h,
            horizontalErrorM = error,
            toleranceM = tolerance,
            descentAllowed = descentAllowed,
        )
    }
}
