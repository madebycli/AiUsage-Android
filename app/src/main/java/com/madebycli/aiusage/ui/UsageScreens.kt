@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.madebycli.aiusage.AccountState
import com.madebycli.aiusage.HomeStyle
import com.madebycli.aiusage.R
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.ZoneId

/** The home screen: every account in the order the user chose, in the chosen style, or the sort list. */
@Composable
internal fun UsageScreen(state: UiState, now: Long, sorting: Boolean, open: (String) -> Unit, reorder: (List<String>) -> Unit) {
    if (sorting) { SortList(state.accounts, reorder); return }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(state.accounts, key = { _, entry -> entry.id }) { index, entry ->
            if (state.homeStyle == HomeStyle.PLAYFUL) PlayfulCard(entry, now, index, Modifier.animateItem()) { open(entry.id) }
            else AccountCard(entry, now, index, Modifier.animateItem()) { open(entry.id) }
        }
    }
}

@Composable
private fun CardHeader(entry: AccountState, now: Long) {
    val res = LocalResources.current
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandIcon(entry.provider)
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(entry.plan, entry.usage?.let { Fmt.ago(res, now - it.fetchedAt) }).joinToString(" · ")
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        if (entry.refreshing) LoadingIndicator(Modifier.size(28.dp))
        else Icon(Icons.Rounded.ChevronRight, null, tint = colors.onSurfaceVariant)
    }
}

/** 0.9.1 card: two windows side by side with number, bar and reset time. */
@Composable
internal fun AccountCard(entry: AccountState, now: Long, index: Int, modifier: Modifier = Modifier, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val usage = entry.usage
    val windows = entry.windows()
    Box(modifier.fillMaxWidth().orionReveal(index).orionSpringClick(cardShape, onClick = open).background(colors.panel)) {
        Column(Modifier.padding(20.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CardHeader(entry, now)
            if (windows.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                windows.take(2).forEachIndexed { i, window -> WindowColumn(window, now, i, Modifier.weight(1f)) }
            } else if (entry.refreshing && usage == null) SkeletonRows()
            else Text(stringResource(if (usage == null) R.string.usage_no_reading else R.string.usage_no_windows), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
}

@Composable
private fun WindowColumn(window: UsageWindow, now: Long, index: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val progress by rememberFillProgress((window.usedPercent / 100).toFloat(), 120 + index * 90)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(windowTitle(window.seconds), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text("${rememberCountingPercent(window.usedPercent)}%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        UsageTrack(progress, windowColor(window, colors), Modifier.fillMaxWidth().height(6.dp))
        ResetLines(window.resetAt, now)
    }
}

@Composable
private fun SkeletonRows() {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(.35f, .8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(2) {
            Column(Modifier.weight(1f).graphicsLayer { this.alpha = alpha }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(60.dp).height(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)))
                Box(Modifier.width(90.dp).height(30.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)))
                Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(3.dp)))
            }
        }
    }
}

internal fun windowColor(window: UsageWindow, colors: ColorScheme) =
    if (window.usedPercent >= 100) colors.error else if (window.seconds == FIVE_HOURS) colors.primary else colors.tertiary

// ---- Playful home ---------------------------------------------------------------------------------------

@Composable
private fun PlayfulCard(entry: AccountState, now: Long, index: Int, modifier: Modifier = Modifier, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val windows = entry.windows().take(2)
    Box(modifier.fillMaxWidth().orionReveal(index).orionSpringClick(cardShape, onClick = open).background(colors.panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CardHeader(entry, now)
            if (windows.isEmpty()) Text(stringResource(if (entry.usage == null) R.string.usage_no_reading else R.string.usage_no_windows), color = colors.onSurfaceVariant)
            else Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                windows.forEachIndexed { i, window ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiquidShape(if (window.seconds == FIVE_HOURS) MaterialShapes.Cookie9Sided else MaterialShapes.Flower,
                            (window.usedPercent / 100).toFloat(), windowColor(window, colors), 116.dp, i) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${rememberCountingPercent(window.usedPercent)}%", style = MaterialTheme.typography.headlineMediumEmphasized, fontWeight = FontWeight.Bold)
                                Text(windowTitle(window.seconds), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        ResetLines(window.resetAt, now, horizontalAlignment = Alignment.CenterHorizontally)
                    }
                }
            }
            entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
}

/**
 * A Material shape that fills from the bottom like a glass: the level is the usage, its surface moves in a slow wave and
 * the shape turns a little. The wave stops when the window is full.
 */
@Composable
internal fun LiquidShape(shape: RoundedPolygon, level: Float, color: Color, size: Dp, index: Int, content: @Composable BoxScope.() -> Unit) {
    val fill by rememberFillProgress(level.coerceIn(0f, 1f), 150 + index * 120)
    val waves = rememberInfiniteTransition(label = "liquid")
    val phase by waves.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2600 + index * 400, easing = LinearEasing)), label = "phase")
    val turn by waves.animateFloat(-6f, 6f, infiniteRepeatable(tween(5200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "turn")
    val track = color.copy(alpha = .16f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().graphicsLayer { rotationZ = turn }) {
            val path = shape.toPath().asComposePath().apply { transform(androidx.compose.ui.graphics.Matrix().apply { scale(this@Canvas.size.width, this@Canvas.size.height) }) }
            drawPath(path, track)
            if (fill > 0f) clipPath(path) {
                val top = this.size.height * (1f - fill)
                val amplitude = if (fill >= .999f) 0f else 4.dp.toPx()
                val wave = Path().apply {
                    moveTo(0f, this@clipPath.size.height)
                    lineTo(0f, top)
                    val steps = 24
                    for (s in 0..steps) {
                        val x = this@clipPath.size.width * s / steps
                        lineTo(x, top + amplitude * kotlin.math.sin(phase + s / steps.toFloat() * 2 * Math.PI.toFloat()))
                    }
                    lineTo(this@clipPath.size.width, this@clipPath.size.height); close()
                }
                drawPath(wave, color.copy(alpha = .6f))
            }
        }
        content()
    }
}

// ---- Sort mode ------------------------------------------------------------------------------------------

private val sortRowHeight = 68.dp
private val sortGap = 8.dp

/** Reorders accounts by dragging the handle or with the arrows; the order is saved after every move. */
@Composable
internal fun SortList(accounts: List<AccountState>, commit: (List<String>) -> Unit) {
    val order = remember(accounts.map { it.id }.toSet()) { mutableStateListOf<String>().apply { addAll(accounts.map { it.id }) } }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { (sortRowHeight + sortGap).toPx() }
    val lift = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    fun move(id: String, by: Int) {
        val index = order.indexOf(id)
        val target = (index + by).coerceIn(0, order.lastIndex)
        if (target != index) { order.add(target, order.removeAt(index)); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(sortGap)) {
        item(key = "hint") { Text(stringResource(R.string.usage_order_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(order, key = { it }) { id ->
            val entry = accounts.firstOrNull { it.id == id } ?: return@items
            val active = dragged == id
            val raise by animateFloatAsState(if (active) 1f else 0f, lift, label = "lift")
            val name = entry.title
            Surface(
                modifier = (if (active) Modifier else Modifier.animateItem()).fillMaxWidth().height(sortRowHeight).zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active) offset else 0f; scaleX = 1f + .03f * raise; scaleY = 1f + .03f * raise; rotationZ = -1.2f * raise },
                shape = RoundedCornerShape(22.dp), color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.panel,
                shadowElevation = (8 * raise).dp
            ) {
                Row(Modifier.padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val moveLabel = stringResource(R.string.usage_move, name)
                    Box(Modifier.size(48.dp).semantics { contentDescription = moveLabel }.pointerInput(id) {
                        detectDragGestures(
                            onDragStart = { dragged = id; offset = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrag = { change, amount ->
                                change.consume(); offset += amount.y
                                val index = order.indexOf(id)
                                if (offset > step * .6f && index < order.lastIndex) { move(id, 1); offset -= step }
                                else if (offset < -step * .6f && index > 0) { move(id, -1); offset += step }
                            },
                            onDragEnd = { dragged = null; offset = 0f; commit(order.toList()) },
                            onDragCancel = { dragged = null; offset = 0f; commit(order.toList()) })
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    BrandIcon(entry.provider, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.plan ?: entry.provider, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { move(id, -1); commit(order.toList()) }, enabled = order.indexOf(id) > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.usage_up)) }
                    IconButton(onClick = { move(id, 1); commit(order.toList()) }, enabled = order.indexOf(id) < order.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.usage_down)) }
                }
            }
        }
    }
}

// ---- Account page ---------------------------------------------------------------------------------------

/** One account on a page with a tinted hero in the provider's colour, like the Iris settings pages. */
@Composable
internal fun AccountPage(entry: AccountState, state: UiState, now: Long, model: UsageViewModel, back: () -> Unit, stats: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val res = LocalResources.current
    val usage = entry.usage
    val subtitle = listOfNotNull(entry.provider, entry.plan, entry.account.detail?.takeIf { it != entry.title }).joinToString(" · ")
    SettingsPageScaffold(Icons.Rounded.DonutLarge, entry.title, subtitle, providerAccent(entry.provider), MaterialShapes.Cookie9Sided, back,
        actions = {
            RefreshButton(entry.refreshing) { model.refresh(entry.id) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.accounts_rename)) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.accounts_sign_in_again)) }, onClick = { menu = false; model.connect(entry.provider, entry.id) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.accounts_disconnect), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirm = true })
                }
            }
        }) {
        entry.error?.let { settingsItem("error", 0) { Notice(it, true) } }
        entry.storageWarning?.let { settingsItem("storage", 0) { Notice(it) } }
        if (usage == null) settingsItem("empty", 1) {
            SoftCard {
                if (entry.refreshing) LoadingIndicator()
                Text(stringResource(if (entry.refreshing) R.string.usage_loading else R.string.usage_not_loaded), style = MaterialTheme.typography.titleMedium)
            }
        }
        usage?.let { snapshot ->
            val warnings = snapshot.warnings.filterNot { it == snapshot.creditsWarning }
            if (warnings.isNotEmpty()) settingsItem("warnings", 1) { Notice(warnings.joinToString("\n")) }
            var shown = 0
            snapshot.groups.forEachIndexed { index, group ->
                group.windows.forEach { window ->
                    val position = shown++
                    settingsItem("window:$index:${window.seconds}", 1 + position) {
                        WindowCard(window, now, if (group.name == entry.provider) null else group.name)
                    }
                }
            }
            if (snapshot.availableResets != null || snapshot.resetCredits.isNotEmpty() || snapshot.creditsWarning != null)
                settingsItem("bank", 4) { BankedCard(snapshot, now, entry.creditsLoading) }
            snapshot.extraUsage?.let { settingsItem("extra", 5) { ExtraUsageCard(it) } }
            snapshot.accountCredits?.let { credits -> settingsItem("credits", 5) { SoftCard {
                Text(stringResource(R.string.credits_title), style = MaterialTheme.typography.titleMedium)
                Text(if (credits.unlimited == true) stringResource(R.string.credits_unlimited) else credits.balance?.let { stringResource(R.string.credits_amount, Fmt.number(res, it, 2)) }
                    ?: stringResource(R.string.not_reported), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (credits.available == false) Text(stringResource(R.string.credits_unavailable), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } } }
            snapshot.spending?.let { spending -> settingsItem("spend", 5) { SoftCard {
                Text(stringResource(R.string.spend_title), style = MaterialTheme.typography.titleMedium)
                spending.remainingPercent?.let { Text(stringResource(R.string.spend_free, Fmt.percent(it)), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                Text(stringResource(R.string.spend_line, spending.used?.let { Fmt.number(res, it, 2) } ?: stringResource(R.string.not_reported),
                    spending.limit?.let { Fmt.number(res, it, 2) } ?: stringResource(R.string.not_reported)), style = MaterialTheme.typography.bodySmall)
                spending.resetAt?.let { Text(stringResource(R.string.usage_reset_at, Fmt.clock(res, it, now)), style = MaterialTheme.typography.labelMedium) }
            } } }
        }
        settingsItem("recent", 6) { RecentUsageCard(entry, now, stats) }
        val cycles = entry.ledger.fiveCycles.filter { it.peak > 0 && !it.migrated }.take(8)
        if (cycles.isNotEmpty()) settingsItem("cycles", 7) { CycleList(cycles, now) }
        usage?.let { settingsItem("stand", 8) {
            Text(stringResource(R.string.usage_stand, "${Fmt.shortDate(res, it.fetchedAt)} ${Fmt.clockOnly(res, it.fetchedAt)}"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        } }
    }
    if (renaming) RenameDialog(entry, { renaming = false }) { model.renameAccount(entry.id, it); renaming = false }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.accounts_disconnect_title, entry.title)) },
        text = { Text(stringResource(R.string.accounts_disconnect_text)) },
        confirmButton = { TextButton(onClick = { confirm = false; model.removeAccount(entry.id) }) { Text(stringResource(R.string.accounts_disconnect), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

/** The last seven days of one account, with a way into the statistics. */
@Composable
private fun RecentUsageCard(entry: AccountState, now: Long, stats: (() -> Unit)?) {
    val res = LocalResources.current
    val today = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = remember(entry.ledger) { LedgerStats.days(entry.ledger) }
    val dates = List(7) { today.minusDays((6 - it).toLong()) }
    val values = dates.map { days[it]?.takeIf { day -> day.measured }?.five }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.usage_last7), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (values.any { it != null }) Fmt.limits(res, values.filterNotNull().sum()) else stringResource(R.string.usage_no_reading),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MiniBars(values, dates.map { Fmt.weekdayLetter(res, it.dayOfWeek.value) })
        if (stats != null) TextButton(onClick = stats, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Rounded.BarChart, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.usage_all_stats))
        }
    }
}

/** The latest 5-hour windows of the account: start to end, peak, and whether the end was seen. */
@Composable
private fun CycleList(cycles: List<CycleRecord>, now: Long) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    SettingsSectionLabel(stringResource(R.string.usage_cycles))
    SettingsGroup {
        cycles.forEach { cycle ->
            val start = cycle.start(FIVE_HOURS)
            Surface(color = cs.panel, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(112.dp)) {
                        Text(Fmt.clock(res, start, now), style = MaterialTheme.typography.titleSmall)
                        Text("– ${Fmt.clockOnly(res, cycle.end)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    UsageTrack((cycle.peak / 100).toFloat(), if (cycle.peak >= 100) cs.error else cs.primary, Modifier.weight(1f).height(8.dp))
                    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.End) {
                        Text("${Fmt.percent(cycle.peak)}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        val state = when { cycle.closedAt == null && cycle.end > now -> stringResource(R.string.usage_cycle_running); !cycle.complete -> stringResource(R.string.usage_cycle_open); else -> null }
                        state?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

/** One window in detail: usage, the reset as a clock time, how far the window has run, and the pace so far. */
@Composable
private fun WindowCard(window: UsageWindow, now: Long, group: String?) {
    val colors = MaterialTheme.colorScheme
    val res = LocalResources.current
    val accent = windowColor(window, colors)
    val at = window.resetAt
    val expired = at != null && at <= now
    val title = listOfNotNull(group, windowTitle(window.seconds)).joinToString(" · ")
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                expired -> StatusPill(stringResource(R.string.usage_reset_reached), colors.tertiaryContainer, colors.onTertiaryContainer)
                window.usedPercent >= 100 -> StatusPill(stringResource(R.string.usage_limit_reached), colors.errorContainer, colors.onErrorContainer)
                at != null -> StatusPill(stringResource(R.string.usage_running), colors.surfaceContainerHighest, colors.onSurfaceVariant)
            }
        }
        val a11y = stringResource(R.string.usage_window_a11y, title, Fmt.percent(window.usedPercent))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            RingGauge((window.usedPercent / 100).toFloat(), accent, "${rememberCountingPercent(window.usedPercent)}%", stringResource(R.string.usage_used),
                size = 120.dp, modifier = Modifier.semantics { contentDescription = a11y })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.usage_next_reset), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(if (at == null) stringResource(R.string.not_reported) else Fmt.clock(res, at, now), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                if (at != null) Text(if (expired) stringResource(R.string.usage_since, Fmt.ago(res, now - at)) else Fmt.remaining(res, at - now),
                    style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            }
        }
        if (at != null && !expired) {
            WindowTimeline(window, now, accent)
            Pace.of(window, now)?.let { PaceLine(it, now) }
        }
    }
}

@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Time and usage on one scale: the window from start to reset, with how much of both is gone. */
@Composable
private fun WindowTimeline(window: UsageWindow, now: Long, accent: Color) {
    val colors = MaterialTheme.colorScheme
    val res = LocalResources.current
    val end = window.resetAt ?: return
    val start = end - window.seconds
    val elapsed = ((now - start).toDouble() / window.seconds).toFloat().coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TimelineRow(stringResource(R.string.usage_time), elapsed, colors.onSurfaceVariant.copy(alpha = .55f), "${Fmt.percent(elapsed * 100.0)} %")
        TimelineRow(stringResource(R.string.usage_usage), (window.usedPercent / 100).toFloat(), accent, "${Fmt.percent(window.usedPercent)} %")
        Row {
            Text(Fmt.clock(res, start, now), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(Fmt.clock(res, end, now), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimelineRow(label: String, progress: Float, color: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        UsageTrack(progress, color, Modifier.weight(1f).height(8.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun PaceLine(pace: Pace, now: Long) {
    val colors = MaterialTheme.colorScheme
    val res = LocalResources.current
    val (text, color) = when (pace.status) {
        Pace.Status.FULL -> return
        Pace.Status.WILL_RUN_OUT -> stringResource(R.string.usage_pace_full, pace.fullAt?.let { Fmt.clock(res, it, now) } ?: "–") to colors.error
        Pace.Status.ON_TRACK -> stringResource(R.string.usage_pace_projected, Fmt.percent(pace.projectedPercent)) to colors.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

/** Saved resets ("Vorrat"): count large, then each credit with its state and expiry, soonest expiry first. */
@Composable
internal fun BankedCard(usage: UsageSnapshot, now: Long, loading: Boolean, title: String? = null) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).background(cs.primaryContainer, MaterialShapes.Cookie7Sided.toShape()), contentAlignment = Alignment.Center) {
                Text((usage.availableResets ?: usage.resetCredits.count { it.status == "available" }).toString(), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                val count = usage.availableResets
                Text(if (count != null) pluralStringResource(R.plurals.bank_title, count, count) else stringResource(R.string.bank_header), style = MaterialTheme.typography.titleMedium)
                if (title != null) Text(title, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
        if (loading) Text(stringResource(R.string.bank_loading), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        val credits = usage.resetCredits.distinctBy { listOf(it.status, it.grantedAt, it.expiresAt, it.title) }
            .sortedWith(compareBy({ it.status != "available" }, { it.expiresAt ?: Long.MAX_VALUE }))
        credits.forEach { credit ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.RestartAlt, null, tint = if (credit.status == "available") cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Text(stringResource(when (credit.status) {
                    "available" -> R.string.bank_available; "redeemed" -> R.string.bank_redeemed; "paused" -> R.string.bank_paused
                    "expired" -> R.string.bank_expired; "unknown" -> R.string.bank_unknown; else -> R.string.bank_unavailable
                }), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                credit.expiresAt?.let {
                    Text(stringResource(R.string.bank_expires, Fmt.mediumDate(res, Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalDate())) +
                        if (it <= now && credit.status != "expired") " · " + stringResource(R.string.bank_expired_now) else "",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        usage.creditsWarning?.let { Text(stringResource(R.string.bank_details_failed), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
    }
}

@Composable
private fun ExtraUsageCard(extra: ExtraUsage) {
    fun money(value: Double?) = extraAmountText(value, extra.currency, extra.decimals)
    SoftCard {
        Text(stringResource(R.string.extra_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when (extra.enabled) { true -> R.string.extra_on; false -> R.string.extra_off; null -> R.string.extra_unknown }), style = MaterialTheme.typography.labelMedium)
        if (extra.enabled == true || extra.used != null || extra.limit != null) {
            Text(money(extra.used), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.extra_monthly, money(extra.limit)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Spinner for the app bar; shows the refresh icon while idle. */
@Composable
internal fun RefreshButton(refreshing: Boolean, label: String = stringResource(R.string.refresh_now), onClick: () -> Unit) {
    val angle = if (refreshing) rememberInfiniteTransition(label = "refresh").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle").value else 0f
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
        Icon(Icons.Rounded.Refresh, label, Modifier.graphicsLayer { rotationZ = angle })
    }
}
