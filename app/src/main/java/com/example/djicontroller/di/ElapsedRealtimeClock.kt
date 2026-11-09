package com.example.djicontroller.di

import android.os.SystemClock
import com.example.djicontroller.core.port.Clock

/**
 * Zegar aplikacji: monotoniczny = `SystemClock.elapsedRealtime()` (biegnie także w uśpieniu).
 * Jeden zegar dla klatek kamery, potoku wizyjnego, watchdoga i logów — wiek klatki (R4)
 * liczy się wtedy z jednego źródła czasu.
 */
object ElapsedRealtimeClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
}
