// Adapted from AniHyou by axiel7, GPL-3.0. See docs/UPSTREAM.md.
package com.axiel7.anihyou.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AniHyouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    blackColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            primary = Color(0xFFB0D391), onPrimary = Color(0xFF1D370C),
            primaryContainer = Color(0xFF334E20), onPrimaryContainer = Color(0xFFD4EDBB),
            secondary = Color(0xFFBECAB0), secondaryContainer = Color(0xFF3F4A36),
            tertiary = Color(0xFFA2D0CB), tertiaryContainer = Color(0xFF234E4A),
            background = Color(0xFF12150F), surface = Color(0xFF12150F),
            surfaceContainer = Color(0xFF1E211A), surfaceContainerLow = Color(0xFF1A1D16),
            surfaceContainerHigh = Color(0xFF292C24), surfaceContainerHighest = Color(0xFF33362F)
        )
        else -> lightColorScheme(
            primary = Color(0xFF46652E), onPrimary = Color.White,
            primaryContainer = Color(0xFFD4EDBB), onPrimaryContainer = Color(0xFF263E17),
            secondary = Color(0xFF58624D), secondaryContainer = Color(0xFFDCE6CE),
            tertiary = Color(0xFF366A64), tertiaryContainer = Color(0xFFBAECE4),
            background = Color(0xFFF9FAF4), surface = Color(0xFFF9FAF4),
            surfaceContainer = Color(0xFFEEF1E6), surfaceContainerLow = Color(0xFFF3F5EC),
            surfaceContainerHigh = Color(0xFFE8EBDD), surfaceContainerHighest = Color(0xFFE2E6D8)
        )
    }
    MaterialExpressiveTheme(
        colorScheme = if (blackColors && darkTheme) colors.copy(background = Color.Black, surface = Color.Black) else colors,
        content = content
    )
}
