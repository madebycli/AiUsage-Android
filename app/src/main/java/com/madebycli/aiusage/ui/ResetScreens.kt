@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.AccountState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The resets page: what the public trackers say about the next global reset, side by side, plus the history. */
@Composable
internal fun ResetScreen(state: UiState, now: Long, provider: String, select: (String) -> Unit, open: (String, String) -> Unit) {
    val names = state.connectedProviders
    val active = provider.takeIf { it in names } ?: names.firstOrNull() ?: return
    val outlook = remember(state.intel, active, now / 30) { ResetOutlookBuilder.build(active, state.intel, now) }
    val problems = outlook.sources.filter { it.state == SourceState.ERROR }
    val loaded = outlook.sources.any { it.state != SourceState.EMPTY }
    val banked = state.accountsOf(active).filter { entry -> entry.usage?.let { it.availableResets != null || it.resetCredits.isNotEmpty() || it.creditsWarning != null } == true }
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxSize()) {
        if (names.size > 1) Box(Modifier.padding(20.dp, 4.dp, 20.dp, 8.dp)) { ProviderTabs(names, active, select) }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.intelLoading && !loaded) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (problems.isNotEmpty()) item(key = "problems") {
                Notice(problems.joinToString("\n") { "${it.source.label}: ${it.error}" }, error = outlook.horizons.isEmpty() && outlook.timeline.isEmpty())
            }
            item(key = "last") { LastResetCard(outlook, now, active, loaded || state.intelLoading) }
            if (outlook.horizons.isNotEmpty()) {
                item(key = "outlook-title") { SectionTitle("Wahrscheinlichkeit") }
                item(key = "outlook") { OutlookCard(outlook, now) }
            }
            if (outlook.hints.isNotEmpty()) item(key = "hints") { HintsCard(outlook) }
            if (outlook.resets.isNotEmpty()) {
                item(key = "history-title") { SectionTitle("Verlauf") }
                item(key = "history") {
                    SoftCard {
                        val shown = outlook.resets.take(5)
                        val gaps = ResetOutlookBuilder.gapsDays(outlook.resets)
                        shown.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
                            ResetRow(entry, gaps[index])
                        }
                        TextButton(onClick = { open(active, "Verlauf") }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Rounded.History, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Gesamten Verlauf ansehen")
                        }
                    }
                }
            }
            item(key = "sources") { MenuCard { MenuRow("Quellen", Icons.Rounded.QueryStats, "${outlook.sources.count { it.state == SourceState.FRESH }} von ${outlook.sources.size} aktuell") { open(active, "Quellen") } } }
            if (banked.isNotEmpty()) item(key = "stock-title") { SectionTitle("Dein Vorrat") }
            banked.forEach { entry ->
                item(key = "stock:${entry.id}") { BankedCard(entry.usage!!, now, entry.creditsLoading, title = if (state.accountsOf(active).size > 1) entry.title else null) }
            }
        }
    }
}

@Composable
private fun LastResetCard(outlook: ResetOutlook, now: Long, provider: String, loading: Boolean) {
    val colors = MaterialTheme.colorScheme
    SoftCard {
        Text("Letzter Reset", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        val last = outlook.lastResetAt
        if (last == null) {
            Text(if (loading) "Wird geladen…" else "Noch keine Angabe", style = MaterialTheme.typography.headlineSmall)
            return@SoftCard
        }
        val dayCount = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochSecond(last).atZone(ZoneOffset.UTC).toLocalDate(), Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate())
        Text(if (outlook.lastResetTimeKnown) Words.ago(now - last) else when (dayCount) { 0L -> "heute"; 1L -> "gestern"; else -> "vor $dayCount Tagen" },
            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(if (outlook.lastResetTimeKnown) "${Words.date(last)} · ${Words.time(last)} Uhr" else "${Words.dateYear(last)} (Uhrzeit unbekannt)",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        outlook.rhythm?.let { rhythm ->
            Spacer(Modifier.height(6.dp))
            RhythmMeter((now - last) / 86_400.0, rhythm)
            val since = (now - last) / 86_400.0
            Text(when {
                since < rhythm.medianDays -> "Noch innerhalb des üblichen Abstands (Median ${Words.days(rhythm.medianDays)})."
                since <= rhythm.longestDays -> "Länger als üblich (Median ${Words.days(rhythm.medianDays)}), aber kürzer als der längste Abstand."
                else -> "Länger als jeder der letzten ${rhythm.sample} Abstände."
            }, style = MaterialTheme.typography.bodyMedium)
            Text("Median ${Words.days(rhythm.medianDays)} · Ø ${Words.days(rhythm.meanDays)} · längster ${Words.days(rhythm.longestDays)} · ${rhythm.sample} Abstände",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        outlook.window?.let { window ->
            val (from, to) = ResetOutlookBuilder.localHours(window, now)
            Text("Resets kamen meist zwischen $from und $to Uhr.", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        if (provider == Providers.CLAUDE) Text("Claude-Resets sind selten und werden nur als Datum gemeldet.", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/** Time since the last reset on a line, with the usual gap and the longest gap marked. */
@Composable
private fun RhythmMeter(sinceDays: Double, rhythm: Rhythm) {
    val colors = MaterialTheme.colorScheme
    val scale = maxOf(rhythm.longestDays, sinceDays) * 1.08
    val position by rememberFillProgress((sinceDays / scale).toFloat())
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        val h = 8.dp.toPx()
        val top = (size.height - h) / 2
        drawRoundRect(colors.surfaceContainerHighest, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
        val usual = (size.width * (rhythm.medianDays / scale)).toFloat()
        val longest = (size.width * (rhythm.longestDays / scale)).toFloat()
        drawRoundRect(colors.primary.copy(alpha = .22f), Offset(0f, top), Size(usual.coerceAtLeast(h), h), CornerRadius(h / 2))
        drawLine(colors.primary, Offset(usual, top - 4.dp.toPx()), Offset(usual, top + h + 4.dp.toPx()), 2.dp.toPx())
        drawLine(colors.onSurfaceVariant.copy(alpha = .6f), Offset(longest, top - 4.dp.toPx()), Offset(longest, top + h + 4.dp.toPx()), 2.dp.toPx())
        val x = (size.width * position).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx())
        drawCircle(colors.surface, 9.dp.toPx(), Offset(x, size.height / 2))
        drawCircle(if (sinceDays > rhythm.longestDays) colors.error else colors.primary, 6.dp.toPx(), Offset(x, size.height / 2))
    }
}

private fun horizonTitle(hours: Int) = when {
    hours >= 96 && hours % 24 == 0 -> "In den nächsten ${hours / 24} Tagen"
    else -> "In den nächsten $hours Stunden"
}

@Composable
private fun OutlookCard(outlook: ResetOutlook, now: Long) {
    val colors = MaterialTheme.colorScheme
    SoftCard {
        outlook.horizons.forEachIndexed { index, horizon ->
            if (index > 0) HorizontalDivider(color = colors.outlineVariant.copy(alpha = .35f))
            Text(horizonTitle(horizon.hours), style = MaterialTheme.typography.titleSmall)
            horizon.rows.forEach { row ->
                val tint = if (row.fresh) colors.primary else colors.onSurfaceVariant.copy(alpha = .6f)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.source.label, style = MaterialTheme.typography.bodyMedium)
                            if (!row.fresh) Text("veraltet · ${Words.ago(now - row.fetchedAt)}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${percent(row.chance.percent)} %", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (row.fresh) colors.onSurface else colors.onSurfaceVariant)
                            if (row.chance.low != null && row.chance.high != null) Text("${percent(row.chance.low)}–${percent(row.chance.high)} %", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                    RangeBar(row.chance.percent, row.chance.low, row.chance.high, tint)
                }
            }
            if (horizon.disagree) Surface(shape = RoundedCornerShape(14.dp), color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
                Text("Die Quellen weichen um ${percent(horizon.spread!!)} Punkte ab. Sie rechnen unterschiedlich, deshalb gibt es hier keinen Mittelwert.",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(12.dp))
            }
        }
        Text("Schätzungen der Seiten aus der bisherigen Reset-Historie. Keine Zusage von OpenAI oder Anthropic.", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun HintsCard(outlook: ResetOutlook) {
    SoftCard {
        Text("Hinweise der Quellen", style = MaterialTheme.typography.titleSmall)
        outlook.hints.distinct().forEachIndexed { index, (source, text) ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Text(source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        outlook.expected.forEach { (source, at) ->
            Text("${source.label} erwartet den nächsten Reset am ${Words.date(at)} um ${Words.time(at)} Uhr. Das ist eine spielerische Schätzung.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One reset: date, kind, time, what was said, how long after the previous one, and a link to the original. */
@Composable
internal fun ResetRow(entry: ResetEntry, gapDays: Double?) {
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val moment = Instant.ofEpochSecond(entry.at).atZone(ZoneId.systemDefault())
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(moment.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(moment.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMAN).removeSuffix("."), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                KindChip(entry.kind)
                if (!entry.verified) Chip("unbestätigt", colors.errorContainer, colors.onErrorContainer)
                Text(if (entry.timeKnown) "${Words.date(entry.at)} · ${Words.time(entry.at)}" else Words.date(entry.at), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            entry.detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
            gapDays?.takeIf { entry.kind != ResetKind.BANKED }?.let { Text("${Words.days(it)} nach dem vorigen", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        }
        entry.url?.let { link -> IconButton(onClick = { uri.openUri(link) }, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Originalquelle öffnen", Modifier.size(18.dp)) } }
    }
}

@Composable
private fun KindChip(kind: ResetKind) {
    val colors = MaterialTheme.colorScheme
    when (kind) {
        ResetKind.GLOBAL -> Chip(kind.label, colors.primaryContainer, colors.onPrimaryContainer)
        ResetKind.PARTIAL -> Chip(kind.label, colors.secondaryContainer, colors.onSecondaryContainer)
        ResetKind.BANKED -> Chip(kind.label, colors.tertiaryContainer, colors.onTertiaryContainer)
        ResetKind.EVENT -> Chip(kind.label, colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1)
    }
}

/** Full history and source status, opened from the resets page. */
@Composable
internal fun ResetDetailsScreen(state: UiState, now: Long, model: UsageViewModel, provider: String, destination: String) {
    val outlook = remember(state.intel, provider, now / 30) { ResetOutlookBuilder.build(provider, state.intel, now) }
    if (destination == "Quellen") SourcesList(outlook, now) else HistoryList(outlook)
}

private enum class HistoryFilter(val title: String) { ALL("Alle"), RESETS("Resets"), BANKED("Gutschriften") }

@Composable
private fun HistoryList(outlook: ResetOutlook) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val entries = outlook.timeline.filter { when (filter) { HistoryFilter.ALL -> true; HistoryFilter.RESETS -> it.kind != ResetKind.BANKED; HistoryFilter.BANKED -> it.kind == ResetKind.BANKED } }
    val gaps = ResetOutlookBuilder.gapsDays(outlook.timeline.filter { it.kind != ResetKind.BANKED }).let { list -> outlook.timeline.filter { it.kind != ResetKind.BANKED }.zip(list).toMap() }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "filter") { SegmentedTabGroup(HistoryFilter.entries, filter, { filter = it }, { it.title }, fillEqually = true) }
        if (entries.isEmpty()) item { SoftCard { Text("Noch keine Meldungen", style = MaterialTheme.typography.titleMedium) } }
        val byMonth = entries.groupBy { Instant.ofEpochSecond(it.at).atZone(ZoneId.systemDefault()).let { d -> d.year to d.monthValue } }
        byMonth.forEach { (month, rows) ->
            item(key = "m${month.first}-${month.second}") {
                Text(java.time.Month.of(month.second).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.GERMAN) + " ${month.first}",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
            item(key = "c${month.first}-${month.second}") {
                SoftCard {
                    rows.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
                        ResetRow(entry, gaps[entry])
                    }
                }
            }
        }
        item(key = "note") { Notice("Gleiche Resets mehrerer Quellen sind zu einer Zeile zusammengefasst. Die Uhrzeit ist der Zeitpunkt der Meldung, nicht unbedingt des Resets selbst.") }
    }
}

@Composable
private fun SourcesList(outlook: ResetOutlook, now: Long) {
    val uri = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(outlook.sources, key = { it.source.name }) { status ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(status.source.label, style = MaterialTheme.typography.titleMedium)
                        Text(status.source.method, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = { uri.openUri(status.source.site) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "${status.source.label} öffnen", Modifier.size(20.dp)) }
                }
                when (status.state) {
                    SourceState.FRESH -> Chip("Aktuell", colors.primaryContainer, colors.onPrimaryContainer)
                    SourceState.STALE -> Chip("Veraltet", colors.tertiaryContainer, colors.onTertiaryContainer)
                    SourceState.ERROR -> Chip("Fehler", colors.errorContainer, colors.onErrorContainer)
                    SourceState.EMPTY -> Chip("Noch nichts geladen", colors.surfaceContainerHighest, colors.onSurfaceVariant)
                }
                status.fetchedAt?.let { Text("Abgerufen ${Words.ago(now - it)}${status.publishedAt?.let { p -> " · Stand der Seite ${Words.ago(now - p)}" }.orEmpty()}", style = MaterialTheme.typography.labelMedium) }
                status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                status.partial?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                outlook.horizons.flatMap { it.rows }.filter { it.source == status.source }.forEach { row ->
                    Text("${horizonTitle(row.chance.hours)}: ${percent(row.chance.percent)} %" + (row.chance.low?.let { " (${percent(it)}–${percent(row.chance.high ?: it)} %)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "note") { Notice("Fehlt eine Quelle oder ist sie veraltet, zählt sie nicht. Fehlende Werte stehen nie als 0 %.") }
    }
}
