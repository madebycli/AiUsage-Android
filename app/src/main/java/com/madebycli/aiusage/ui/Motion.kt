package com.madebycli.aiusage.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay((index.coerceAtMost(8) * 55).toLong())
        progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 36.dp.toPx()
    }
}

/** Slight shrink while a card is pressed. */
@Composable
internal fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.975f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, quickSpring, label = "press")
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
    val shown = value.value.toDouble()
    return formatPercent(if (kotlin.math.abs(shown - target) < 0.05) target else Math.round(shown * 10) / 10.0)
}

/** A page counts as deeper the more routes lie above the main screen. */
internal data class Screen(val key: String, val depth: Int)

internal fun pageTransition(): AnimatedContentTransitionScope<Screen>.() -> ContentTransform = {
    if (targetState.depth > initialState.depth) {
        (slideInHorizontally(tween(PAGE_MILLIS, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(PAGE_MILLIS))) togetherWith
            (slideOutHorizontally(tween(PAGE_MILLIS, easing = FastOutSlowInEasing)) { -it / 8 } + fadeOut(tween(PAGE_MILLIS / 2)))
    } else if (targetState.depth < initialState.depth) {
        (slideInHorizontally(tween(PAGE_MILLIS, easing = FastOutSlowInEasing)) { -it / 8 } + fadeIn(tween(PAGE_MILLIS))) togetherWith
            (slideOutHorizontally(tween(PAGE_MILLIS, easing = FastOutSlowInEasing)) { it / 5 } + fadeOut(tween(PAGE_MILLIS / 2)))
    } else fadeIn(tween(220)) togetherWith fadeOut(tween(160))
}

/** Tabs slide towards the side they sit on. */
internal fun tabTransition(order: List<Int>): AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
    val forward = order.indexOf(targetState) >= order.indexOf(initialState)
    val offset: (Int) -> Int = { width -> if (forward) width / 6 else -width / 6 }
    (slideInHorizontally(tween(280, easing = FastOutSlowInEasing), offset) + fadeIn(tween(280))) togetherWith
        (slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { -offset(it) } + fadeOut(tween(140)))
}
