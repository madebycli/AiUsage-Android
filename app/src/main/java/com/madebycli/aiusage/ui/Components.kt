@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
internal val screenPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp)
internal val maxContentWidth = 680.dp

@Composable
internal fun SoftCard(contentPadding: PaddingValues = PaddingValues(20.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Rows without their own padding, separated by hairlines; used for menus. */
@Composable
internal fun MenuCard(content: @Composable ColumnScope.() -> Unit) = SoftCard(PaddingValues(0.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp), content = content)
}

@Composable
internal fun MenuDivider() = HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))

@Composable
internal fun MenuRow(title: String, icon: ImageVector, open: () -> Unit) {
    Surface(onClick = open, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp, 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Row(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.size(4.dp, 22.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun Notice(text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(18.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
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
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
internal fun BrandIcon(provider: String, modifier: Modifier = Modifier.size(28.dp)) {
    Icon(painterResource(if (provider == Providers.CODEX) R.drawable.ic_brand_codex else R.drawable.ic_brand_claude),
        contentDescription = null, modifier = modifier,
        tint = if (provider == Providers.CODEX) MaterialTheme.colorScheme.onSurface else Color(0xFFD58C70))
}

@Composable
internal fun BrandTile(provider: String, size: Int = 76) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
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
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = Instant.now().epochSecond; delay(1000) } } }
    return now
}

internal fun percent(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.GERMAN, "%.1f", value)
internal fun date(at: Long, seconds: Boolean = false): String = DateTimeFormatter.ofPattern(if (seconds) "dd.MM. · HH:mm:ss" else "dd.MM. · HH:mm", Locale.GERMAN)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(at))
internal fun countdown(seconds: Long): String = when {
    seconds >= 86400 -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    else -> "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}
internal fun age(seconds: Long): String = when { seconds < 60 -> "vor ${seconds.coerceAtLeast(0)} s"; seconds < 3600 -> "vor ${seconds / 60} min"; else -> "vor ${seconds / 3600} h" }
internal fun number(value: Double, maxFraction: Int = 2): String =
    java.text.NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = maxFraction }.format(value)
