package com.example.djicontroller.core.port

/** Kategorie wpisów logu (zasada Z5). */
enum class LogCategory { TELEMETRY, STATE, COMMAND, MARKER, SAFETY, STATION, SYSTEM }

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Port logu lotu. Implementacja nadaje znacznik czasu i zapisuje wpis (np. JSON Lines do pliku).
 * Wywołanie musi być nieblokujące i bezpieczne wątkowo — logger jest wołany z pętli sterowania.
 */
interface FlightLogger {
    fun log(
        category: LogCategory,
        level: LogLevel,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    )
}

/** Logger, który nic nie zapisuje — wyłącznie dla podglądów UI. */
object NoOpFlightLogger : FlightLogger {
    override fun log(category: LogCategory, level: LogLevel, event: String, fields: Map<String, Any?>) = Unit
}
