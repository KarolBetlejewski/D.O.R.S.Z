package com.example.djicontroller.vision

import org.opencv.android.OpenCVLoader

/** Jednorazowe ładowanie bibliotek natywnych OpenCV (`libopencv_java4.so` z AAR `org.opencv:opencv`). */
object OpenCvRuntime {
    @Volatile
    private var loaded = false

    /** Zwraca `true`, gdy OpenCV jest gotowe. Bezpieczne do wołania wielokrotnie i z wielu wątków. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (!loaded) loaded = OpenCVLoader.initLocal()
        }
        return loaded
    }
}
