package com.example.djicontroller.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.Surface
import com.example.djicontroller.core.model.VideoFrame
import com.example.djicontroller.ui.VideoPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Podgląd w wariancie `demo`: dokładnie te klatki, które dostaje detektor ArUco
 * ([SimulatedCamera] → [com.example.djicontroller.vision.SyntheticPadCamera]), wpasowane w powierzchnię
 * jak `ScaleType.CENTER_INSIDE` w MSDK — dzięki temu nakładka wizyjna w UI trafia w marker tak samo jak w `prod`.
 */
class SimulatedVideo(
    private val sim: DroneSimulator,
    private val camera: SimulatedCamera,
    private val scope: CoroutineScope,
) : VideoPreview {

    private val lock = Any()
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var drawJob: Job? = null
    private var collectJob: Job? = null

    @Volatile private var latest: VideoFrame? = null
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)

    private val text = Paint().apply { color = Color.WHITE; textSize = 30f; isAntiAlias = true }
    private val small = Paint().apply { color = Color.WHITE; textSize = 22f; isAntiAlias = true }
    private val textBg = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }

    override fun attach(surface: Surface, width: Int, height: Int) {
        synchronized(lock) {
            this.surface = surface
            this.width = width
            this.height = height
        }
        if (collectJob?.isActive != true) {
            collectJob = scope.launch(Dispatchers.Default) { camera.frames.collect { latest = it } }
        }
        if (drawJob?.isActive != true) {
            drawJob = scope.launch(Dispatchers.Default) {
                while (isActive) {
                    drawFrame()
                    delay(66)
                }
            }
        }
    }

    override fun detach(surface: Surface) {
        synchronized(lock) {
            if (this.surface == surface) {
                this.surface = null
                collectJob?.cancel()
                collectJob = null
                drawJob?.cancel()
                drawJob = null
            }
        }
    }

    private fun drawFrame() = synchronized(lock) {
        val s = surface ?: return@synchronized
        if (!s.isValid || width <= 0 || height <= 0) return@synchronized
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: IllegalArgumentException) {
            return@synchronized
        } catch (e: IllegalStateException) {
            return@synchronized
        }
        try {
            render(canvas)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
    }

    private fun render(c: Canvas) {
        c.drawColor(Color.rgb(30, 33, 36))
        // Dron wyłączony: tylko tło — komunikat pokazuje nakładka UI.
        if (!sim.poweredOn) return
        val frame = latest
        if (frame == null) {
            // Dron włączony, a klatek brak — pokaż powód zamiast czarnego ekranu.
            val msg = camera.problem ?: "Uruchamianie kamery syntetycznej…"
            c.drawText(msg, 24f, c.height / 2f, text)
            return
        }
        val bmp = toBitmap(frame)

        // CENTER_INSIDE: cała klatka widoczna, pasy po bokach lub u góry.
        val scale = min(c.width.toFloat() / frame.width, c.height.toFloat() / frame.height)
        val dw = frame.width * scale
        val dh = frame.height * scale
        val left = (c.width - dw) / 2
        val top = (c.height - dh) / 2
        c.drawBitmap(bmp, Rect(0, 0, frame.width, frame.height), RectF(left, top, left + dw, top + dh), bitmapPaint)

        // Mały znacznik w prawym dolnym rogu — lewy dół zajmuje pasek telemetrii w UI.
        val label = "SYMULACJA · gimbal ${sim.gimbalPitchDeg.toInt()}°"
        val tw = small.measureText(label)
        val x = left + dw - tw - 20f
        val y = top + dh - 18f
        c.drawRect(x - 8f, y - 26f, x + tw + 8f, y + 8f, textBg)
        c.drawText(label, x, y, small)
    }

    /** Płaszczyzna Y klatki NV21 → bitmapa w skali szarości (bufor wielokrotnego użytku). */
    private fun toBitmap(frame: VideoFrame): Bitmap {
        val w = frame.width
        val h = frame.height
        var bmp = bitmap
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            pixels = IntArray(w * h)
        }
        val y = frame.data
        for (i in 0 until w * h) {
            val v = y[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp!!.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}
