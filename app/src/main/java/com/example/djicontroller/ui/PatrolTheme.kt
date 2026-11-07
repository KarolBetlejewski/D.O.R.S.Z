package com.example.djicontroller.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Kolory z dokumentu architektury (akcent pomarańczowy) na ciemnym tle — czytelne w słońcu i w nocy. */
object PatrolColors {
    val Accent = Color(0xFFE8792B)
    val AccentSoft = Color(0xFF3A2516)
    val Ok = Color(0xFF3DBE7A)
    val OkSoft = Color(0xFF15301F)
    val Warn = Color(0xFFF2B33D)
    val Stop = Color(0xFFD7372D)
    val Blue = Color(0xFF5B9BE0)
    val Background = Color(0xFF111316)
    val Panel = Color(0xFF1C1F24)
    val PanelBorder = Color(0xFF2C3036)
    val TextMuted = Color(0xFF9AA3AD)
}

@Composable
fun PatrolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PatrolColors.Accent,
            onPrimary = Color.Black,
            secondary = PatrolColors.Blue,
            error = PatrolColors.Stop,
            background = PatrolColors.Background,
            surface = PatrolColors.Panel,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}
