package com.example.djicontroller.vision

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Położenie środka lądowiska względem drona w układzie BODY (patrz `MarkerObservation`). */
data class BodyPose(val forwardM: Double, val rightM: Double, val heightM: Double, val yawDeg: Double)

/**
 * Przeliczenie wyniku `solvePnP` (układ kamery OpenCV: x w prawo, y w dół obrazu, z wzdłuż osi optycznej)
 * na układ BODY drona. Czysty Kotlin — testowany na JVM bez OpenCV.
 *
 * Założenia (gimbal w trybie podążania za kursem, sekcja 5.4):
 * - przy pochyleniu −90° oś optyczna patrzy pionowo w dół, a góra obrazu to przód drona,
 *   więc: przód = −y, prawo = x, wysokość = z;
 * - przy pochyleniu `p` ≠ −90° kamera jest odchylona od pionu o `δ = p + 90°` w stronę przodu —
 *   obracamy wynik o `δ` wokół osi x kamery (stabilizacja gimbala kompensuje przechył drona).
 */
object PoseConversion {

    /**
     * @param tvec położenie środka markera w układzie kamery, m (wynik `solvePnP`)
     * @param rotation macierz obrotu marker→kamera 3×3, wierszami (z `Rodrigues(rvec)`)
     * @param padOffsetRightM położenie środka MARKERA względem środka lądowiska, oś „w prawo” markera, m
     * @param padOffsetUpM jw., oś „w górę” markera (w stronę jego górnej krawędzi), m
     * @param gimbalPitchDeg bieżące pochylenie gimbala (−90 = w dół); `null` = przyjmujemy −90
     */
    fun toBody(
        tvec: DoubleArray,
        rotation: DoubleArray,
        padOffsetRightM: Double = 0.0,
        padOffsetUpM: Double = 0.0,
        gimbalPitchDeg: Double? = null,
    ): BodyPose {
        require(tvec.size == 3 && rotation.size == 9) { "tvec 3, rotation 3×3" }
        // Środek lądowiska = środek markera + R·(−offset) (offset w płaszczyźnie markera, z = 0).
        val px = tvec[0] - (rotation[0] * padOffsetRightM + rotation[1] * padOffsetUpM)
        val py = tvec[1] - (rotation[3] * padOffsetRightM + rotation[4] * padOffsetUpM)
        val pz = tvec[2] - (rotation[6] * padOffsetRightM + rotation[7] * padOffsetUpM)

        val delta = Math.toRadians((gimbalPitchDeg ?: -90.0) + 90.0)
        val c = cos(delta)
        val s = sin(delta)

        val forward = -py * c + pz * s
        val height = pz * c + py * s
        val right = px

        // Oś „góra” markera (kolumna 1 macierzy obrotu) w BODY → błąd kursu.
        val upFwd = -rotation[4] * c + rotation[7] * s
        val upRight = rotation[1]
        val yaw = Math.toDegrees(atan2(upRight, upFwd))

        return BodyPose(forwardM = forward, rightM = right, heightM = height, yawDeg = yaw)
    }

    /**
     * Poza zgrubna z samych narożników markera — gdy `solvePnP` daje zbyt duży błąd reprojekcji
     * (mały marker w obrazie, kilka pikseli na pole: narożniki są zaszumione, ale ID jest zdekodowane).
     *
     * Środek markera to średnia narożników (kierunek do markera nie zależy od dokładności pojedynczego
     * narożnika), odległość z rozmiaru: `z = f · bok_m / bok_px`, oś „góra” markera z krawędzi lewej i prawej.
     * Zakłada kamerę patrzącą prawie pionowo w dół (lądowanie); dalej jak [toBody].
     *
     * @param corners 8 współrzędnych pikseli w kolejności ArUco: lewy górny, prawy górny, prawy dolny, lewy dolny
     * @return `null`, gdy narożniki są zdegenerowane
     */
    fun fromCorners(
        corners: FloatArray,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        markerSizeM: Double,
        padOffsetRightM: Double = 0.0,
        padOffsetUpM: Double = 0.0,
        gimbalPitchDeg: Double? = null,
    ): BodyPose? {
        require(corners.size == 8) { "4 narożniki" }
        fun x(k: Int) = corners[2 * k].toDouble()
        fun y(k: Int) = corners[2 * k + 1].toDouble()
        var side = 0.0
        for (k in 0 until 4) side += hypot(x((k + 1) % 4) - x(k), y((k + 1) % 4) - y(k))
        side /= 4
        if (!side.isFinite() || side < 2.0 || markerSizeM <= 0) return null

        val u = (x(0) + x(1) + x(2) + x(3)) / 4
        val v = (y(0) + y(1) + y(2) + y(3)) / 4
        val z = (fx + fy) / 2 * markerSizeM / side
        val t = doubleArrayOf((u - cx) / fx * z, (v - cy) / fy * z, z)

        // Oś „prawo” markera: lewy→prawy narożnik (górna i dolna krawędź); oś „góra”: dolny→górny.
        var rx = (x(1) - x(0)) + (x(2) - x(3))
        var ry = (y(1) - y(0)) + (y(2) - y(3))
        var ux = (x(0) - x(3)) + (x(1) - x(2))
        var uy = (y(0) - y(3)) + (y(1) - y(2))
        val rn = hypot(rx, ry)
        val un = hypot(ux, uy)
        if (rn < 1e-9 || un < 1e-9) return null
        rx /= rn; ry /= rn; ux /= un; uy /= un
        // Marker na ziemi, kamera w dół: osie markera leżą w płaszczyźnie obrazu, normalna = −z kamery.
        val rotation = doubleArrayOf(
            rx, ux, 0.0,
            ry, uy, 0.0,
            0.0, 0.0, -1.0,
        )
        return toBody(t, rotation, padOffsetRightM, padOffsetUpM, gimbalPitchDeg)
    }
}
