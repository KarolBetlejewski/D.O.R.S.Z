package com.example.djicontroller.core.model

import kotlin.math.hypot

/**
 * Położenie środka lądowiska (markera ArUco) względem drona, przy kamerze skierowanej w dół.
 * Wynik `solvePnP` przeliczony z układu kamery na układ BODY drona (sekcja 5.4).
 *
 * @property markerId identyfikator ArUco, z którego policzono pozę — duży marker (zasięg 8–10 m)
 *   albo mały w jego środku (ostatnia faza podejścia, poniżej 1–2 m)
 * @property dxM przesunięcie środka lądowiska do przodu względem drona (+ = przed dronem), m
 * @property dyM przesunięcie środka lądowiska w prawo (+ = na prawo od drona), m
 * @property dzM wysokość kamery nad płaszczyzną markera, m (zawsze > 0)
 * @property yawDeg błąd kursu: o ile trzeba obrócić drona, żeby jego przód pokrył się z osią „góra”
 *   markera; + = obrót w prawo (zgodnie z ruchem wskazówek zegara, widok z góry)
 * @property timestampMs czas monotoniczny odbioru klatki, z której pochodzi obserwacja, ms
 * @property latencyMs wiek klatki w chwili zakończenia detekcji (odbiór → wynik), ms — test R4
 * @property reprojectionErrorPx średni błąd reprojekcji narożników — miara jakości detekcji
 * @property coarse poza zgrubna: `solvePnP` odrzucony (zbyt duży błąd reprojekcji), położenie policzone
 *   ze środka markera w pikselach i jego rozmiaru — środek i kurs są wiarygodne, wysokość ±5–10 %
 */
data class MarkerObservation(
    val markerId: Int,
    val dxM: Double,
    val dyM: Double,
    val dzM: Double,
    val yawDeg: Double,
    val timestampMs: Long,
    val latencyMs: Long? = null,
    val reprojectionErrorPx: Double? = null,
    val coarse: Boolean = false,
) {
    val horizontalErrorM: Double get() = hypot(dxM, dyM)

    fun ageMs(nowMonotonicMs: Long): Long = nowMonotonicMs - timestampMs
}

/** Punkt w pikselach klatki źródłowej (0,0 = lewy górny róg). */
data class PixelPoint(val x: Float, val y: Float)

/**
 * Jeden zdekodowany marker w klatce — do nakładki w UI i do logów.
 * @property corners narożniki w kolejności ArUco: lewy górny, prawy górny, prawy dolny, lewy dolny
 * @property observation poza wyliczona z tego markera; `null`, gdy `solvePnP` się nie powiódł
 *   albo marker nie należy do konfiguracji lądowiska
 */
data class DetectedMarker(
    val id: Int,
    val corners: List<PixelPoint>,
    val sizeM: Double?,
    val observation: MarkerObservation?,
)

/**
 * Wynik przetworzenia jednej klatki przez :vision.
 * @property selected obserwacja wybrana do sterowania (najmniejszy poprawny marker), `null` = brak markera
 * @property detectionMs czas samej detekcji i estymacji pozy, ms
 * @property frameAgeMs wiek klatki po zakończeniu detekcji (odbiór klatki → wynik), ms
 */
data class VisionFrameResult(
    val frameTimestampMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val markers: List<DetectedMarker>,
    val selected: MarkerObservation?,
    val detectionMs: Long,
    val frameAgeMs: Long,
    val source: String,
    /** Błąd OpenCV w tej klatce (np. w drugim przebiegu detekcji); `null` = bez błędów. */
    val error: String? = null,
)

/** Statystyki potoku wizyjnego od ostatniego resetu (test R4). */
data class VisionStats(
    val frames: Int = 0,
    val framesWithMarker: Int = 0,
    val fps: Double = 0.0,
    val detectionMsMean: Double = 0.0,
    val detectionMsP95: Long = 0,
    val frameAgeMsMean: Double = 0.0,
    val frameAgeMsP95: Long = 0,
)

enum class FrameFormat { NV21, YUV420_888, RGBA_8888 }

/**
 * Klatka wideo z `CameraStreamManager.addFrameListener` (albo z testowego źródła).
 * Bufor [data] jest własnością klatki (kopia zrobiona w adapterze), więc konsument
 * może go przetwarzać na innym wątku.
 */
class VideoFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val format: FrameFormat,
    /** Czas monotoniczny odbioru klatki (w aplikacji: `SystemClock.elapsedRealtime()`), ms. */
    val timestampMs: Long,
) {
    override fun toString(): String = "VideoFrame(${width}x$height $format, ${data.size} B, t=$timestampMs)"
}
