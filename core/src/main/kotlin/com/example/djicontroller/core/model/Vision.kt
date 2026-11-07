package com.example.djicontroller.core.model

import kotlin.math.hypot

/**
 * Położenie markera ArUco względem drona, przy kamerze skierowanej w dół (gimbal −90°).
 * Wynik `solvePnP` przeliczony z układu kamery na układ BODY drona (sekcja 5.4).
 *
 * @property markerId identyfikator ArUco — duży marker (zasięg 8–10 m) i mały w środku (ostatnie centymetry)
 * @property dxM przesunięcie markera do przodu względem drona (+ = marker przed dronem), m
 * @property dyM przesunięcie markera w prawo (+ = marker na prawo od drona), m
 * @property dzM odległość w pionie od kamery do płaszczyzny markera, m (zawsze > 0)
 * @property yawDeg obrót markera względem osi drona, stopnie (+ = zgodnie z ruchem wskazówek zegara, widok z góry)
 * @property timestampMs czas monotoniczny klatki, z której pochodzi obserwacja, ms
 * @property reprojectionErrorPx średni błąd reprojekcji narożników — miara jakości detekcji
 */
data class MarkerObservation(
    val markerId: Int,
    val dxM: Double,
    val dyM: Double,
    val dzM: Double,
    val yawDeg: Double,
    val timestampMs: Long,
    val reprojectionErrorPx: Double? = null,
) {
    val horizontalErrorM: Double get() = hypot(dxM, dyM)

    fun ageMs(nowMonotonicMs: Long): Long = nowMonotonicMs - timestampMs
}

enum class FrameFormat { NV21, YUV420_888, RGBA_8888 }

/**
 * Klatka wideo z `CameraStreamManager.addFrameListener`. Bufor [data] jest własnością klatki
 * (kopia zrobiona w adapterze), więc konsument może go przetwarzać na innym wątku.
 */
class VideoFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val format: FrameFormat,
    /** Czas monotoniczny odbioru klatki, ms. */
    val timestampMs: Long,
) {
    override fun toString(): String = "VideoFrame(${width}x$height $format, ${data.size} B, t=$timestampMs)"
}
