package com.sleepguard.poc.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.sleepguard.poc.R

// Variable TTFs bundled in res/font. Weights are synthesized by Compose where needed.
val Rubik = FontFamily(Font(R.font.rubik))         // headings / display / big numbers
val Assistant = FontFamily(Font(R.font.assistant)) // body / labels

private val base = Typography()

/** Rubik for display/headline/title; Assistant for body/label. */
val SgTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = Rubik),
    displayMedium = base.displayMedium.copy(fontFamily = Rubik),
    displaySmall = base.displaySmall.copy(fontFamily = Rubik),
    headlineLarge = base.headlineLarge.copy(fontFamily = Rubik),
    headlineMedium = base.headlineMedium.copy(fontFamily = Rubik),
    headlineSmall = base.headlineSmall.copy(fontFamily = Rubik),
    titleLarge = base.titleLarge.copy(fontFamily = Rubik),
    titleMedium = base.titleMedium.copy(fontFamily = Rubik),
    titleSmall = base.titleSmall.copy(fontFamily = Rubik),
    bodyLarge = base.bodyLarge.copy(fontFamily = Assistant),
    bodyMedium = base.bodyMedium.copy(fontFamily = Assistant),
    bodySmall = base.bodySmall.copy(fontFamily = Assistant),
    labelLarge = base.labelLarge.copy(fontFamily = Assistant),
    labelMedium = base.labelMedium.copy(fontFamily = Assistant),
    labelSmall = base.labelSmall.copy(fontFamily = Assistant),
)
