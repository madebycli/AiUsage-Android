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
    dynamicColor: Boolean = false,
    blackColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            primary = Color(0xFFB8C5ED), onPrimary = Color(0xFF20304F),
            primaryContainer = Color(0xFF333D55), onPrimaryContainer = Color(0xFFE0E7FF),
            secondary = Color(0xFFC0C5D8), onSecondary = Color(0xFF292F40),
            secondaryContainer = Color(0xFF343A4B), onSecondaryContainer = Color(0xFFE1E5F5),
            tertiary = Color(0xFFD7BFEA), onTertiary = Color(0xFF392B46),
            tertiaryContainer = Color(0xFF42364F), onTertiaryContainer = Color(0xFFF0DFFF),
            background = Color(0xFF0C0E12), onBackground = Color(0xFFE4E5EE),
            surface = Color(0xFF0C0E12), onSurface = Color(0xFFE4E5EE),
            onSurfaceVariant = Color(0xFFAFB1C0), outlineVariant = Color(0xFF30323D),
            surfaceContainerLowest = Color(0xFF0A0C10), surfaceContainerLow = Color(0xFF14161C),
            surfaceContainer = Color(0xFF1B1D24), surfaceContainerHigh = Color(0xFF252832),
            surfaceContainerHighest = Color(0xFF30333E)
        )
        else -> lightColorScheme(
            primary = Color(0xFF4A5D8A), onPrimary = Color.White,
            primaryContainer = Color(0xFFDBE4FF), onPrimaryContainer = Color(0xFF263758),
            secondary = Color(0xFF565E76), onSecondary = Color.White,
            secondaryContainer = Color(0xFFE0E5F6), onSecondaryContainer = Color(0xFF32394F),
            tertiary = Color(0xFF74588C), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFF0DFFF), onTertiaryContainer = Color(0xFF463253),
            background = Color(0xFFF8F9FD), onBackground = Color(0xFF1D2029),
            surface = Color(0xFFF8F9FD), onSurface = Color(0xFF1D2029),
            onSurfaceVariant = Color(0xFF5D6171), outlineVariant = Color(0xFFD5D9E5),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F2F8),
            surfaceContainer = Color(0xFFE9EDF5), surfaceContainerHigh = Color(0xFFE3E7F1),
            surfaceContainerHighest = Color(0xFFDCE1EC)
        )
    }
    MaterialExpressiveTheme(
        typography = UsageTypography,
        colorScheme = if (blackColors && darkTheme) colors.copy(background = Color.Black, surface = Color.Black) else colors,
        content = content
    )
}
