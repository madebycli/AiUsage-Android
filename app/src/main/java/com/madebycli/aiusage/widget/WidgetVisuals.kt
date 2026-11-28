@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.widget

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.madebycli.aiusage.ui.OrionAccents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Icon, accent and shape of each widget kind, used by its settings page and the widget list in the app. */
internal val WidgetKind.icon: ImageVector get() = when (this) {
    WidgetKind.USAGE -> Icons.Rounded.DataUsage; WidgetKind.STATS -> Icons.Rounded.BarChart
    WidgetKind.PLAN -> Icons.Rounded.CalendarMonth; WidgetKind.RESETS -> Icons.Rounded.RestartAlt
}
internal val WidgetKind.accent: Color get() = when (this) {
    WidgetKind.USAGE -> OrionAccents.Sky; WidgetKind.STATS -> OrionAccents.Purple
    WidgetKind.PLAN -> OrionAccents.Green; WidgetKind.RESETS -> OrionAccents.Orange
}
internal val WidgetKind.shape: RoundedPolygon get() = when (this) {
    WidgetKind.USAGE -> MaterialShapes.Cookie9Sided; WidgetKind.STATS -> MaterialShapes.Clover4Leaf
    WidgetKind.PLAN -> MaterialShapes.Sunny; WidgetKind.RESETS -> MaterialShapes.Flower
}
/** The size a new widget of this kind gets in the launcher, in dp, for previews. */
internal val WidgetKind.defaultSize: Pair<Int, Int> get() = when (this) {
    WidgetKind.USAGE -> 250 to 180; WidgetKind.STATS -> 250 to 120; WidgetKind.PLAN -> 250 to 120; WidgetKind.RESETS -> 180 to 180
}

/** A wallpaper-like backdrop, so the widget's rounded shape and translucency read as on the home screen. */
@Composable
internal fun WallpaperBox(modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, content: @Composable BoxScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < .5f }
    val colors = if (dark) listOf(Color(0xFF233049), accent.copy(alpha = .55f).compositeOver(Color(0xFF2B2140)), Color(0xFF151C28))
        else listOf(Color(0xFFA9C2EE), accent.copy(alpha = .45f).compositeOver(Color(0xFFE5D3F3)), Color(0xFFF4DCC8))
    Box(modifier.clip(MaterialTheme.shapes.extraLarge).background(Brush.linearGradient(colors)), contentAlignment = Alignment.Center, content = content)
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

/** The real widget picture for [config], drawn off the main thread and faded in when it changes. */
@Composable
internal fun WidgetPicture(config: WidgetConfig, accounts: List<WidgetAccount>, widthDp: Int, heightDp: Int, modifier: Modifier = Modifier, displayWidth: Dp = widthDp.dp, now: Long? = null) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, config, accounts, widthDp, heightDp) {
        value = withContext(Dispatchers.Default) {
            runCatching { WidgetRenderer.render(context, config, accounts, widthDp, heightDp, now ?: java.time.Instant.now().epochSecond) }.getOrNull()
        }
    }
    val scale = displayWidth.value / widthDp
    Box(modifier.size(displayWidth, (heightDp * scale).dp)) {
        Crossfade(bitmap, label = "widget") { image ->
            if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize())
        }
    }
}
