package com.example.djicontroller.core.model

/** Ostatni status przysłany przez stację (wiadomość `status`, sekcja 5.7). */
data class StationStatus(
    val charging: Boolean,
    val dronePresent: Boolean,
    val padClear: Boolean,
    /** „Stacja gotowa” — stacja jest gotowa do rozpoczęcia kolejnego cyklu. */
    val ready: Boolean,
    /** Czas monotoniczny odbioru wiadomości, ms. */
    val receivedAtMs: Long,
)

sealed interface StationLink {
    /** Brak heartbeatu przez 3 s lub brak połączenia WebSocket. */
    data object Disconnected : StationLink

    /** Połączono; [status] jest `null`, dopóki nie przyjdzie pierwsza wiadomość `status`. */
    data class Connected(val status: StationStatus?) : StationLink
}

val StationLink.status: StationStatus?
    get() = (this as? StationLink.Connected)?.status

enum class PowerAction { POWER_ON, POWER_OFF }

/** Polecenia aplikacja → stacja. Są idempotentne, więc ponowienie po utracie `ack` jest bezpieczne. */
sealed interface StationCommand {
    /** Sekwencja popychacza przycisku zasilania; dokładną sekwencję naciśnięć realizuje firmware stacji (R2). */
    data class PowerButton(val action: PowerAction) : StationCommand

    /** Ładowanie wolno włączyć tylko przy wyłączonym dronie (R1). */
    data class Charge(val on: Boolean) : StationCommand
}
