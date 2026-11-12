/* Ported from Orion Store Settings.kt at e561e91e9409d9d9b437ab7acf919cd115769d7e.
 * RookieEnough and contributors, GPL-3.0. Hub slots, movable-card gestures, saved menu state,
 * fade/sharedBounds and PAGE_RESIZE are retained; categories/actions are AI Usage's.
 * The destination hero is intentionally omitted at the user's request.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.madebycli.aiusage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.madebycli.aiusage.*
import kotlinx.coroutines.launch

internal val AppTab.icon: ImageVector get() = when (this) {
    AppTab.USAGE -> Icons.Rounded.DonutLarge
    AppTab.STATS -> Icons.Rounded.BarChart
    AppTab.RESETS -> Icons.Rounded.History
}

private val SETTINGS_ORDER = listOf(DesignPage.ACCOUNTS, DesignPage.APPEARANCE, DesignPage.NOTIFICATIONS,
    DesignPage.WIDGETS, DesignPage.NAVIGATION, DesignPage.ABOUT)

/** Orion's settings owns one local menu; a tile expands into the entire destination surface. */
@Composable
internal fun SettingsScreen(state: UiState, model: UsageViewModel, onBack: () -> Unit, onSortAccounts: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = rememberSaveableStateHolder()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    fun back() { if (menu != null) menu = null else onBack() }
    BackHandler { back() }
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val shared = this
        AnimatedContent(menu, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "settings-menu") { target ->
            val visibility = this
            saved.SaveableStateProvider(target ?: "hub") {
                OrionSharedScreen(shared, visibility) {
                    val page = SETTINGS_ORDER.firstOrNull { it.name == target }
                    if (page == null) SettingsHub(state, { menu = it.name }, ::back)
                    else {
                        CompositionLocalProvider(LocalSettingsCategory provides page) {
                        // Exactly the same shared key as the source card; this is the full page, not its title.
                        Scaffold(modifier = Modifier.orionSharedBounds("settings:${page.name}").fillMaxSize(),
                            containerColor = MaterialTheme.colorScheme.background,
                            topBar = { TopAppBar(title = { SettingsPageTitle(page) }, expandedHeight = 88.dp, navigationIcon = {
                                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
                            }) }) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                                when (page) {
                                    DesignPage.ACCOUNTS -> AccountsScreen(state, model, sort = onSortAccounts)
                                    DesignPage.APPEARANCE -> AppearanceScreen(state, model)
                                    DesignPage.NOTIFICATIONS -> NotificationSettings(state, model)
                                    DesignPage.WIDGETS -> WidgetsScreen()
                                    DesignPage.NAVIGATION -> NavigationSettings(state, model)
                                    DesignPage.ABOUT -> AboutScreen()
                                    else -> Unit
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}

/** Orion Hub's fixed bento slots and drag-trading, with the integrated header from the reference. */
@Composable
private fun SettingsHub(state: UiState, open: (DesignPage) -> Unit, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", 0) }
    var order by rememberSaveable { mutableStateOf(
        (prefs.getString("settings-order", "").orEmpty().split(',').mapNotNull { name -> SETTINGS_ORDER.firstOrNull { it.name == name } } + SETTINGS_ORDER).distinct().map { it.name }) }
    val latestOrder by rememberUpdatedState(order)
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(query.isNotEmpty()) { query = "" }
    val (bg, fg) = orionTonal(colors.primary)
    val haptic = LocalHapticFeedback.current
    var live by remember { mutableStateOf<List<String>?>(null) }
    val shownOrder = live ?: order
    var dragging by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableStateOf(Offset.Zero) }
    val slots = remember { mutableMapOf<Int, Rect>() }
    val glide = remember { mutableStateMapOf<String, Animatable<Offset, AnimationVector2D>>() }
    val motion = rememberCoroutineScope()
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Offset>()
    fun center(slot: Int) = slots[slot]?.center ?: Offset.Zero
    fun slide(id: String, from: Offset) = motion.launch {
        Animatable(from, Offset.VectorConverter).also { glide[id] = it }.animateTo(Offset.Zero, spring)
    }
    fun start(slot: Int) {
        val current = live ?: latestOrder
        live = current; dragging = current[slot]; position = center(slot)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun move(delta: Offset) {
        val id = dragging ?: return; val current = live ?: return
        position += delta
        val from = current.indexOf(id)
        val to = slots.entries.firstOrNull { it.key != from && it.value.contains(position) }?.key ?: return
        val other = current[to]
        live = current.toMutableList().also { it[from] = other; it[to] = id }
        slide(other, center(to) - center(from)); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }
    fun end() {
        val id = dragging; val current = live
        if (id != null && current != null) {
            slide(id, position - center(current.indexOf(id)))
            order = current
            prefs.edit().putString("settings-order", current.joinToString(",")).apply()
        }
        dragging = null
    }
    LaunchedEffect(order) { if (dragging == null) live = null }
    fun Modifier.movable(slot: Int, id: String) = onGloballyPositioned { slots[slot] = it.boundsInRoot() }
        .zIndex(if (dragging == id) 1f else 0f)
        .graphicsLayer {
            val translation = if (dragging == id) position - center(slot) else glide[id]?.value ?: Offset.Zero
            translationX = translation.x; translationY = translation.y
            val scale = if (dragging == id) 1.05f else 1f; scaleX = scale; scaleY = scale
        }.pointerInput(slot) {
            detectDragGesturesAfterLongPress({ start(slot) }, { end() }, { end() }) { change, delta -> change.consume(); move(delta) }
        }
    fun description(page: DesignPage) = when (page) {
        DesignPage.ACCOUNTS -> if (state.accounts.size == 1) "1 Account" else "${state.accounts.size} Accounts"
        DesignPage.APPEARANCE -> "Google Sans Flex, Farben und Kontraste"
        DesignPage.NOTIFICATIONS -> "Resets und Aktualisierung"
        DesignPage.WIDGETS -> "Vorlagen und Anordnung"
        DesignPage.NAVIGATION -> "Startseite und untere Leiste"
        else -> "Version, Quellcode und Lizenzen"
    }
    fun z(vararg indices: Int): Float = if (indices.any { shownOrder[it] == dragging }) 2f else 0f
    @Composable fun Slot(slot: Int, modifier: Modifier, compact: Boolean = false) {
        val page = SETTINGS_ORDER.first { it.name == shownOrder[slot] }
        OrionBento(page.title, description(page), page.icon, page.accent, page.shape, { open(page) },
            modifier.movable(slot, page.name), compact = compact, sharedKey = "settings:${page.name}")
    }
    Box(Modifier.fillMaxSize().clipToBounds().background(Brush.verticalGradient(listOf(bg, colors.background, colors.background)))) {
        OrionSpinner(MaterialShapes.Cookie9Sided, fg.copy(.08f), 260.dp,
            Modifier.align(Alignment.TopEnd).offset(70.dp, (-20).dp), 40000)
        Scaffold(containerColor = Color.Transparent,
            topBar = { TopAppBar(title = {}, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent), navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
            }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).widthIn(max = maxContentWidth).align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                Box(Modifier.fillMaxWidth().testTag("settings-hub-header")) {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                            OrionSpinner(MaterialShapes.Cookie9Sided, fg.copy(.16f), 64.dp, periodMs = 18000)
                            Icon(Icons.Rounded.Settings, null, Modifier.size(32.dp), tint = fg)
                        }
                        Text("Einstellungen", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.displaySmallEmphasized)
                    }
                }
                TextField(query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text("Einstellungen durchsuchen") }, shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = colors.surfaceContainer, unfocusedContainerColor = colors.surfaceContainer))
                if (query.isNotBlank()) {
                    val results = SETTINGS_ORDER.filter { (it.title + " " + description(it)).contains(query.trim(), true) }
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        results.forEach { page -> OrionBento(page.title, description(page), page.icon, page.accent, page.shape, { open(page) },
                            Modifier.fillMaxWidth().height(132.dp), compact = true, sharedKey = "settings:${page.name}") }
                        if (results.isEmpty()) Notice("Keine passenden Einstellungen")
                    }
                } else {
                    Spacer(Modifier.height(16.dp))
                    val singleColumn = LocalDensity.current.fontScale > 1.3f
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (singleColumn) shownOrder.indices.forEach { Slot(it, Modifier.fillMaxWidth().height(180.dp)) }
                        else {
                            Slot(0, Modifier.zIndex(z(0)).fillMaxWidth().height(156.dp))
                            Row(Modifier.zIndex(z(1, 2, 3)).height(268.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slot(1, Modifier.zIndex(z(1)).weight(1f).fillMaxHeight())
                                Column(Modifier.zIndex(z(2, 3)).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Slot(2, Modifier.fillMaxWidth().weight(1f), compact = true)
                                    Slot(3, Modifier.fillMaxWidth().weight(1f), compact = true)
                                }
                            }
                            Slot(4, Modifier.zIndex(z(4)).fillMaxWidth().height(140.dp))
                            Slot(5, Modifier.zIndex(z(5)).fillMaxWidth().height(132.dp), compact = true)
                        }
                    }
                }
            }
        }
    }
}
