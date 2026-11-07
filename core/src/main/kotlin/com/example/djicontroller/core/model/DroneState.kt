package com.example.djicontroller.core.model

/**
 * Jedno źródło prawdy o dronie (zasada Z1).
 *
 * Składany przez `KeyRepository` z kluczy telemetrii MSDK (tabela w sekcji 5.2).
 * Każde pole jest nullowalne: `null` oznacza „nieznane” (klucz jeszcze nie przyszedł
 * albo nie jest obsługiwany przez Mini 4 Pro) i **nie** jest równoważne zeru ani `false`.
 * Logika bezpieczeństwa traktuje wartości nieznane zachowawczo.
 */
data class DroneState(
    /** `FlightControllerKey.KeyConnection`. */
    val connected: Boolean = false,
    /** `BatteryKey.KeyChargeRemainingInPercent`, 0..100. */
    val batteryPercent: Int? = null,
    /** `FlightControllerKey.KeyAircraftLocation`. */
    val location: GeoPoint? = null,
    /** `FlightControllerKey.KeyAltitude` — wysokość barometryczna względem punktu startu, m. */
    val altitudeM: Double? = null,
    /** `FlightControllerKey.KeyUltrasonicHeight` — wysokość nad podłożem z czujnika ToF, m (krótki zasięg). */
    val ultrasonicHeightM: Double? = null,
    /** `FlightControllerKey.KeyCompassHeading`, stopnie −180..180 lub 0..360 (jak zwraca SDK). */
    val headingDeg: Double? = null,
    /** `FlightControllerKey.KeyAircraftVelocity` (NED). */
    val velocity: Velocity3D? = null,
    /** `FlightControllerKey.KeyGPSSatelliteCount`. */
    val gpsSatelliteCount: Int? = null,
    /** `FlightControllerKey.KeyGPSSignalLevel` znormalizowany do 0..5. */
    val gpsSignalLevel: Int? = null,
    /** `FlightControllerKey.KeyIsFlying`. */
    val isFlying: Boolean? = null,
    /** `FlightControllerKey.KeyAreMotorsOn`. */
    val areMotorsOn: Boolean? = null,
    /** `FlightControllerKey.KeyFlightMode` — nazwa enuma z MSDK, tylko do logów i UI. */
    val flightMode: String? = null,
    /** `FlightControllerKey.KeyHomeLocation` — punkt startu, czyli stacja. */
    val homeLocation: GeoPoint? = null,
    /** `FlightControllerKey.KeyIsLowBatteryWarning`. */
    val isLowBatteryWarning: Boolean? = null,
    /** `FlightControllerKey.KeyIsSeriousLowBatteryWarning`. */
    val isSeriousLowBatteryWarning: Boolean? = null,
    /** `FlightControllerKey.KeyIsFailSafe` — dron wykonuje procedurę utraty łącza. */
    val isFailSafe: Boolean? = null,
    /** `FlightControllerKey.KeyFailsafeAction`. */
    val failsafeAction: FailsafeAction? = null,
    /** `FlightControllerKey.KeyIsLandingConfirmationNeeded` — ochrona lądowania czeka na potwierdzenie. */
    val isLandingConfirmationNeeded: Boolean? = null,
    /** Czas monotoniczny ostatniej aktualizacji dowolnego pola, ms ([com.example.djicontroller.core.port.Clock.monotonicMs]). */
    val updatedAtMs: Long = 0L,
) {
    val hasValidLocation: Boolean get() = location?.isValid == true

    /** Odległość pozioma do [point] albo `null`, gdy brak ważnej pozycji. */
    fun distanceToM(point: GeoPoint): Double? =
        location?.takeIf { it.isValid && point.isValid }?.distanceTo(point)

    fun distanceToHomeM(): Double? = homeLocation?.let { distanceToM(it) }

    /**
     * Zachowawcza ocena „czy dron może być w powietrzu”: brak danych traktujemy jak lot.
     * Używane do decyzji, których pomyłka w drugą stronę byłaby niebezpieczna.
     */
    val possiblyAirborne: Boolean get() = isFlying != false || areMotorsOn == true

    /** Pewność, że dron stoi na ziemi z wyłączonymi silnikami. */
    val confirmedOnGroundMotorsOff: Boolean get() = isFlying == false && areMotorsOn == false

    companion object {
        val UNKNOWN = DroneState()
    }
}

/** Reakcja drona na utratę łącza z aparaturą (`KeyFailsafeAction`). */
enum class FailsafeAction { HOVER, LANDING, GO_HOME, UNKNOWN }
