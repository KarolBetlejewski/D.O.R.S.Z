package com.example.djicontroller.core.orchestrator

import com.example.djicontroller.core.model.DroneState
import com.example.djicontroller.core.model.GeoPoint
import kotlin.math.ceil

/**
 * Parametry progu powrotu. Wartości domyślne są punktem startowym do kalibracji
 * na podstawie logów z testów (sekcja 5.2) — nie są zmierzone na Mini 4 Pro.
 */
data class BatteryPolicyConfig(
    /** Stała rezerwa na zniżanie nad stacją i lądowanie z markerem, łącznie z ponownymi próbami, %. */
    val landingReservePercent: Double = 15.0,
    /** Zużycie na kilometr lotu powrotnego, %/km (Mini 4 Pro przy ~6–8 m/s, bez wiatru: ok. 5 %/km). */
    val returnCostPercentPerKm: Double = 8.0,
    /** Mnożnik na wiatr i starzenie się akumulatora. */
    val marginFactor: Double = 1.3,
    /** Odległość przyjmowana, gdy brak ważnej pozycji GPS, m. */
    val unknownDistanceAssumptionM: Double = 1_000.0,
    val minReturnThresholdPercent: Int = 25,
    val maxReturnThresholdPercent: Int = 90,
    /** Poniżej tego poziomu nie wracamy, tylko lądujemy w bieżącym miejscu (sekcja 5.8). */
    val criticalPercent: Int = 12,
) {
    init {
        require(landingReservePercent >= 0) { "landingReservePercent < 0" }
        require(returnCostPercentPerKm > 0) { "returnCostPercentPerKm musi być dodatni" }
        require(marginFactor >= 1.0) { "marginFactor musi być ≥ 1" }
        require(unknownDistanceAssumptionM >= 0) { "unknownDistanceAssumptionM < 0" }
        require(criticalPercent in 0 until minReturnThresholdPercent) { "criticalPercent musi być < minReturnThresholdPercent" }
        require(minReturnThresholdPercent <= maxReturnThresholdPercent) { "min > max progu powrotu" }
        require(maxReturnThresholdPercent <= 100) { "max progu powrotu > 100" }
    }
}

sealed interface BatteryDecision {
    /** Brak odczytu baterii — decyzję podejmuje wyłącznie dron (jego własne ostrzeżenia). */
    data object Unknown : BatteryDecision

    data class Continue(val batteryPercent: Int, val thresholdPercent: Int) : BatteryDecision

    data class ReturnNow(val batteryPercent: Int, val thresholdPercent: Int, val reason: String) : BatteryDecision

    data class LandNow(val batteryPercent: Int?, val reason: String) : BatteryDecision
}

/**
 * Dynamiczny próg powrotu: rezerwa na lądowanie + energia na lot do stacji
 * proporcjonalna do odległości (sekcja 5.2, „Próg powrotu”). Klasa jest bezstanowa;
 * histereza (raz podjęta decyzja o powrocie nie jest cofana) należy do orkiestratora.
 */
class BatteryPolicy(val config: BatteryPolicyConfig = BatteryPolicyConfig()) {

    fun returnThresholdPercent(distanceToStationM: Double?): Int {
        val distanceM = distanceToStationM?.takeIf { it.isFinite() && it >= 0 }
            ?: config.unknownDistanceAssumptionM
        val raw = config.landingReservePercent +
            distanceM / 1_000.0 * config.returnCostPercentPerKm * config.marginFactor
        return ceil(raw).toInt().coerceIn(config.minReturnThresholdPercent, config.maxReturnThresholdPercent)
    }

    fun evaluate(state: DroneState, station: GeoPoint): BatteryDecision {
        val battery = state.batteryPercent
        if (state.isSeriousLowBatteryWarning == true) {
            return BatteryDecision.LandNow(battery, "Dron zgłosił krytycznie niski poziom baterii")
        }
        if (battery == null) return BatteryDecision.Unknown
        if (battery <= config.criticalPercent) {
            return BatteryDecision.LandNow(battery, "Bateria $battery% ≤ poziom krytyczny ${config.criticalPercent}%")
        }
        val threshold = returnThresholdPercent(state.distanceToM(station))
        return when {
            battery <= threshold ->
                BatteryDecision.ReturnNow(battery, threshold, "Bateria $battery% ≤ próg powrotu $threshold%")
            state.isLowBatteryWarning == true ->
                BatteryDecision.ReturnNow(battery, threshold, "Dron zgłosił niski poziom baterii ($battery%)")
            else -> BatteryDecision.Continue(battery, threshold)
        }
    }
}
