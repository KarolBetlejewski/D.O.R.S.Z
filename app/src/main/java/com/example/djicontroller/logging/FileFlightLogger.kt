package com.example.djicontroller.logging

import android.content.Context
import android.util.Log
import com.example.djicontroller.core.logging.JsonLinesLogger
import com.example.djicontroller.core.logging.LineSink
import com.example.djicontroller.core.port.Clock
import com.example.djicontroller.core.port.FlightLogger
import com.example.djicontroller.core.port.LogCategory
import com.example.djicontroller.core.port.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Lokalny log lotu (zasada Z5): JSON Lines, jeden plik na uruchomienie aplikacji,
 * w `Android/data/<pakiet>/files/flight-logs/flight_RRRRMMDD_GGMMSS.jsonl`.
 *
 * [log] nie blokuje wywołującego — linie trafiają do kanału, a zapis wykonuje osobna
 * korutyna na `Dispatchers.IO`. Bufor jest opróżniany na dysk, gdy kanał się wyczerpie,
 * więc przy normalnym ruchu opóźnienie zapisu to pojedyncze milisekundy.
 * Gdy kanał jest pełny (dysk nie nadąża), linie są odrzucane i liczone w [droppedLines].
 */
class FileFlightLogger(
    context: Context,
    clock: Clock,
    scope: CoroutineScope,
    private val keepFiles: Int = 50,
) : FlightLogger {

    val directory: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "flight-logs").apply { mkdirs() }

    val file: File = File(
        directory,
        "flight_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(clock.wallMs())) + ".jsonl",
    )

    private val lines = Channel<String>(capacity = 16_384)
    private val dropped = AtomicLong(0)
    val droppedLines: Long get() = dropped.get()

    private val json = JsonLinesLogger(clock, LineSink { line ->
        if (lines.trySend(line).isFailure) dropped.incrementAndGet()
    })

    init {
        pruneOldFiles()
        scope.launch(Dispatchers.IO) { writeLoop() }
    }

    override fun log(category: LogCategory, level: LogLevel, event: String, fields: Map<String, Any?>) {
        json.log(category, level, event, fields)
    }

    private suspend fun writeLoop() {
        val writer = try {
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), 64 * 1024)
        } catch (e: IOException) {
            Log.e(TAG, "Nie można otworzyć logu ${file.absolutePath}", e)
            return
        }
        writer.use { w ->
            for (first in lines) {
                try {
                    w.write(first)
                    w.newLine()
                    while (true) {
                        val next = lines.tryReceive().getOrNull() ?: break
                        w.write(next)
                        w.newLine()
                    }
                    w.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Błąd zapisu logu", e)
                }
            }
        }
    }

    private fun pruneOldFiles() {
        val files = directory.listFiles { f -> f.isFile && f.name.startsWith("flight_") && f.name.endsWith(".jsonl") }
            ?: return
        files.sortedByDescending { it.name }.drop(keepFiles).forEach { it.delete() }
    }

    private companion object {
        const val TAG = "FlightLog"
    }
}

/** Kopia ostrzeżeń i błędów w Logcat — wygodne przy debugowaniu z Android Studio. */
class LogcatFlightLogger(private val minLevel: LogLevel = LogLevel.WARN) : FlightLogger {
    override fun log(category: LogCategory, level: LogLevel, event: String, fields: Map<String, Any?>) {
        if (level < minLevel) return
        val message = if (fields.isEmpty()) event else "$event $fields"
        val tag = "Patrol/${category.name}"
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
