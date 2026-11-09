package com.example.djicontroller.ui

import android.view.Surface

/** Cel dla podglądu wideo: w `prod` strumień z drona (MSDK), w `demo` syntetyczny obraz. */
interface VideoPreview {
    /** Wołane z `SurfaceHolder.Callback.surfaceChanged`. */
    fun attach(surface: Surface, width: Int, height: Int)

    /** Wołane z `SurfaceHolder.Callback.surfaceDestroyed` — po powrocie nic nie może rysować na [surface]. */
    fun detach(surface: Surface)
}
