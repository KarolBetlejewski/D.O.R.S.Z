package com.example.djicontroller.core.control

import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.MarkerObservation
import com.example.djicontroller.core.model.Setpoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Nastawy regulatora utrzymania drona nad markerem.
 *
 * Wartości domyślne są zachowawcze: dron Mini 4 Pro śledzi zadaną prędkość z opóźnieniem ok. 0,3–0,5 s,
 * a obraz dociera z opóźnieniem 150–250 ms — przy większym wzmocnieniu P pojawiają się oscylacje.
 */
data class MarkerHoldConfig(
    /** Wzmocnienie P w poziomie: m błędu → m/s. */
    val kpHorizontal: Double = 0.6,
    /** Wzmocnienie D w poziomie: m/s zmiany błędu → m/s (tłumienie). */
    val kdHorizontal: Double = 0.15,
    /** Filtr dolnoprzepustowy pochodnej błędu (gdy brak prędkości z telemetrii), 0..1 (1 = bez filtra). */
    val derivativeFilterAlpha: Double = 0.35,
    val maxHorizontalMps: Double = 1.0,
    /**
     * Mały człon I w poziomie: usuwa stały uchyb, gdy dron nie trzyma idealnie zadanej prędkości
     * (podmuchy, błąd prędkości GPS). Całkowanie tylko blisko środka ([integralZoneM]),
     * z ograniczeniem [maxIntegralMps]; 0 = czysty PD.
     */
    val kiHorizontal: Double = 0.1,
    val integralZoneM: Double = 1.0,
    val maxIntegralMps: Double = 0.3,
    /** Strefa martwa w poziomie, m — poniżej nie poprawiamy (szum detekcji). */
    val horizontalDeadbandM: Double = 0.03,
    /** Kompensacja opóźnienia obrazu: przewidujemy błąd na „teraz” z prędkości drona. */
    val compensateLatency: Boolean = true,
    /** Maksymalne opóźnienie, które kompensujemy, ms. */
    val maxCompensatedLatencyMs: Long = 400,

    /** Wzmocnienie P w pionie: m błędu wysokości → m/s. */
    val kpVertical: Double = 0.5,
    val maxClimbMps: Double = 0.5,
    val maxDescentMps: Double = 0.3,
    val heightDeadbandM: Double = 0.1,

    /**
     * Stożek bezpieczeństwa (sekcja 5.5): opadanie dozwolone tylko, gdy błąd poziomy
     * ≤ `descentToleranceBaseM + descentTolerancePerM · wysokość`.
     */
    val descentToleranceBaseM: Double = 0.15,
    val descentTolerancePerM: Double = 0.08,

    /** Obracanie drona do osi markera (w teście zawisu domyślnie wyłączone). */
    val alignYaw: Boolean = false,
    val kpYaw: Double = 0.8,
    val maxYawRateDegS: Double = 15.0,
    /** Obrót tylko wtedy, gdy dron jest już blisko środka, m. */
    val yawEnableErrorM: Double = 0.5,
) {
    init {
        require(kpHorizontal > 0 && kpVertical > 0) { "Wzmocnienia P muszą być dodatnie" }
        require(maxHorizontalMps > 0 && maxClimbMps > 0 && maxDescentMps > 0) { "Limity prędkości muszą być dodatnie" }
        require(derivativeFilterAlpha in 0.0..1.0) { "Współczynnik filtra musi być w zakresie 0..1" }
    }

    /** Dopuszczalny błąd poziomy przy opadaniu z wysokości [heightM]. */
    fun descentToleranceM(heightM: Double): Double = descentToleranceBaseM + descentTolerancePerM * max(0.0, heightM)
}

/** Wynik jednego kroku regulatora — punkt zadany i błędy do logu i UI. */
data class MarkerHoldCommand(
    val setpoint: Setpoint,
    val horizontalErrorM: Double,
    val heightErrorM: Double,
    /** Czy w tym kroku wolno było opadać (błąd poziomy w stożku). */
    val descentAllowed: Boolean,
)

/**
 * Regulator PD (z małym, ograniczonym członem I) utrzymujący drona nad środkiem lądowiska
 * na zadanej wysokości (test R3, sekcja 5.5).
 *
 * - Poziomo: `v = Kp·e + Kd·de/dt + Ki·∫e`, gdzie `e` to położenie lądowiska względem drona w BODY.
 *   Pochodna pochodzi z prędkości drona z telemetrii (`de/dt = −v_drona` dla nieruchomego markera),
 *   a gdy jej brak — z filtrowanej różnicy kolejnych obserwacji.
 * - Pionowo: P do wysokości docelowej z ograniczeniem wznoszenia i opadania. Opadanie tylko wtedy,
 *   gdy błąd poziomy mieści się w stożku zależnym od wysokości — inaczej co najwyżej zawis w pionie.
 * - Kurs: opcjonalnie P na błędzie kursu względem markera.
 *
 * Czysty Kotlin bez wątków — wołany z jednej korutyny; [reset] przed każdym nowym podejściem.
 */
class MarkerHoldController(val config: MarkerHoldConfig = MarkerHoldConfig()) {

    private var previous: MarkerObservation? = null
    private var filteredRateForward = 0.0
    private var filteredRateRight = 0.0
    private var integralForward = 0.0
    private var integralRight = 0.0

    fun reset() {
        previous = null
        filteredRateForward = 0.0
        filteredRateRight = 0.0
        integralForward = 0.0
        integralRight = 0.0
    }

    /**
     * @param observation świeża obserwacja lądowiska
     * @param drone bieżąca telemetria (prędkość i kurs do członu D i kompensacji opóźnienia)
     * @param targetHeightM wysokość docelowa kamery nad markerem, m
     */
    fun update(observation: MarkerObservation, drone: DroneState, targetHeightM: Double): MarkerHoldCommand {
        val c = config
        val body = drone.bodyVelocity()

        // Błąd „na teraz”: obserwacja jest sprzed latencyMs, w tym czasie dron przeleciał v·t.
        var errForward = observation.dxM
        var errRight = observation.dyM
        val latencyMs = observation.latencyMs
        if (c.compensateLatency && body != null && latencyMs != null && latencyMs > 0) {
            val t = latencyMs.coerceAtMost(c.maxCompensatedLatencyMs) / 1000.0
            errForward -= body.forwardMps * t
            errRight -= body.rightMps * t
        }

        val prevObs = previous
        val dtObs = prevObs?.let { (observation.timestampMs - it.timestampMs) / 1000.0 }?.takeIf { it in 0.01..0.5 }

        // Człon D: tempo zmiany błędu.
        val rateForward: Double
        val rateRight: Double
        if (body != null) {
            rateForward = -body.forwardMps
            rateRight = -body.rightMps
        } else {
            val prev = previous
            val dt = prev?.let { (observation.timestampMs - it.timestampMs) / 1000.0 } ?: 0.0
            if (prev != null && dt in 0.01..1.0) {
                val a = c.derivativeFilterAlpha
                filteredRateForward = a * ((observation.dxM - prev.dxM) / dt) + (1 - a) * filteredRateForward
                filteredRateRight = a * ((observation.dyM - prev.dyM) / dt) + (1 - a) * filteredRateRight
            }
            rateForward = filteredRateForward
            rateRight = filteredRateRight
        }
        previous = observation

        val horizontalError = hypot(errForward, errRight)

        // Człon I (z ograniczeniem i strefą — bez nawijania przy dużych błędach).
        if (c.kiHorizontal > 0 && dtObs != null && horizontalError <= c.integralZoneM) {
            integralForward += c.kiHorizontal * errForward * dtObs
            integralRight += c.kiHorizontal * errRight * dtObs
            val mag = hypot(integralForward, integralRight)
            if (mag > c.maxIntegralMps) {
                integralForward *= c.maxIntegralMps / mag
                integralRight *= c.maxIntegralMps / mag
            }
        }

        val pForward = if (horizontalError < c.horizontalDeadbandM) 0.0 else c.kpHorizontal * errForward
        val pRight = if (horizontalError < c.horizontalDeadbandM) 0.0 else c.kpHorizontal * errRight
        var vForward = pForward + c.kdHorizontal * rateForward + integralForward
        var vRight = pRight + c.kdHorizontal * rateRight + integralRight
        val vHorizontal = hypot(vForward, vRight)
        if (vHorizontal > c.maxHorizontalMps) {
            vForward *= c.maxHorizontalMps / vHorizontal
            vRight *= c.maxHorizontalMps / vHorizontal
        }

        val height = observation.dzM
        val heightError = targetHeightM - height
        var vUp = if (abs(heightError) < c.heightDeadbandM) 0.0 else c.kpVertical * heightError
        vUp = vUp.coerceIn(-c.maxDescentMps, c.maxClimbMps)
        val descentAllowed = horizontalError <= c.descentToleranceM(height)
        if (!descentAllowed && vUp < 0) vUp = 0.0

        val yawRate = if (c.alignYaw && horizontalError <= c.yawEnableErrorM) {
            (c.kpYaw * observation.yawDeg).coerceIn(-c.maxYawRateDegS, c.maxYawRateDegS)
        } else {
            0.0
        }

        return MarkerHoldCommand(
            setpoint = Setpoint(vForward, vRight, vUp, yawRate),
            horizontalErrorM = horizontalError,
            heightErrorM = heightError,
            descentAllowed = descentAllowed,
        )
    }
}
