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
            primary = Color(0xFFD4BCFF), onPrimary = Color(0xFF382451),
            primaryContainer = Color(0xFF493364), onPrimaryContainer = Color(0xFFEEDDFF),
            secondary = Color(0xFFCCC0DA), secondaryContainer = Color(0xFF463D51),
            tertiary = Color(0xFFFFB59D), tertiaryContainer = Color(0xFF633B2E), onTertiaryContainer = Color(0xFFFFDBCF),
            background = Color(0xFF17141C), surface = Color(0xFF17141C),
            surfaceContainer = Color(0xFF25202D), surfaceContainerLow = Color(0xFF201B26),
            surfaceContainerHigh = Color(0xFF302A38), surfaceContainerHighest = Color(0xFF3B3444)
        )
        else -> lightColorScheme(
            primary = Color(0xFF715095), onPrimary = Color.White,
            primaryContainer = Color(0xFFEEDDFF), onPrimaryContainer = Color(0xFF322043),
            secondary = Color(0xFF665A72), secondaryContainer = Color(0xFFEDDFF5),
            tertiary = Color(0xFF8D503C), tertiaryContainer = Color(0xFFFFDBCF), onTertiaryContainer = Color(0xFF3C2119),
            background = Color(0xFFFCF8FF), surface = Color(0xFFFCF8FF),
            surfaceContainer = Color(0xFFF1EAF6), surfaceContainerLow = Color(0xFFF6EFFB),
            surfaceContainerHigh = Color(0xFFECE4F2), surfaceContainerHighest = Color(0xFFE5DDEB)
        )
    }
    MaterialExpressiveTheme(
        colorScheme = if (blackColors && darkTheme) colors.copy(background = Color.Black, surface = Color.Black) else colors,
        content = content
    )
}
