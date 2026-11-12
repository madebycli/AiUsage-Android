/* Adapted from Orion Store, RookieEnough and contributors, GPL-3.0.
 * Source: e561e91e9409d9d9b437ab7acf919cd115769d7e, ui/Common.kt (springClick, focusRing, reveal, GroupItem, CtaButton,
 * MorphShape, MorphBlob, SectionTitle, EmptyState, TonalIcon).
 * Iris changes: local haptics, reduced motion, explicit enabled/selection state.
 * AI Usage adaptation: provider-neutral UI, Android animation duration scale; no Iris services.
 * See docs/IRIS_DESIGN.md and assets/licenses/LICENSE-Orion-Store.txt.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.madebycli.aiusage.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath


/** Squash-on-press with an expressive spatial spring, plus ripple, clipped to [shape]. */
@Composable
fun Modifier.orionSpringClick(shape: Shape = RoundedCornerShape(20.dp), onLongClick: (() -> Unit)? = null, src: MutableInteractionSource = remember { MutableInteractionSource() }, onClick: () -> Unit): Modifier {
    val pressed by src.collectIsPressedAsState(); val haptic = LocalHapticFeedback.current
    val focused by src.collectIsFocusedAsState()
    // D-pad and keyboard focus (TV remotes) lifts the card a little; touch never focuses, so phones never see it
    val motion = true
    val k by animateFloatAsState(if (pressed) .94f else if (focused) 1.04f else 1f, if (motion) MaterialTheme.motionScheme.fastSpatialSpec() else tween(0), label = "press")
    // Every card and tile in the app routes through here, so this is the one place that gives taps their tick.
    return graphicsLayer { scaleX = k; scaleY = k }.orionFocusRing(src, shape).clip(shape).combinedClickable(src, ripple(), onLongClick = onLongClick, onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() })
}

/** A primary ring around whatever has D-pad or keyboard focus, so a TV remote always shows where it is. */
@Composable
fun Modifier.orionFocusRing(src: androidx.compose.foundation.interaction.InteractionSource, shape: Shape): Modifier {
    val focused by src.collectIsFocusedAsState()
    val w by animateDpAsState(if (focused) 3.dp else 0.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "ring")
    return if (w > 0.dp) border(w, MaterialTheme.colorScheme.primary, shape) else this
}

/** Rise-and-fade entrance for the first screenful, once per item (survives lazy recycling), staggered by [index].
 *  Items further down appear as-is, so flinging never shows rows sliding in. */
@Composable
fun Modifier.orionReveal(index: Int = 0, enabled: Boolean = true): Modifier {
    val motion = true
    if (index > 8 || !enabled || !motion) return this
    var done by rememberSaveable { mutableStateOf(false) }
    val p = remember { Animatable(if (done) 1f else 0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { if (!done) { kotlinx.coroutines.delay(index.coerceIn(0, 8) * 45L); p.animateTo(1f, spec); done = true } }
    return graphicsLayer { alpha = p.value.coerceIn(0f, 1f); translationY = (1f - p.value) * 28.dp.toPx() }
}


/** Segmented list corners: 20dp outer, 4dp inner; a selected row morphs fully round. */
@Composable
fun OrionGroupItem(i: Int, n: Int, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val motion = true
    val spec = if (motion) MaterialTheme.motionScheme.fastSpatialSpec<Dp>() else tween(0)
    val top by animateDpAsState(if (i == 0 || selected) 20.dp else 4.dp, spec, label = "gt")
    val bottom by animateDpAsState(if (i == n - 1 || selected) 20.dp else 4.dp, spec, label = "gb")
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    val color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val m = modifier.fillMaxWidth().padding(bottom = 2.dp)
    if (onClick != null) Surface(onClick, m, shape = shape, color = color) { content() } else Surface(m, shape = shape, color = color) { content() }
}


/** Pastel container + legible icon tone of any accent, so fixed hues still read as M3 tonal pairs in light and dark. Orion ui/Common.kt tonal. */
@Composable
fun orionTonal(c: Color): Pair<Color, Color> {
    val hsl = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(c.toArgb(), it) }
    val light = MaterialTheme.colorScheme.surface.luminance() > .5f
    return Color.hsl(hsl[0], (hsl[1] * .7f).coerceAtMost(.75f), if (light) .88f else .26f) to Color.hsl(hsl[0], hsl[1].coerceAtMost(.8f), if (light) .3f else .82f)
}

/** Decorative MaterialShape that slowly turns; used behind hub tiles and page heroes. Orion ui/Settings.kt Spinner; rests with motion off. */
@Composable
fun OrionSpinner(shape: RoundedPolygon, color: Color, size: Dp, modifier: Modifier = Modifier, periodMs: Int = 24_000) {
    val motion = true
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(0f, if (motion) 360f else 0f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "a")
    Box(modifier.size(size).graphicsLayer { rotationZ = turn }.background(color, shape.toShape()))
}

/**
 * Hub tile in the accent's tonal pair: shape-backed icon, a large turning shape in the corner, title and description at the
 * bottom. Orion ui/Settings.kt Bento; Iris passes texts and the click, and outlines the tile that is open in the two-pane layout.
 */
@Composable
fun OrionBento(title: String, desc: String, icon: ImageVector, accent: Color, shape: RoundedPolygon, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, selected: Boolean = false, badge: String? = null, sharedKey: String? = null) {
    val (bg, fg) = orionTonal(accent)
    val outline = RoundedCornerShape(28.dp)
    Box(modifier.orionSharedBounds(sharedKey).orionSpringClick(outline, onClick = onClick).background(bg).then(if (selected) Modifier.border(2.dp, fg, outline) else Modifier)) {
        OrionSpinner(shape, fg.copy(.1f), if (compact) 120.dp else 168.dp, Modifier.align(Alignment.BottomEnd).offset(36.dp, 36.dp))
        CompositionLocalProvider(LocalContentColor provides fg) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(fg.copy(.14f), shape.toShape()), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
                    Spacer(Modifier.weight(1f))
                    badge?.let { Badge(containerColor = fg, contentColor = bg) { Text(it, Modifier.padding(horizontal = 4.dp)) } }
                }
                Spacer(Modifier.weight(1f))
                // Long words (German) shrink a little before they are cut.
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = MaterialTheme.typography.titleLargeEmphasized.fontSize))
                if (!compact) Text(desc, style = MaterialTheme.typography.bodySmall, color = fg.copy(.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}


/** Hub section header with an optional trailing action. Orion ui/Settings.kt HubHeader. */
@Composable
fun OrionHubHeader(title: String, modifier: Modifier = Modifier, hint: String? = null, action: @Composable () -> Unit = {}) =
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 0.dp, top = 16.dp, bottom = 2.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action()
    }


/** Medium (56dp) M3E button: the screen's completion action. */
@Composable
fun OrionCtaButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, tonal: Boolean = false, loading: Boolean = false) {
    val h = ButtonDefaults.MediumContainerHeight
    val content: @Composable RowScope.() -> Unit = {
        if (loading) LoadingIndicator(Modifier.size(ButtonDefaults.iconSizeFor(h) + 8.dp), color = LocalContentColor.current)
        else icon?.let { Icon(it, null, Modifier.size(ButtonDefaults.iconSizeFor(h))) }
        if (loading || icon != null) Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(h)))
        Text(text, style = ButtonDefaults.textStyleFor(h), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val m = modifier.fillMaxWidth().heightIn(min = h)
    if (tonal) FilledTonalButton(onClick, modifier = m, enabled = enabled && !loading, shapes = ButtonDefaults.shapesFor(h), contentPadding = ButtonDefaults.contentPaddingFor(h), content = content)
    else Button(onClick, modifier = m, enabled = enabled && !loading, shapes = ButtonDefaults.shapesFor(h), contentPadding = ButtonDefaults.contentPaddingFor(h), content = content)
}


// region tile-to-page transition (Orion ui/Settings.kt: the hub tile and its page share bounds)
val LocalOrionShared = staticCompositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
val LocalOrionVisibility = compositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/** Makes the shared-transition scopes of a navigation destination available to [orionSharedBounds] inside [content]. */
@Composable
fun OrionSharedScreen(shared: androidx.compose.animation.SharedTransitionScope, visibility: androidx.compose.animation.AnimatedVisibilityScope, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalOrionShared provides shared, LocalOrionVisibility provides visibility, content = content)

/** Pages are measured once and scaled through the container transform; remeasuring every frame was the jank. Orion PAGE_RESIZE. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
private val PageResize = androidx.compose.animation.SharedTransitionScope.ResizeMode.scaleToBounds(androidx.compose.ui.layout.ContentScale.FillWidth, Alignment.TopCenter)

/** The tile with [key] and the page with the same key grow into each other. Outside a shared screen, or with motion off, nothing happens. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.orionSharedBounds(key: String?): Modifier {
    val shared = LocalOrionShared.current; val visibility = LocalOrionVisibility.current
    val motion = true
    if (shared == null || visibility == null || key == null || !motion) return this
    return with(shared) { this@orionSharedBounds.sharedBounds(rememberSharedContentState(key), visibility, resizeMode = PageResize) }
}
// endregion
