package com.axiel7.anihyou.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.axiel7.anihyou.core.ui.R

// Google Sans Flex, ROND=100. Static OFL instances support Android 8 without font-axis runtime dependencies.
private val rounded = FontFamily(
    Font(R.font.usage_rounded_regular, FontWeight.Normal),
    Font(R.font.usage_rounded_medium, FontWeight.Medium),
    Font(R.font.usage_rounded_bold, FontWeight.Bold)
)
private val base = Typography()
val UsageTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = rounded),
    displayMedium = base.displayMedium.copy(fontFamily = rounded),
    displaySmall = base.displaySmall.copy(fontFamily = rounded),
    headlineLarge = base.headlineLarge.copy(fontFamily = rounded, fontWeight = FontWeight.Medium),
    headlineMedium = base.headlineMedium.copy(fontFamily = rounded, fontWeight = FontWeight.Medium),
    headlineSmall = base.headlineSmall.copy(fontFamily = rounded, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontFamily = rounded),
    titleMedium = base.titleMedium.copy(fontFamily = rounded),
    titleSmall = base.titleSmall.copy(fontFamily = rounded),
    bodyLarge = base.bodyLarge.copy(fontFamily = rounded),
    bodyMedium = base.bodyMedium.copy(fontFamily = rounded),
    bodySmall = base.bodySmall.copy(fontFamily = rounded),
    labelLarge = base.labelLarge.copy(fontFamily = rounded),
    labelMedium = base.labelMedium.copy(fontFamily = rounded),
    labelSmall = base.labelSmall.copy(fontFamily = rounded)
)
