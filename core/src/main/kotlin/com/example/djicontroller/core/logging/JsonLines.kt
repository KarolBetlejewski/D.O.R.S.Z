package com.example.djicontroller.core.logging

/**
 * Minimalny koder JSON bez zależności — jeden wpis logu = jedna linia (format JSON Lines),
 * co ułatwia analizę logów z testów R3–R6 (np. `jq`, pandas).
 *
 * Obsługiwane wartości: `null`, Boolean, liczby (NaN/∞ → `null`), String, Enum (nazwa),
 * Map (klucze przez `toString`), Iterable, Array; pozostałe przez `toString()`.
 */
object JsonLines {

    fun encodeObject(fields: Map<String, Any?>): String = buildString { appendObject(fields) }

    fun encodeValue(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendObject(map: Map<*, *>) {
        append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) append(',')
            first = false
            appendString(k.toString())
            append(':')
            appendValue(v)
        }
        append('}')
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(value)
            is Double -> if (value.isFinite()) append(value) else append("null")
            is Float -> if (value.isFinite()) append(value) else append("null")
            is Number -> append(value)
            is String -> appendString(value)
            is Enum<*> -> appendString(value.name)
            is Map<*, *> -> appendObject(value)
            is Iterable<*> -> appendArray(value.iterator())
            is Array<*> -> appendArray(value.iterator())
            else -> appendString(value.toString())
        }
    }

    private fun StringBuilder.appendArray(items: Iterator<*>) {
        append('[')
        var first = true
        for (item in items) {
            if (!first) append(',')
            first = false
            appendValue(item)
        }
        append(']')
    }

    private fun StringBuilder.appendString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
}
