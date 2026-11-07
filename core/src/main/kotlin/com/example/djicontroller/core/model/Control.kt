package com.example.djicontroller.core.model

import kotlin.math.hypot

/** Wynik polecenia wysłanego do drona lub stacji. */
sealed interface CommandResult {
    data object Success : CommandResult
    data class Failure(val reason: String, val code: String? = null) : CommandResult

    val isSuccess: Boolean get() = this is Success
}

inline fun CommandResult.onFailure(block: (CommandResult.Failure) -> Unit): CommandResult {
    if (this is CommandResult.Failure) block(this)
    return this
}

/**
 * Kto w danej chwili steruje ruchem drona (zasada Z2: dokładnie jeden właściciel).
 * Pilnuje tego [com.example.djicontroller.core.control.ControlArbiter].
 */
enum class ControlOwner { NONE, MISSION, VIRTUAL_STICK, AUTO_LANDING }

/** Utrata sterowania zgłoszona przez `VirtualStickStateListener.onChangeReasonUpdate`. */
data class ControlLoss(val reason: String, val takenOverByRc: Boolean)

/**
 * Zadana prędkość w układzie BODY drona dla Virtual Stick w trybie VELOCITY (sekcja 5.6).
 * Osie: do przodu (+), w prawo (+), w górę (+); obrót w prawo (+).
 */
data class Setpoint(
    val forwardMps: Double,
    val rightMps: Double,
    val upMps: Double,
    val yawRateDegS: Double,
) {
    val isHover: Boolean
        get() = forwardMps == 0.0 && rightMps == 0.0 && upMps == 0.0 && yawRateDegS == 0.0

    /**
     * Ogranicza wartości do [limits]. Prędkość poziomą ogranicza jako wektor (zachowuje kierunek).
     * Wartość nieskończona lub NaN w dowolnej osi daje [HOVER] (zasada Z4).
     */
    fun clamped(limits: SetpointLimits): Setpoint {
        if (!forwardMps.isFinite() || !rightMps.isFinite() || !upMps.isFinite() || !yawRateDegS.isFinite()) {
            return HOVER
        }
        val horizontal = hypot(forwardMps, rightMps)
        val scale = if (horizontal > limits.maxHorizontalMps && horizontal > 0.0) limits.maxHorizontalMps / horizontal else 1.0
        return Setpoint(
            forwardMps = forwardMps * scale,
            rightMps = rightMps * scale,
            upMps = upMps.coerceIn(-limits.maxVerticalMps, limits.maxVerticalMps),
            yawRateDegS = yawRateDegS.coerceIn(-limits.maxYawRateDegS, limits.maxYawRateDegS),
        )
    }

    companion object {
        val HOVER = Setpoint(0.0, 0.0, 0.0, 0.0)
    }
}

/** Twarde limity Virtual Stick; w fazie lądowania nad stacją celowo niskie. */
data class SetpointLimits(
    val maxHorizontalMps: Double = 2.0,
    val maxVerticalMps: Double = 1.0,
    val maxYawRateDegS: Double = 30.0,
) {
    init {
        require(maxHorizontalMps > 0 && maxVerticalMps > 0 && maxYawRateDegS > 0) { "Limity muszą być dodatnie" }
    }
}

/** Stan misji WPML raportowany przez `MissionAdapter`. */
enum class MissionStatus { IDLE, UPLOADING, READY, EXECUTING, PAUSED, FINISHED, FAILED, UNKNOWN }

data class MissionProgress(
    /** Identyfikator planu ([MissionPlan.id]), którego dotyczy stan; `null` = brak misji. */
    val missionId: String? = null,
    val status: MissionStatus = MissionStatus.IDLE,
    /** `WaylineExecutingInfo.currentWaypointIndex` — indeks w [MissionPlan.waypoints]. */
    val missionWaypointIndex: Int? = null,
    val missionWaypointCount: Int? = null,
    val error: String? = null,
) {
    val isActive: Boolean get() = status == MissionStatus.EXECUTING || status == MissionStatus.PAUSED
}

/** Stan inicjalizacji MSDK i połączenia z dronem (sekcja 5.1). */
sealed interface ConnectionState {
    data object Initializing : ConnectionState
    data object Registering : ConnectionState
    data class RegistrationFailed(val reason: String, val code: String? = null) : ConnectionState

    /** MSDK zarejestrowane, brak połączonego drona (dron wyłączony albo brak łącza z RC-N2). */
    data object Disconnected : ConnectionState

    /** Dron połączony; [supported] = `true` tylko dla DJI Mini 4 Pro. */
    data class Connected(val productId: Int, val productType: String, val supported: Boolean) : ConnectionState
}
