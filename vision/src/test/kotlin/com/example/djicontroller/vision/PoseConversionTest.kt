package com.example.djicontroller.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PoseConversionTest {

    private val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /**
     * Macierz obrotu marker→kamera dla kamery patrzącej w dół, gdy oś „góra” markera jest obrócona
     * o [yawDeg] względem przodu drona (dodatni = w prawo). Kolumna 1 = oś góra markera w kamerze.
     */
    private fun markerRotation(yawDeg: Double): DoubleArray {
        val a = Math.toRadians(yawDeg)
        // Oś „góra” markera w BODY: (przód cos a, prawo sin a) → w kamerze: x = sin a, y = −cos a.
        // Oś „prawo” markera: (przód −sin a, prawo cos a) → x = cos a, y = sin a. Oś z markera → −z kamery.
        return doubleArrayOf(
            cos(a), sin(a), 0.0,
            sin(a), -cos(a), 0.0,
            0.0, 0.0, -1.0,
        )
    }

    @Test
    fun `kamera w dol - przod to minus y, prawo to x`() {
        // Marker 1 m przed dronem (góra obrazu = −y) i 0,5 m w lewo (−x), 5 m pod kamerą.
        val p = PoseConversion.toBody(doubleArrayOf(-0.5, -1.0, 5.0), markerRotation(0.0))
        assertEquals(1.0, p.forwardM, 1e-9)
        assertEquals(-0.5, p.rightM, 1e-9)
        assertEquals(5.0, p.heightM, 1e-9)
        assertEquals(0.0, p.yawDeg, 1e-9)
    }

    @Test
    fun `blad kursu ma znak obrotu w prawo`() {
        assertEquals(30.0, PoseConversion.toBody(doubleArrayOf(0.0, 0.0, 3.0), markerRotation(30.0)).yawDeg, 1e-9)
        assertEquals(-90.0, PoseConversion.toBody(doubleArrayOf(0.0, 0.0, 3.0), markerRotation(-90.0)).yawDeg, 1e-9)
    }

    @Test
    fun `przesuniecie markera od srodka ladowiska`() {
        // Marker leży 0,2 m „w prawo” od środka lądowiska; marker dokładnie pod kamerą, osie zgodne.
        val p = PoseConversion.toBody(doubleArrayOf(0.0, 0.0, 2.0), markerRotation(0.0), padOffsetRightM = 0.2)
        // Środek lądowiska jest więc 0,2 m w lewo od drona.
        assertEquals(-0.2, p.rightM, 1e-9)
        assertEquals(0.0, p.forwardM, 1e-9)
    }

    @Test
    fun `przesuniecie przy obroconym markerze`() {
        // Marker obrócony o 90° w prawo: jego oś „góra” wskazuje prawo drona.
        val p = PoseConversion.toBody(doubleArrayOf(0.0, 0.0, 2.0), markerRotation(90.0), padOffsetUpM = 0.3)
        // Środek lądowiska leży 0,3 m „w dół” markera = w lewo drona.
        assertEquals(-0.3, p.rightM, 1e-9)
        assertEquals(0.0, p.forwardM, 1e-9)
    }

    @Test
    fun `odchylenie gimbala od pionu`() {
        // Gimbal −80°: kamera odchylona 10° do przodu. Punkt na osi optycznej w odległości 5 m
        // leży 5·sin10° przed dronem i 5·cos10° niżej.
        val p = PoseConversion.toBody(doubleArrayOf(0.0, 0.0, 5.0), identity, gimbalPitchDeg = -80.0)
        assertEquals(5 * sin(Math.toRadians(10.0)), p.forwardM, 1e-9)
        assertEquals(5 * cos(Math.toRadians(10.0)), p.heightM, 1e-9)
    }

    @Test
    fun `brak pochylenia oznacza kamere w dol`() {
        val a = PoseConversion.toBody(doubleArrayOf(0.3, -0.2, 4.0), identity, gimbalPitchDeg = null)
        val b = PoseConversion.toBody(doubleArrayOf(0.3, -0.2, 4.0), identity, gimbalPitchDeg = -90.0)
        assertEquals(a, b)
    }
}
