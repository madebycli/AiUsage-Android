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
        targetState.depth > initialState.depth ->
            (slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 4 }).apply { targetContentZIndex = 1f }
        targetState.depth < initialState.depth ->
            (slideInHorizontally(spec) { -it / 4 } togetherWith slideOutHorizontally(spec) { it }).apply { targetContentZIndex = -1f }
        else -> fadeIn(tween(180, delayMillis = 90)) togetherWith fadeOut(tween(90))
    }
}

/**
 * Window motion as in Iris NavHost (copied from Orion Store Root.kt Overlays): a page opens with a fade and a scale from
 * 0.92 while the page below stays put; closing fades and scales it out to 0.94. Settings slide in a third of the width
 * from the right with a fade, and leave the same way.
 */
@androidx.compose.runtime.Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
internal fun windowTransition(): AnimatedContentTransitionScope<Screen>.() -> ContentTransform {
    val motion = androidx.compose.material3.MaterialTheme.motionScheme
    val scaleSpec = motion.defaultSpatialSpec<Float>()
    val fx = motion.fastEffectsSpec<Float>()
    val effects = motion.defaultEffectsSpec<Float>()
    val slide = motion.defaultSpatialSpec<IntOffset>()
    return {
        val settingsIn = targetState.key == "route:settings" && initialState.depth == 0
        val settingsOut = initialState.key == "route:settings" && targetState.depth == 0
        when {
            settingsIn -> ((slideInHorizontally(slide) { it / 3 } + fadeIn(effects)) togetherWith ExitTransition.KeepUntilTransitionsFinished).apply { targetContentZIndex = 1f }
            settingsOut -> (EnterTransition.None togetherWith (slideOutHorizontally(slide) { it / 3 } + fadeOut(effects))).apply { targetContentZIndex = -1f }
            targetState.depth > initialState.depth ->
                ((fadeIn(fx) + scaleIn(scaleSpec, 0.92f)) togetherWith ExitTransition.KeepUntilTransitionsFinished).apply { targetContentZIndex = 1f }
            targetState.depth < initialState.depth ->
                (EnterTransition.None togetherWith (fadeOut(fx) + scaleOut(targetScale = 0.94f))).apply { targetContentZIndex = -1f }
            else -> fadeIn(effects) togetherWith fadeOut(fx)
        }
    }
}

/** Tabs: shared X axis, a fifth of the width, with the fast spatial spring (Iris NavHost tab motion). */
internal fun tabTransition(order: List<Int>): AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
    val forward = order.indexOf(targetState) >= order.indexOf(initialState)
    val spring = spring<IntOffset>(dampingRatio = .8f, stiffness = 1400f)
    val fade = spring<Float>(dampingRatio = 1f, stiffness = 3800f)
    (slideInHorizontally(spring) { (it * .2f * if (forward) 1 else -1).toInt() } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(spring) { (it * .2f * if (forward) -1 else 1).toInt() } + fadeOut(fade))
}
