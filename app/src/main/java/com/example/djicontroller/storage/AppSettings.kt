package com.example.djicontroller.storage

import android.content.Context
import com.example.djicontroller.core.model.StickAxes
import com.example.djicontroller.vision.LandingPadLayout

/**
 * Ustawienia diagnostyczne zapamiętywane między uruchomieniami (`SharedPreferences`):
 * mapowanie osi Virtual Stick (wynik testu osi), układ lądowiska, wysokość testu R3.
 * Odczyt synchroniczny przy starcie aplikacji; zapis przez `apply()`.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("patrol_settings", Context.MODE_PRIVATE)

    var stickAxes: StickAxes
        get() = prefs.getString(KEY_AXES, null)?.let { runCatching { StickAxes.valueOf(it) }.getOrNull() } ?: StickAxes.ROLL_FORWARD
        set(value) { prefs.edit().putString(KEY_AXES, value.name).apply() }

    var padLayout: LandingPadLayout
        get() = prefs.getString(KEY_LAYOUT, null)?.let { name -> LandingPadLayout.PRESETS.firstOrNull { it.name == name } }
            ?: LandingPadLayout.STATION
        set(value) { prefs.edit().putString(KEY_LAYOUT, value.name).apply() }

    var hoverHeightM: Double
        get() = prefs.getFloat(KEY_HOVER_HEIGHT, 3.0f).toDouble()
        set(value) { prefs.edit().putFloat(KEY_HOVER_HEIGHT, value.toFloat()).apply() }

    var autoConfirmLanding: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONFIRM, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_CONFIRM, value).apply() }

    private companion object {
        const val KEY_AXES = "vs_axes"
        const val KEY_LAYOUT = "pad_layout"
        const val KEY_HOVER_HEIGHT = "r3_height"
        const val KEY_AUTO_CONFIRM = "auto_confirm_landing"
    }
}
