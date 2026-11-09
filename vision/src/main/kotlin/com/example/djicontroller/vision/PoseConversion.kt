package com.example.djicontroller.vision

import kotlin.math.atan2
import kotlin.math.cos
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
}
