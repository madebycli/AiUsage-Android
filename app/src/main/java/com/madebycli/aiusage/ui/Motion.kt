@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.data.formatPercent
import kotlinx.coroutines.delay

/** One spring and one tween for the whole app, so every movement feels the same. */
internal val softSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
internal val quickSpring = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
internal const val PAGE_MILLIS = 320

/** Cards rise and fade in one after another the first time they appear. */
@Composable
internal fun Modifier.staggeredEntrance(index: Int, key: Any = Unit): Modifier {
    return androidx.compose.runtime.key(key) { orionReveal(index) }
}

/** Slight shrink while a card is pressed. */
@Composable
internal fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.94f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, androidx.compose.material3.MaterialTheme.motionScheme.fastSpatialSpec(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Fills from empty to [target] the first time it is shown, then follows changes with a spring. */
@Composable
internal fun rememberFillProgress(target: Float, delayMillis: Int = 0): State<Float> {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (value.value == 0f && delayMillis > 0) delay(delayMillis.toLong())
        value.animateTo(target.coerceIn(0f, 1f), softSpring)
    }
    return value.asState()
}

/** A percentage that counts up to its value. */
@Composable
internal fun rememberCountingPercent(target: Double): String {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) { value.animateTo(target.toFloat(), tween(700, easing = FastOutSlowInEasing)) }
    return formatPercent(value.value.toDouble())
}

/** A page counts as deeper the more routes lie above the main screen. */
internal data class Screen(val key: String, val depth: Int)

internal fun pageTransition(): AnimatedContentTransitionScope<Screen>.() -> ContentTransform = {
    val spec = spring<IntOffset>(dampingRatio = .92f, stiffness = 450f)
    when {
        // The page above slides over the one below. Both stay fully opaque, so nothing shows through and no ghost images appear.
        targetState.depth > initialState.depth ->
            (slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 4 }).apply { targetContentZIndex = 1f }
        targetState.depth < initialState.depth ->
            (slideInHorizontally(spec) { -it / 4 } togetherWith slideOutHorizontally(spec) { it }).apply { targetContentZIndex = -1f }
        // Same level: fade through, the old page is gone before the new one appears.
        else -> fadeIn(tween(180, delayMillis = 90)) togetherWith fadeOut(tween(90))
    }
}

/** Tabs fade through with a small slide towards the side they sit on. */
internal fun tabTransition(order: List<Int>): AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
    val forward = order.indexOf(targetState) >= order.indexOf(initialState)
    val offset: (Int) -> Int = { width -> if (forward) width / 14 else -width / 14 }
    (fadeIn(tween(200, delayMillis = 90)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing), offset)) togetherWith fadeOut(tween(90))
}
