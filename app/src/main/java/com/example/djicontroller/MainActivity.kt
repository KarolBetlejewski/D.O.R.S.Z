package com.example.djicontroller

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.djicontroller.core.logging.system
import com.example.djicontroller.service.PatrolService
import com.example.djicontroller.ui.DiagnosticsScreen
import com.example.djicontroller.ui.PatrolScreen
import com.example.djicontroller.ui.PatrolTheme

/**
 * Jedyne Activity (sekcja 3.1). Pełny ekran w poziomie, ekran zawsze włączony,
 * prośby o uprawnienia jak w DJIMainActivity + powiadomienia i wyłączenie optymalizacji baterii.
 */
class MainActivity : ComponentActivity() {

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        appContainer.logger.system("permissions", fields = result.mapKeys { it.key.substringAfterLast('.') })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        requestRuntimePermissions()
        PatrolService.start(this)
        requestIgnoreBatteryOptimizations()

        setContent {
            PatrolTheme {
                // Jedno Activity, dwa ekrany: główny (D1) i Diagnostyka (F2). Stan przetrwa obrót ekranu.
                var screen by rememberSaveable { mutableStateOf(AppScreen.MAIN) }
                BackHandler(enabled = screen == AppScreen.DIAGNOSTICS) { screen = AppScreen.MAIN }
                when (screen) {
                    AppScreen.MAIN -> PatrolScreen(onOpenDiagnostics = { screen = AppScreen.DIAGNOSTICS })
                    AppScreen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { screen = AppScreen.MAIN })
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }

    /** Bez tego system może uśpić aplikację między cyklami (Doze) mimo usługi pierwszoplanowej. */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")),
            )
        } catch (e: RuntimeException) {
            appContainer.logger.system("battery_optimization_prompt_failed", fields = mapOf("error" to e.toString()))
        }
    }
}

private enum class AppScreen { MAIN, DIAGNOSTICS }
