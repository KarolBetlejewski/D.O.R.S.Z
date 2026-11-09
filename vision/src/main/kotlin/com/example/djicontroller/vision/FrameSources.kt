package com.example.djicontroller.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.djicontroller.core.model.FrameFormat
import com.example.djicontroller.core.model.VideoFrame
import com.example.djicontroller.core.port.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * Testowe źródła klatek bez podłączonego drona (sekcja 5.4): zdjęcie lub nagranie markera
 * odtwarzane jako klatki NV21 (sama luminancja, chrominancja = 128), z tym samym kontraktem
 * co kamera MSDK — znacznik czasu z [Clock] w chwili „odbioru”.
 *
 * Poza obliczona z pliku jest poprawna tylko wtedy, gdy kalibracja kamery odpowiada aparatowi,
 * którym zrobiono zdjęcie/nagranie (dla nagrań z Mini 4 Pro — domyślna albo własna kalibracja).
 */
object FrameSources {

    /** Zdjęcie [uri] powtarzane z częstotliwością [fps]. */
    fun fromImage(context: Context, uri: Uri, clock: Clock, fps: Double = 10.0, maxWidth: Int = 1920): TestFrameSource {
        val name = "obraz: ${uri.lastPathSegment ?: uri}"
        val frames = flow {
            check(OpenCvRuntime.ensureLoaded()) { "OpenCV niezaładowane" }
            val bitmap = decodeBitmap(context, uri, maxWidth) ?: throw IOException("Nie można odczytać obrazu $uri")
            val gray = bitmap.toGrayNv21()
            bitmap.recycle()
            val periodMs = (1000.0 / fps).toLong().coerceAtLeast(10)
            while (currentCoroutineContext().isActive) {
                emit(VideoFrame(gray.data.copyOf(), gray.width, gray.height, FrameFormat.NV21, clock.monotonicMs()))
                delay(periodMs)
            }
        }.flowOn(Dispatchers.IO)
        return TestFrameSource(name, frames)
    }

    /** Nagranie [uri] odtwarzane w pętli, klatka co `1/fps` s czasu nagrania. */
    fun fromVideo(context: Context, uri: Uri, clock: Clock, fps: Double = 10.0, maxWidth: Int = 1280): TestFrameSource {
        val name = "wideo: ${uri.lastPathSegment ?: uri}"
        val frames = flow {
            check(OpenCvRuntime.ensureLoaded()) { "OpenCV niezaładowane" }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: throw IOException("Brak długości nagrania $uri")
                val stepMs = (1000.0 / fps).toLong().coerceAtLeast(10)
                var t = 0L
                while (currentCoroutineContext().isActive) {
                    val started = clock.monotonicMs()
                    val bitmap = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bitmap != null) {
                        val scaled = bitmap.scaledToWidth(maxWidth)
                        val gray = scaled.toGrayNv21()
                        if (scaled !== bitmap) scaled.recycle()
                        bitmap.recycle()
                        emit(VideoFrame(gray.data, gray.width, gray.height, FrameFormat.NV21, clock.monotonicMs()))
                    }
                    t += stepMs
                    if (t > durationMs) t = 0
                    delay(max(0L, stepMs - (clock.monotonicMs() - started)))
                }
            } finally {
                retriever.release()
            }
        }.flowOn(Dispatchers.IO)
        return TestFrameSource(name, frames)
    }

    private fun decodeBitmap(context: Context, uri: Uri, maxWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun Bitmap.scaledToWidth(maxWidth: Int): Bitmap =
        if (width <= maxWidth) this else Bitmap.createScaledBitmap(this, maxWidth, height * maxWidth / width, true)
}

/** Klatka w skali szarości o parzystych wymiarach, zapisana jako NV21 (UV = 128). */
internal class GrayFrame(val data: ByteArray, val width: Int, val height: Int)

internal fun Bitmap.toGrayNv21(): GrayFrame {
    val argb = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    val rgba = Mat()
    val gray = Mat()
    try {
        Utils.bitmapToMat(argb, rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        // NV21 wymaga parzystych wymiarów.
        val w = gray.cols() and 1.inv()
        val h = gray.rows() and 1.inv()
        val data = ByteArray(w * h * 3 / 2) { NEUTRAL_CHROMA }
        val row = ByteArray(gray.cols())
        for (y in 0 until h) {
            gray.get(y, 0, row)
            System.arraycopy(row, 0, data, y * w, w)
        }
        return GrayFrame(data, w, h)
    } finally {
        rgba.release()
        gray.release()
        if (argb !== this) argb.recycle()
    }
}

/** Zapis klatki (płaszczyzny Y) do PNG — np. zdjęcia szachownicy do kalibracji kamery. */
object FrameSnapshot {
    fun writePng(frame: VideoFrame, file: File): Boolean {
        if (!OpenCvRuntime.ensureLoaded()) return false
        if (frame.data.size < frame.width * frame.height) return false
        val gray = Mat(frame.height, frame.width, CvType.CV_8UC1)
        return try {
            gray.put(0, 0, frame.data, 0, frame.width * frame.height)
            file.parentFile?.mkdirs()
            Imgcodecs.imwrite(file.absolutePath, gray)
        } finally {
            gray.release()
        }
    }
}

internal const val NEUTRAL_CHROMA: Byte = 128.toByte()
