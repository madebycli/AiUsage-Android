// Derived from AniHyou by axiel7, GPL-3.0. See docs/UPSTREAM.md.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.axiel7.anihyou.core.ui.composables

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity

@Composable
fun DefaultScaffoldWithLargeTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable (RowScope.() -> Unit) = {},
    scrollBehavior: TopAppBarScrollBehavior,
    topAppBarColors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            LargeTopAppBar(
                title = { Text(text = title) },
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = topAppBarColors,
            )
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        contentWindowInsets = contentWindowInsets,
        content = content
    )
}

@Composable
fun DefaultScaffoldWithMediumTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable (RowScope.() -> Unit) = {},
    scrollBehavior: TopAppBarScrollBehavior,
    topAppBarColors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    accentTitle: Boolean = false,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            MediumTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (accentTitle) Box(Modifier.size(4.dp, 26.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                        Text(text = title)
                    }
                },
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = topAppBarColors,
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = contentWindowInsets,
        content = content
    )
}

@Composable
fun DefaultScaffoldWithSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable (RowScope.() -> Unit) = {},
    scrollBehavior: TopAppBarScrollBehavior,
    topAppBarColors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = topAppBarColors,
            )
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = contentWindowInsets,
        content = content
    )
}

@Composable
fun rememberTopBarContainerColor(
    colors: TopAppBarColors,
    scrollBehavior: TopAppBarScrollBehavior
) = remember(colors, scrollBehavior) {
    derivedStateOf {
        val fraction = scrollBehavior.state.collapsedFraction
        lerp(
            colors.containerColor,
            colors.scrolledContainerColor,
            FastOutLinearInEasing.transform(fraction),
        )
    }
}

@Composable
fun StatusBarProtection(
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(
                with(LocalDensity.current) {
                    (WindowInsets.statusBars.getTop(this) * 1.2f).toDp()
                }
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = 1f),
                        color.copy(alpha = 0.8f),
                        Color.Transparent
                    )
                )
            )
    )
}
