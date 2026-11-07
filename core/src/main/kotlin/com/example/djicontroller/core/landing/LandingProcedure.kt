package com.example.djicontroller.core.landing

/**
 * Procedura precyzyjnego lądowania L2–L5 (sekcja 5.5): szukanie markera, centrowanie
 * i opadanie na Virtual Stick, przyziemienie automatycznym lądowaniem DJI, weryfikacja.
 *
 * Wołana przez orkiestrator w stanie `Landing(attempt)`, gdy dron jest nad stacją.
 * Implementacja (F3) korzysta z `FlightControlPort`, `CameraPort` i detektora z :vision.
 * Anulowanie korutyny musi zostawić drona w zawisie (Z4).
 */
fun interface LandingProcedure {
    suspend fun execute(attempt: Int): LandingOutcome
}

sealed interface LandingOutcome {
    /** Dron na polu ładowania, silniki wyłączone. */
    data object Landed : LandingOutcome

    /** Marker utracony dłużej niż dopuszczalny czas — orkiestrator ponawia próbę. */
    data class MarkerLost(val reason: String) : LandingOutcome

    /** Przerwanie bez sensu ponawiania (np. odmowa ochrony lądowania, utrata sterowania). */
    data class Aborted(val reason: String) : LandingOutcome
}
