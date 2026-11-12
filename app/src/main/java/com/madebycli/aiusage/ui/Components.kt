@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.R
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val cardShape = RoundedCornerShape(28.dp)
internal val screenPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp)
internal val maxContentWidth = 680.dp

@Composable
internal fun SoftCard(contentPadding: PaddingValues = PaddingValues(16.dp), modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    OrionGroupItem(0, 1, modifier = modifier) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Orion settings Section/SRow surface: row insets belong to the row, not to a second surrounding card. */
@Composable
internal fun SettingsContent(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** Connected surfaces have real transparent gaps; every segment owns its outline. */
@Composable
internal fun MenuCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
internal fun MenuDivider() = Unit

@Composable
internal fun MenuRow(title: String, icon: ImageVector, subtitle: String? = null, index: Int = 0, count: Int = 1, open: () -> Unit) {
    OrionGroupItem(index, count, onClick = open) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SectionTitle(text: String) = OrionHubHeader(text)

@Composable
internal fun Notice(text: String, error: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.large, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
    }
}

@Composable
internal fun Disclosure(label: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(label); Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val accent = LocalSettingsCategory.current?.accent ?: MaterialTheme.colorScheme.primary
    val (background, foreground) = orionTonal(accent)
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Box(Modifier.size(40.dp).background(background, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.material.icons.Icons.Rounded.Tune, null, Modifier.size(22.dp), tint = foreground)
        } },
        trailingContent = { Switch(checked, null, thumbContent = { Icon(if (checked) androidx.compose.material.icons.Icons.Rounded.Check else androidx.compose.material.icons.Icons.Rounded.Close, null, Modifier.size(SwitchDefaults.IconSize)) }) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall).then(Modifier.toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)))

}

@Composable
internal fun BrandIcon(provider: String, modifier: Modifier = Modifier.size(28.dp)) {
    Icon(painterResource(if (provider == Providers.CODEX) R.drawable.ic_brand_codex else R.drawable.ic_brand_claude),
        contentDescription = null, modifier = modifier,
        tint = if (provider == Providers.CODEX) MaterialTheme.colorScheme.onSurface else Color(0xFFD58C70))
}

/** Reset moment of a window: the clock time first, the remaining time below it. */
@Composable
internal fun ResetLines(resetAt: Long?, now: Long, modifier: Modifier = Modifier, horizontalAlignment: Alignment.Horizontal = Alignment.Start) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp), horizontalAlignment = horizontalAlignment) {
        when {
            resetAt == null -> Text("Reset nicht gemeldet", style = MaterialTheme.typography.labelMedium, color = muted)
            resetAt <= now -> {
                Text("Reset erreicht", style = MaterialTheme.typography.labelLarge)
                Text("seit ${com.madebycli.aiusage.data.TimeText.clock(resetAt, now)} Uhr", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            else -> {
                Text("Reset ${com.madebycli.aiusage.data.TimeText.clock(resetAt, now)} Uhr", style = MaterialTheme.typography.labelLarge)
                Text(com.madebycli.aiusage.data.TimeText.remaining(resetAt - now), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

/**
 * A ring with its text in the middle. The text size follows the ring, so a long value such as "35,2%"
 * always stays inside it. A gap at the bottom keeps the familiar gauge shape when [gauge] is set.
 */
@Composable
internal fun RingGauge(progress: Float, color: Color, text: String, caption: String? = null, size: androidx.compose.ui.unit.Dp = 112.dp,
    stroke: androidx.compose.ui.unit.Dp = 9.dp, gauge: Boolean = false, modifier: Modifier = Modifier) {
    val animated by rememberFillProgress(progress)
    val track = color.copy(alpha = .14f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val inset = width / 2
            val bounds = androidx.compose.ui.geometry.Size(this.size.width - width, this.size.height - width)
            val start = if (gauge) 140f else -90f
            val sweep = if (gauge) 260f else 360f
            drawArc(track, start, sweep, false, Offset(inset, inset), bounds, style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = StrokeCap.Round))
            if (animated > 0f) drawArc(color, start, sweep * animated, false, Offset(inset, inset), bounds, style = androidx.compose.ui.graphics.drawscope.Stroke(width, cap = StrokeCap.Round))
        }
        val factor = .22f // Reserve the same type size for 0% through 100%, including animation frames.
        val fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (size.toPx() * factor).toSp() }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.titleLarge.copy(fontSize = fontSize), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, softWrap = false)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * A 0 to 100 scale with a band from [low] to [high] and a dot at [value]: how far apart a source's own range is.
 */
@Composable
internal fun RangeBar(value: Double, low: Double?, high: Double?, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(14.dp)) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val reveal by rememberFillProgress(1f)
    Canvas(modifier) {
        val h = size.height
        val barHeight = h * .42f
        val top = (h - barHeight) / 2
        drawRoundRect(track, Offset(0f, top), Size(size.width, barHeight), androidx.compose.ui.geometry.CornerRadius(barHeight / 2))
        if (low != null && high != null && high > low) {
            val x0 = (size.width * (low / 100).toFloat()).coerceIn(0f, size.width)
            val x1 = (size.width * (high / 100).toFloat()).coerceIn(0f, size.width)
            drawRoundRect(color.copy(alpha = .35f), Offset(x0, top), Size((x1 - x0).coerceAtLeast(barHeight), barHeight), androidx.compose.ui.geometry.CornerRadius(barHeight / 2))
        }
        val x = (size.width * (value / 100).toFloat() * reveal).coerceIn(h / 2, size.width - h / 2)
        drawCircle(color, h / 2, Offset(x, h / 2))
        drawCircle(Color.White.copy(alpha = .9f), h / 6, Offset(x, h / 2))
    }
}

@Composable
internal fun BrandTile(provider: String, size: Int = 76) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) { BrandIcon(provider, Modifier.size((size / 2).dp)) }
    }
}

@Composable
internal fun ProviderTabs(names: List<String>, active: String, select: (String) -> Unit) {
    SegmentedTabGroup(names, active, select, { it }, fillEqually = true,
        icon = { ImageVector.vectorResource(if (it == Providers.CODEX) R.drawable.ic_brand_codex else R.drawable.ic_brand_claude) })
}

@Composable
internal fun UsageTrack(progress: Float, accent: Color, modifier: Modifier = Modifier.fillMaxWidth().height(8.dp)) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "track")
    Canvas(modifier) {
        drawLine(track, Offset(size.height / 2, center.y), Offset(size.width - size.height / 2, center.y), size.height, StrokeCap.Round)
        if (animated > 0f) drawLine(accent, Offset(size.height / 2, center.y),
            Offset((size.width - size.height) * animated + size.height / 2, center.y), size.height, StrokeCap.Round)
    }
}

@Composable
internal fun rememberNow(): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = Instant.now().epochSecond; delay(10_000) } } }
    return now
}

internal fun percent(value: Double): String = com.madebycli.aiusage.data.formatPercent(value)
internal fun date(at: Long, seconds: Boolean = false): String = DateTimeFormatter.ofPattern(if (seconds) "dd.MM. · HH:mm:ss" else "dd.MM. · HH:mm", Locale.GERMAN)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(at))
internal fun age(seconds: Long): String = when { seconds < 60 -> "vor ${seconds.coerceAtLeast(0)} s"; seconds < 3600 -> "vor ${seconds / 60} min"; else -> "vor ${seconds / 3600} h" }
internal fun number(value: Double, maxFraction: Int = 2): String =
    java.text.NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = maxFraction }.format(value)
