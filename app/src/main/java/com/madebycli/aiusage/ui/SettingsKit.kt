/* Settings layout as in Iris (madebycli/iris-android, presentation/settings/SettingsComponents.kt and
 * components/orion/OrionPrimitives.kt), which adapts Orion Store ui/Settings.kt Page and ui/Common.kt,
 * RookieEnough and contributors, GPL-3.0, e561e91. AI Usage keeps the 0.9.1 panel colour for rows.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.madebycli.aiusage.R

/** The accent of the settings page on screen; section labels take their tone from it (Orion's LocalAccent). */
internal val LocalSettingsAccent = compositionLocalOf<Color?> { null }

/**
 * The tinted hero a page opens on: a large shape turning in the corner, the icon on a smaller one, the title in display type.
 * It scrolls away with the page instead of staying pinned; [top] holds the back button and actions.
 */
@Composable
internal fun OrionPageHero(icon: ImageVector, title: String, desc: String, accent: Color, shape: RoundedPolygon, modifier: Modifier = Modifier,
    topInset: Dp = 0.dp, top: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = orionTonal(accent)
    Box(modifier.fillMaxWidth().clipToBounds().background(Brush.verticalGradient(listOf(bg, cs.background)))) {
        OrionSpinner(shape, fg.copy(.08f), 260.dp, Modifier.align(Alignment.TopEnd).offset(70.dp, (-30).dp), periodMs = 40_000)
        Column(Modifier.padding(top = topInset, bottom = 12.dp)) {
            top()
            Box(Modifier.padding(start = 24.dp, top = 8.dp).size(72.dp), contentAlignment = Alignment.Center) {
                OrionSpinner(shape, fg.copy(.16f), 72.dp, periodMs = 18_000)
                Icon(icon, null, Modifier.size(34.dp), tint = fg)
            }
            Text(title, Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp), style = MaterialTheme.typography.displaySmallEmphasized,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = MaterialTheme.typography.headlineSmall.fontSize,
                    maxFontSize = MaterialTheme.typography.displaySmallEmphasized.fontSize))
            if (desc.isNotEmpty()) Text(desc, Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * A settings page laid out like Orion Page: the tinted hero, then the content; everything scrolls together, hero
 * included, so nothing stays pinned at the top. [sharedKey] lets the hub tile grow into the page.
 */
@Composable
internal fun SettingsPageScaffold(icon: ImageVector, title: String, desc: String, accent: Color, shape: RoundedPolygon, onBack: () -> Unit,
    modifier: Modifier = Modifier, sharedKey: String? = null, actions: @Composable RowScope.() -> Unit = {}, content: LazyListScope.() -> Unit) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    CompositionLocalProvider(LocalSettingsAccent provides accent) {
        LazyColumn(
            modifier.orionSharedBounds(sharedKey).fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(),
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp),
        ) {
            item(key = "hero") {
                OrionPageHero(icon, title, desc, accent, shape, topInset = statusBar) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                        Spacer(Modifier.weight(1f))
                        actions()
                    }
                }
            }
            content()
        }
    }
}

/** One block of page content with the Iris side margins and the staggered entrance. */
internal fun LazyListScope.settingsItem(key: String, index: Int = 1, content: @Composable ColumnScope.() -> Unit) = item(key = key) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).widthIn(max = maxContentWidth).orionReveal(index),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Small section label above a [SettingsGroup], in the page accent. */
@Composable
internal fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp), style = MaterialTheme.typography.titleSmallEmphasized,
        color = LocalSettingsAccent.current?.let { orionTonal(it).second } ?: MaterialTheme.colorScheme.primary)
}

/** Rows joined into one rounded block with 2 dp gaps. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
}

@Composable
internal fun SettingsItem(title: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null,
    enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, titleColor: Color = Color.Unspecified, trailing: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                leading != null -> Box(Modifier.padding(end = 16.dp)) { leading() }
                icon != null -> Box(Modifier.padding(end = 14.dp).size(40.dp).clip(RoundedCornerShape(14.dp))
                    .background(cs.primaryContainer.copy(alpha = if (enabled) .55f else .2f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = if (enabled) cs.primary else cs.onSurfaceVariant.copy(alpha = .38f))
                }
            }
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = if (!enabled) cs.onSurface.copy(alpha = .6f) else if (titleColor != Color.Unspecified) titleColor else cs.onSurface)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant.copy(alpha = if (enabled) 1f else .6f))
            }
            Box(contentAlignment = Alignment.Center) { trailing() }
        }
    }
    if (onClick != null) Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(4.dp), color = cs.panel, modifier = modifier.fillMaxWidth()) { row() }
    else Surface(shape = RoundedCornerShape(4.dp), color = cs.panel, modifier = modifier.fillMaxWidth()) { row() }
}

@Composable
internal fun SwitchSettingsItem(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null,
    icon: ImageVector? = null, enabled: Boolean = true) =
    SettingsItem(title, { onChange(!checked) }, modifier, subtitle, icon, enabled) { Switch(checked, onChange, enabled = enabled, colors = appSwitchColors()) }

@Composable
internal fun RadioSettingsItem(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null,
    icon: ImageVector? = null, enabled: Boolean = true) =
    SettingsItem(title, onClick, modifier.semantics { role = Role.RadioButton }, subtitle, icon, enabled) { RadioButton(selected, null, enabled = enabled) }

/** A row that opens a sub-screen: only these get the chevron. */
@Composable
internal fun NavigationSettingsItem(title: String, subtitle: String?, icon: ImageVector?, onClick: () -> Unit) =
    SettingsItem(title, onClick, subtitle = subtitle, icon = icon) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }

/** A row-shaped block that holds pickers or status instead of a single setting. */
@Composable
internal fun SettingsBlock(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.panel, shape = RoundedCornerShape(4.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** One option of a visual picker: tonal container and a 2 dp outline when selected. */
@Composable
internal fun SettingsChoiceTile(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(if (selected) cs.primaryContainer else cs.panelRaised, label = "choice")
    val border by animateColorAsState(if (selected) cs.primary else Color.Transparent, label = "choice-border")
    Surface(selected = selected, onClick = onClick, shape = shape, color = container,
        contentColor = if (selected) cs.onPrimaryContainer else cs.onSurface, border = BorderStroke(2.dp, border),
        modifier = modifier.semantics { role = Role.RadioButton }) { content() }
}

@Composable
internal fun appSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline)

/** Label and value on two lines, for status inside a [SettingsBlock]. */
@Composable
internal fun StatusLine(title: String, value: String, valueColor: Color = Color.Unspecified) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

// region shape morph (Orion ui/Common.kt MorphShape, MorphBlob)

/** Live morph between two normalized MaterialShapes polygons. */
internal class OrionMorphShape(private val morph: Morph, private val progress: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): Outline {
        val p = morph.toPath(progress); p.transform(android.graphics.Matrix().apply { setScale(size.width, size.height) })
        return Outline.Generic(p.asComposePath())
    }
}

/** Morphs to shapes[index] with the expressive slow spatial spring and a quarter turn. */
@Composable
internal fun OrionMorphBlob(shapes: List<RoundedPolygon>, index: Int, size: Dp, color: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var from by remember { mutableIntStateOf(index) }; var to by remember { mutableIntStateOf(index) }
    val progress = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(index) { if (index != to) { from = to; to = index; progress.snapTo(0f); progress.animateTo(1f, spec) } }
    val morph = remember(from, to) { Morph(shapes[from % shapes.size], shapes[to % shapes.size]) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().graphicsLayer { rotationZ = (to + progress.value - 1) * 90f }.background(color, OrionMorphShape(morph, progress.value)))
        content()
    }
}
// endregion
