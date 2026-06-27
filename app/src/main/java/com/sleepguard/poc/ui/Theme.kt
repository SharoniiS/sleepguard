package com.sleepguard.poc.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Minimal dark theme — placeholder palette only. The real visual design comes later; this exists so
 * the screens render coherently. The whole app is laid out RTL (Hebrew UI).
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B8DEF),
    onPrimary = Color.White,
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF141B2E),
    onSurface = Color(0xFFE6ECF5),
    surfaceVariant = Color(0xFF1C2540),
    onSurfaceVariant = Color(0xFFAEB9D0),
)

@Composable
fun SleepGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}
