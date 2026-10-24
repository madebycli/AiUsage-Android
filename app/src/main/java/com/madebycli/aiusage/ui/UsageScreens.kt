@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.axiel7.anihyou.core.ui.composables.common.SmallCircularProgressIndicator
import com.madebycli.aiusage.AccountState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.data.*

/** The home screen: one card per account in the order the user chose, or the sort list. */
@Composable
internal fun UsageScreen(state: UiState, now: Long, sorting: Boolean, open: (String) -> Unit, add: () -> Unit, reorder: (List<String>) -> Unit) {
    if (sorting) {
        SortList(state.accounts, reorder)
        return
    }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(state.accounts, key = { _, entry -> entry.id }) { index, entry ->
            AccountCard(entry, now, index, Modifier.animateItem()) { open(entry.id) }
        }
        item(key = "add") { AddAccountRow(state.accounts.size, add, Modifier.animateItem()) }
    }
}

@Composable
private fun AddAccountRow(count: Int, add: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = add, modifier = modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(20.dp)) {
        Icon(Icons.Rounded.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        Text(if (count == 0) "Account verbinden" else "Weiteren Account hinzufügen")
    }
}

@Composable
internal fun AccountCard(entry: AccountState, now: Long, index: Int, modifier: Modifier = Modifier, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val usage = entry.usage
    val windows = entry.windows()
    val source = remember { MutableInteractionSource() }
    Surface(onClick = open, interactionSource = source, modifier = modifier.fillMaxWidth().staggeredEntrance(index, entry.id).pressScale(source),
        shape = cardShape, color = colors.surfaceContainerLow) {
        Column(Modifier.padding(20.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandIcon(entry.provider)
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val subtitle = listOfNotNull(entry.plan, usage?.let { "vor ${age(now - it.fetchedAt).removePrefix("vor ")}" }).joinToString(" · ")
                    if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                if (entry.refreshing) SmallCircularProgressIndicator()
                else Icon(Icons.Rounded.ChevronRight, null, tint = colors.onSurfaceVariant)
            }
            if (windows.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                windows.forEachIndexed { i, window -> WindowColumn(window, now, i, Modifier.weight(1f)) }
            } else if (entry.refreshing && usage == null) SkeletonRows()
            else Text(if (usage == null) "Noch keine Messung" else "Der Anbieter meldet gerade keine Zeitfenster.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            entry.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
}

@Composable
private fun WindowColumn(window: UsageWindow, now: Long, index: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val progress by rememberFillProgress((window.usedPercent / 100).toFloat(), 120 + index * 90)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(window.title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Text("${rememberCountingPercent(window.usedPercent)}%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val track = colors.surfaceContainerHighest
            drawLine(track, Offset(size.height / 2, center.y), Offset(size.width - size.height / 2, center.y), size.height, StrokeCap.Round)
            if (progress > 0f) drawLine(windowColor(window, colors), Offset(size.height / 2, center.y),
                Offset(((size.width - size.height) * progress + size.height / 2), center.y), size.height, StrokeCap.Round)
        }
        ResetLines(window.resetAt, now)
    }
}

@Composable
private fun SkeletonRows() {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(.35f, .8f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(2) {
            Column(Modifier.weight(1f).alpha(alpha), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(60.dp).height(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)))
                Box(Modifier.width(90.dp).height(30.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)))
                Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(3.dp)))
            }
        }
    }
}

private fun windowColor(window: UsageWindow, colors: ColorScheme) =
    if (window.usedPercent >= 100) colors.error else if (window.seconds == FIVE_HOURS) colors.primary else colors.tertiary

// ---- Sort mode ------------------------------------------------------------------------------------------

private val sortRowHeight = 68.dp
private val sortGap = 10.dp

/** Reorders accounts by dragging the handle or with the arrows; the order is saved after every move. */
@Composable
internal fun SortList(accounts: List<AccountState>, commit: (List<String>) -> Unit) {
    val order = remember(accounts.map { it.id }.toSet()) { mutableStateListOf<String>().apply { addAll(accounts.map { it.id }.let { ids -> ids }) } }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val step = with(LocalDensity.current) { (sortRowHeight + sortGap).toPx() }
    fun move(id: String, by: Int) {
        val index = order.indexOf(id)
        val target = (index + by).coerceIn(0, order.lastIndex)
        if (target != index) { order.add(target, order.removeAt(index)); haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(sortGap)) {
        item(key = "hint") {
            Text("Ziehe die Karten am Griff oder nutze die Pfeile. Die Reihenfolge gilt für die Startseite und das Widget.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        items(order, key = { it }) { id ->
            val entry = accounts.firstOrNull { it.id == id } ?: return@items
            val active = dragged == id
            val lift by animateFloatAsState(if (active) 1f else 0f, quickSpring, label = "lift")
            Surface(
                modifier = (if (active) Modifier else Modifier.animateItem()).fillMaxWidth().height(sortRowHeight).zIndex(if (active) 1f else 0f)
                    .graphicsLayer { translationY = if (active) offset else 0f; scaleX = 1f + .02f * lift; scaleY = 1f + .02f * lift },
                shape = RoundedCornerShape(22.dp), color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = (8 * lift).dp
            ) {
                Row(Modifier.padding(start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).semantics { contentDescription = "${entry.title} verschieben" }.pointerInput(id) {
                        detectDragGestures(
                            onDragStart = { dragged = id; offset = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrag = { change, amount ->
                                change.consume()
                                offset += amount.y
                                val index = order.indexOf(id)
                                if (offset > step * .6f && index < order.lastIndex) { move(id, 1); offset -= step }
                                else if (offset < -step * .6f && index > 0) { move(id, -1); offset += step }
                            },
                            onDragEnd = { dragged = null; offset = 0f; commit(order.toList()) },
                            onDragCancel = { dragged = null; offset = 0f; commit(order.toList()) }
                        )
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    BrandIcon(entry.provider, Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.plan ?: entry.provider, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { move(id, -1); commit(order.toList()) }, enabled = order.indexOf(id) > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Nach oben") }
                    IconButton(onClick = { move(id, 1); commit(order.toList()) }, enabled = order.indexOf(id) < order.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Nach unten") }
                }
            }
        }
    }
}

// ---- Account page ---------------------------------------------------------------------------------------

@Composable
internal fun AccountDetailScreen(state: UiState, now: Long, entry: AccountState, stats: (() -> Unit)?) {
    val usage = entry.usage
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandIcon(entry.provider)
                Column {
                    Text(listOfNotNull(entry.provider, entry.plan?.replaceFirstChar { it.titlecase() }).joinToString(" · "), style = MaterialTheme.typography.labelLarge)
                    entry.account.detail?.takeIf { it != entry.title }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        entry.error?.let { item { Notice(it, true) } }
        entry.storageWarning?.let { item { Notice(it) } }
        if (usage == null) item { SoftCard {
            if (entry.refreshing) LoadingIndicator()
            Text(if (entry.refreshing) "Verbrauch wird geladen" else "Verbrauch noch nicht geladen", style = MaterialTheme.typography.titleMedium)
            Text("Oben aktualisieren, um es erneut zu versuchen.", style = MaterialTheme.typography.bodySmall)
        } }
        usage?.let { snapshot ->
            val warnings = snapshot.warnings.filterNot { it == snapshot.creditsWarning }
            if (warnings.isNotEmpty()) item { Notice(warnings.joinToString("\n")) }
            var animatedIndex = 0
            snapshot.groups.forEachIndexed { index, group ->
                if (group.name != entry.provider) item(key = "title:$index") { SectionTitle(group.name) }
                items(group.windows, key = { "$index:${it.seconds}" }) { window -> WindowCard(window, now, animatedIndex++) }
            }
            if (snapshot.groups.all { it.windows.isEmpty() }) item { Notice("Der Anbieter meldet aktuell keine Zeitfenster.") }
            if (snapshot.availableResets != null || snapshot.resetCredits.isNotEmpty() || snapshot.creditsWarning != null)
                item { BankedCard(snapshot, now, entry.creditsLoading) }
            snapshot.extraUsage?.let { item { ExtraUsageCard(it) } }
            snapshot.accountCredits?.let { credits -> item { SoftCard {
                Text("Credit-Guthaben", style = MaterialTheme.typography.titleMedium)
                Text(if (credits.unlimited == true) "Unbegrenzt" else credits.balance?.let { "${number(it)} Credits" } ?: "Nicht gemeldet",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (credits.available == false) Text("Derzeit nicht verfügbar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } } }
            snapshot.spending?.let { spending -> item { SoftCard {
                Text("Zusätzliches Kontingent", style = MaterialTheme.typography.titleMedium)
                spending.remainingPercent?.let { Text("${percent(it)}% frei", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                Text("Verbrauch ${spending.used?.let { number(it) } ?: "nicht gemeldet"} · Limit ${spending.limit?.let { number(it) } ?: "nicht gemeldet"}", style = MaterialTheme.typography.bodySmall)
                spending.resetAt?.let { Text("Reset ${date(it)}", style = MaterialTheme.typography.labelMedium) }
            } } }
        }
        if (entry.ledger.days.isNotEmpty() || stats != null) item { RecentUsageCard(entry, now, stats) }
        usage?.let { item { Text("${if (entry.refreshing) "Aktualisiert… · " else ""}Stand ${date(it.fetchedAt, true)} (${age(now - it.fetchedAt)})",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

/** The last seven days of one account, with a way into the statistics. */
@Composable
private fun RecentUsageCard(entry: AccountState, now: Long, stats: (() -> Unit)?) {
    val today = java.time.Instant.ofEpochSecond(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val byDate = entry.ledger.days.associateBy { it.date }
    val days = List(7) { today.minusDays((6 - it).toLong()) }
    val values = days.map { byDate[it.toString()]?.takeIf { day -> day.measured }?.five }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Letzte 7 Tage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val sum = values.filterNotNull().sum()
            Text(if (values.any { it != null }) formatLimits(sum) else "Keine Messung", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MiniBars(values, days.map { it.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.GERMAN) })
        if (stats != null) TextButton(onClick = stats, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Rounded.BarChart, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Alle Statistiken")
        }
    }
}

@Composable
private fun WindowCard(window: UsageWindow, now: Long, index: Int) {
    val colors = MaterialTheme.colorScheme
    val accent = windowColor(window, colors)
    val animated by rememberFillProgress((window.usedPercent / 100).toFloat().coerceIn(0f, 1f), 100)
    SoftCard {
        Text(window.title, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(112.dp).semantics { contentDescription = "${window.title}: ${percent(window.usedPercent)} Prozent verbraucht" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val inset = 8.dp.toPx()
                    val bounds = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(accent.copy(alpha = .12f), 140f, 260f, false, Offset(inset, inset), bounds, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                    if (animated > 0) drawArc(accent, 140f, 260f * animated, false, Offset(inset, inset), bounds, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${rememberCountingPercent(window.usedPercent)}%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("genutzt", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Nächster Reset", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                val at = window.resetAt
                Text(if (at == null) "Nicht gemeldet" else if (at <= now) "Erreicht" else "${TimeText.clock(at, now)} Uhr", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                if (at != null) Text(if (at <= now) "seit ${TimeText.clock(at, now)} Uhr" else TimeText.remaining(at - now), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (at != null && at > now) Text(date(at), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        window.elapsed(now)?.let { elapsed ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageTrack(elapsed, colors.onSurfaceVariant.copy(alpha = .6f), Modifier.weight(1f).height(3.dp))
                Text("${percent(elapsed.toDouble() * 100)}% der Zeit", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun BankedCard(usage: UsageSnapshot, now: Long, loading: Boolean, title: String? = null) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.ConfirmationNumber, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(usage.availableResets?.let { "$it ${if (it == 1) "Reset verfügbar" else "Resets verfügbar"}" } ?: "Resets", style = MaterialTheme.typography.titleSmall)
                if (title != null) Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (loading) Text("Details werden geladen…", style = MaterialTheme.typography.labelSmall)
        usage.resetCredits.distinctBy { listOf(it.status, it.grantedAt, it.expiresAt, it.title) }.forEachIndexed { index, credit ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.RestartAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(creditStatus(credit.status), style = MaterialTheme.typography.labelLarge)
                    credit.expiresAt?.let { Text("Ablauf ${date(it)}${if (it <= now && credit.status != "expired") " · erreicht" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        usage.creditsWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun creditStatus(status: String) = when (status) {
    "available" -> "Reset"
    "redeemed" -> "Reset · eingelöst"
    "paused" -> "Reset · pausiert"
    "expired" -> "Reset · abgelaufen"
    "unknown" -> "Reset · Verfügbarkeit unbekannt"
    else -> "Reset · derzeit nicht verfügbar"
}

@Composable
private fun ExtraUsageCard(extra: ExtraUsage) {
    fun money(value: Double?) = extraAmountText(value, extra.currency, extra.decimals)
    SoftCard {
        Text("Zusatzverbrauch", style = MaterialTheme.typography.titleMedium)
        Text(when (extra.enabled) { true -> "Aktiviert"; false -> "Deaktiviert"; null -> "Aktivierung nicht gemeldet" }, style = MaterialTheme.typography.labelMedium)
        if (extra.enabled == true || extra.used != null || extra.limit != null) {
            Text(money(extra.used), style = MaterialTheme.typography.headlineSmall)
            Text("Monatslimit: ${money(extra.limit)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Spinner for the app bar; shows the refresh icon while idle. */
@Composable
internal fun RefreshButton(refreshing: Boolean, label: String = "Jetzt aktualisieren", onClick: () -> Unit) {
    // The endless animation only exists while something is loading, so an idle screen draws nothing.
    val angle = if (refreshing) rememberInfiniteTransition(label = "refresh").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle").value else 0f
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Rounded.Refresh, label, Modifier.graphicsLayer { rotationZ = angle })
    }
}
