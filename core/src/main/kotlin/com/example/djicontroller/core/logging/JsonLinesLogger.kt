package com.example.djicontroller.core.logging

import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel

/** Odbiorca gotowych linii logu — w :app zapis do pliku, w testach lista w pamięci. */
fun interface LineSink {
    fun write(line: String)
}

/**
 * [FlightLogger] zapisujący wpisy jako JSON Lines:
 * `{"ts":<epoch ms>,"mono":<monotoniczny ms>,"cat":"STATE","lvl":"INFO","ev":"transition",...pola}`.
 *
 * Używa czasu epoch zamiast ISO-8601, bo `java.time` nie jest dostępne na Androidzie API 24–25
 * bez desugaringu. Klucze zastrzeżone (`ts`, `mono`, `cat`, `lvl`, `ev`) w [fields] są nadpisywane.
 */
class JsonLinesLogger(
    private val clock: Clock,
    private val sink: LineSink,
    private val minLevel: LogLevel = LogLevel.DEBUG,
) : FlightLogger {

    override fun log(category: LogCategory, level: LogLevel, event: String, fields: Map<String, Any?>) {
        if (level < minLevel) return
        val record = LinkedHashMap<String, Any?>(fields.size + 5)
        record["ts"] = clock.wallMs()
        record["mono"] = clock.monotonicMs()
        record["cat"] = category
        record["lvl"] = level
        record["ev"] = event
        for ((k, v) in fields) {
            if (k !in RESERVED) record[k] = v
        }
        sink.write(JsonLines.encodeObject(record))
    }

    private companion object {
        val RESERVED = setOf("ts", "mono", "cat", "lvl", "ev")
    }
}

/** Rozsyła wpisy do kilku loggerów (np. plik + logcat). */
class CompositeFlightLogger(private val loggers: List<FlightLogger>) : FlightLogger {
    override fun log(category: LogCategory, level: LogLevel, event: String, fields: Map<String, Any?>) {
        for (l in loggers) l.log(category, level, event, fields)
    }
}
