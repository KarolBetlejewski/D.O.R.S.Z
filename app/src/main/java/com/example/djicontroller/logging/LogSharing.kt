package com.example.djicontroller.logging

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Własna klasa dostawcy (zamiast `androidx.core.content.FileProvider` wprost) — biblioteki DJI deklarują
 * swojego `FileProvider`, a dwa wpisy o tej samej nazwie klasy kolidują przy łączeniu manifestów.
 */
class LogFileProvider : FileProvider()

/**
 * Wysyłanie logów lotu z telefonu w terenie (bez kabla i adb): arkusz „Udostępnij” Androida —
 * poczta, Dysk, komunikator. Pliki udostępniane przez [LogFileProvider] (`res/xml/log_paths.xml`).
 */
object LogSharing {

    private fun authority(context: Context) = "${context.packageName}.logs"

    /** Ostatnie [count] plików logu (bieżące uruchomienie i poprzednie — np. sprzed restartu aplikacji). */
    fun latestLogs(directory: File, count: Int = 3): List<File> =
        directory.listFiles { f -> f.isFile && f.name.startsWith("flight_") && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name }
            ?.take(count)
            .orEmpty()

    /** Otwiera arkusz udostępniania z plikami; `false`, gdy nie ma czego wysłać. */
    fun shareFiles(context: Context, files: List<File>, subject: String): Boolean {
        if (files.isEmpty()) return false
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, authority(context), it) })
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(subject, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Udostępnia tekst (np. raport zgodności do wklejenia w dokumentację testów). */
    fun shareText(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
