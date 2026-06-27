package com.sleepguard.poc.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val DarkColors = darkColorScheme(
    primary = SgPrimary,
    onPrimary = Color.White,
    background = SgBackground,
    onBackground = SgForeground,
    surface = SgCard,
    onSurface = SgForeground,
    surfaceVariant = SgSurfaceVariant,
    onSurfaceVariant = SgMutedForeground,
    secondary = SgSecondary,
    onSecondary = Color.White,
    outline = SgBorder,
    error = Color(0xFFE5484D),
)

/**
 * App theme: SleepGuard dark palette + Rubik/Assistant typography, laid out RTL (Hebrew).
 * Body text defaults to Assistant via LocalTextStyle; Material components use [SgTypography].
 */
@Composable
fun SleepGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, typography = SgTypography) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Assistant),
            content = content
        )
    }
}
