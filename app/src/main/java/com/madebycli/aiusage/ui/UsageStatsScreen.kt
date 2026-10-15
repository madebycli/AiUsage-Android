package com.madebycli.aiusage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madebycli.aiusage.ProviderState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** The statistics tab: provider switch, activity calendar and the provider's reports. */
@Composable
internal fun StatsTab(state: UiState, model: UsageViewModel, now: Long, provider: String, select: (String) -> Unit) {
    val names = state.connectedProviders
    val active = provider.takeIf { it in names } ?: names.firstOrNull() ?: return
    LaunchedEffect(active) { model.refreshStats(active) }
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxSize()) {
        if (names.size > 1) Box(Modifier.padding(20.dp, 4.dp, 20.dp, 8.dp)) { ProviderTabs(names, active, select) }
        if (active == Providers.CODEX) CodexStats(state, now) else ClaudeStats(state, now)
    }
}

@Composable
private fun ActivityCalendar(provider: String, account: ProviderState, now: Long) {
    val reported = if (provider == Providers.CODEX) reportedActivity(account.stats) else emptyList()
    UsageCalendar(provider, reported.ifEmpty { account.days }, now)
}

@Composable
private fun CodexStats(state: UiState, now: Long) {
    val account = state.codex
    val uri = LocalUriHandler.current
    val reports = account.stats.ordered()
    var selectedReport by rememberSaveable { mutableStateOf(CodexReport.PROFILE.id) }
    var period by rememberSaveable { mutableIntStateOf(30) }
    var metricKey by rememberSaveable(selectedReport) { mutableStateOf("") }
    var series by rememberSaveable(selectedReport, metricKey) { mutableStateOf("") }
    val report = reports.firstOrNull { it.id == selectedReport } ?: reports.firstOrNull { it.points.isNotEmpty() } ?: reports.firstOrNull()
    val metrics = report?.points.orEmpty().map { it.metric to it.unit }.distinct()
    val metric = metrics.firstOrNull { "${it.first}|${it.second}" == metricKey } ?: metrics.firstOrNull { it.first == "tokens" } ?: metrics.firstOrNull()
    val candidates = report?.points.orEmpty().filter { it.metric == metric?.first && it.unit == metric?.second }
    val labels = candidates.map { it.label }.distinct().sorted()
    val label = series.takeIf { it in labels } ?: labels.firstOrNull { it == "Gesamt" } ?: labels.firstOrNull()
    val end = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate()
    val points = candidates.filter { it.label == label && (it.day == null || it.day >= end.minusDays((period - 1).toLong()).toString()) }
    val daily = points.filter { it.day != null && it.day <= end.toString() }.groupBy { it.day!! }.mapValues { (_, values) -> values.sumOf { it.value } }.toSortedMap()
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("Aktivitätsverlauf") }
        item { ActivityCalendar(Providers.CODEX, account, now) }
        if (reports.size > 1) item { SegmentedTabGroup(reports.map { it.id }, report?.id.orEmpty(), { selectedReport = it }, { id -> reports.first { it.id == id }.title }) }
        if (account.statsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        account.statsError?.let { item { Notice(it, true) } }
        if (report == null) item { SoftCard {
            Icon(Icons.Rounded.QueryStats, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Text(if (account.statsLoading) "Berichte werden geladen" else "Noch keine Statistiken", style = MaterialTheme.typography.headlineSmall)
            Text("Die Werte kommen direkt von OpenAI, sobald der Anbieter sie für deinen Account freigibt.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } } else {
            item { SectionTitle(report.title) }
            item { SoftCard {
                Text("${report.scope} · von OpenAI gemeldet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                report.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                report.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (metric != null) {
                    if (metrics.size > 1) Picker("Messwert", metrics.map { "${it.first}|${it.second}" }, "${metric.first}|${metric.second}", { metricKey = it }, { metricLabel(it.substringBefore('|')) })
                    if (labels.size > 1) Picker("Kategorie", labels, label.orEmpty(), { series = it }, { it })
                    if (daily.isNotEmpty()) {
                        SegmentedTabGroup(listOf(7, 30), period, { period = it }, { "$it Tage" }, fillEqually = true)
                        val today = daily[end.toString()]
                        if (today != null) {
                            Text("Heute (UTC)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BigStatNumber(today, metric.second)
                        }
                        Text("Summe der letzten $period Tage", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (today != null) Text("${formatLarge(daily.values.sum())} ${metric.second}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        else BigStatNumber(daily.values.sum(), metric.second)
                        DailyStatsChart(List(period) { index -> daily[end.minusDays((period - index - 1).toLong()).toString()] })
                        Text("${daily.keys.first()} – ${daily.keys.last()} · ${daily.count { it.value > 0 }} aktive Tage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else points.firstOrNull()?.let { point ->
                        BigStatNumber(point.value, point.unit)
                        Text("Gesamtzeitraum des Berichts", style = MaterialTheme.typography.labelMedium)
                    }
                } else Text(if (report.error != null) "Keine lesbaren Werte" else "OpenAI meldet keine Werte für diesen Bericht.", style = MaterialTheme.typography.bodyMedium)
                if (report.fetchedAt > 0) Text("Abgerufen ${date(report.fetchedAt)}${report.publishedAt?.let { " · Stand ${date(it)}" }.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            val undated = report.points.filter { it.day == null }
            if (undated.isNotEmpty()) {
                item { SectionTitle("Gesamtzeitraum") }
                item { SoftCard { undated.take(80).forEachIndexed { index, point ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                    StatLine("${point.label} · ${metricLabel(point.metric)}", "${formatLarge(point.value)} ${point.unit}")
                } } }
            }
            if (daily.isNotEmpty()) {
                item { SectionTitle("Tageswerte") }
                item { SoftCard { daily.entries.reversed().forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                    StatLine(entry.key, "${formatLarge(entry.value)} ${metric?.second.orEmpty()}")
                } } }
            }
        }
        item { TextButton(onClick = { uri.openUri("https://chatgpt.com/codex/settings/usage") }) {
            Text("Nutzung bei OpenAI öffnen"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
        } }
    }
}

/** Claude reports quota percentages only. The history lists exactly what Claude returned at each read. */
@Composable
private fun ClaudeStats(state: UiState, now: Long) {
    val account = state.claude
    val uri = LocalUriHandler.current
    var period by rememberSaveable { mutableIntStateOf(30) }
    val today = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val byDay = account.days.associateBy { it.date }
    val range = List(period) { today.minusDays((period - it - 1).toLong()) }
    val usage = account.usage
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        account.error?.let { item { Notice(it, true) } }
        item { SectionTitle("Aktivitätsverlauf") }
        item { ActivityCalendar(Providers.CLAUDE, account, now) }
        item { SectionTitle("Aktuelle Limits") }
        item { SoftCard {
            val groups = usage?.groups.orEmpty().filter { it.windows.isNotEmpty() }
            if (groups.isEmpty()) Text(if (account.refreshing) "Wird geladen…" else "Claude hat noch keine Limits gemeldet.", style = MaterialTheme.typography.bodyMedium)
            groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                group.windows.forEach { window -> LimitRow(if (group.name == Providers.CLAUDE) window.title else "${group.name} · ${window.title}", window, now) }
            }
            usage?.extraUsage?.takeIf { it.enabled == true }?.let { extra ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                StatLine("Zusatzverbrauch", "${extraAmountText(extra.used, extra.currency, extra.decimals)} von ${extraAmountText(extra.limit, extra.currency, extra.decimals)}")
            }
        } }
        item { SectionTitle("Verlauf") }
        item { SoftCard {
            SegmentedTabGroup(listOf(7, 30), period, { period = it }, { "$it Tage" }, fillEqually = true)
            Text("Höchster 5-Stunden-Wert pro Tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DailyStatsChart(range.map { byDay[it.toString()]?.peak }, maximum = 100.0)
            Text("Wochenlimit, letzter Wert pro Tag", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DailyStatsChart(range.map { byDay[it.toString()]?.weekly }, maximum = 100.0)
            Text("Werte so, wie Claude sie bei der Aktualisierung gemeldet hat. Tage ohne Aktualisierung bleiben leer.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        val recorded = range.reversed().mapNotNull { day -> byDay[day.toString()]?.takeIf { it.peak != null || it.weekly != null } }
        if (recorded.isNotEmpty()) {
            item { SectionTitle("Tageswerte") }
            item { SoftCard { recorded.forEachIndexed { index, day ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                StatLine(day.date, listOfNotNull(day.peak?.let { "5 Std. ${percent(it)}%" }, day.weekly?.let { "Woche ${percent(it)}%" }).joinToString(" · "))
            } } }
        }
        item { Notice("Claude liefert über die Anmeldung Limits, Resets und Zusatzverbrauch, aber keine Token- oder Nachrichtenzahlen.") }
        item { TextButton(onClick = { uri.openUri("https://claude.ai/settings/usage") }) {
            Text("Nutzung bei Claude öffnen"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
        } }
    }
}

@Composable
private fun LimitRow(title: String, window: UsageWindow, now: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${percent(window.usedPercent)}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        UsageTrack((window.usedPercent / 100).toFloat(), if (window.usedPercent >= 100) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(6.dp))
        window.resetAt?.let { Text(if (it > now) "Reset in ${countdown(it - now)} · ${date(it)}" else "Reset fällig", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun BigStatNumber(value: Double, unit: String) {
    Text(formatLarge(value), style = MaterialTheme.typography.displayMedium.copy(fontSize = 44.sp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    Text(unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Picker(title: String, options: List<String>, selected: String, select: (String) -> Unit, label: (String) -> String) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$title: ${label(selected)}"); Icon(Icons.Rounded.ExpandMore, null) }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(label(option)) }, onClick = { select(option); open = false }) }
        }
    }
}

@Composable
private fun DailyStatsChart(values: List<Double?>, maximum: Double? = null) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp)) {
        val top = maximum ?: values.filterNotNull().maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        val signed = values.any { it != null && it < 0 }
        val baseline = if (signed) size.height / 2 else size.height
        val plotHeight = if (signed) size.height / 2 else size.height
        if (signed) drawLine(colors.outlineVariant, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        val gap = 3.dp.toPx()
        val width = ((size.width - gap * (values.size - 1)) / values.size.coerceAtLeast(1)).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            val bar = value ?: run {
                drawRoundRect(colors.surfaceContainerHighest, Offset(index * (width + gap), baseline - 2.dp.toPx()), Size(width, 2.dp.toPx()), CornerRadius(1f, 1f))
                return@forEachIndexed
            }
            val height = (kotlin.math.abs(bar) / top * (plotHeight - 4.dp.toPx())).toFloat().coerceIn(2.dp.toPx(), plotHeight)
            drawRoundRect(if (bar < 0) colors.tertiary else colors.primary.copy(alpha = (.35 + .65 * (kotlin.math.abs(bar) / top).coerceIn(0.0, 1.0)).toFloat()),
                Offset(index * (width + gap), if (bar < 0) baseline else baseline - height), Size(width, height), CornerRadius(width / 2, width / 2))
        }
    }
}

@Composable
private fun StatLine(title: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** German short scale: Mio. 10^6, Mrd. 10^9, Bio. 10^12, Brd. 10^15; the exact value stays in the list rows. */
internal fun formatLarge(value: Double): String {
    val magnitude = kotlin.math.abs(value)
    return when {
        magnitude >= 1e15 -> "${number(value / 1e15)} Brd."
        magnitude >= 1e12 -> "${number(value / 1e12)} Bio."
        magnitude >= 1e9 -> "${number(value / 1e9)} Mrd."
        magnitude >= 1e6 -> "${number(value / 1e6)} Mio."
        else -> number(value, if (magnitude < 1) 6 else 2)
    }
}

private fun metricLabel(key: String): String = when (key) {
    "lifetime_tokens" -> "Tokens insgesamt"; "peak_daily_tokens" -> "Stärkster Tag · Tokens"; "tokens" -> "Tokens"
    "current_streak_days" -> "Aktuelle Serie"; "longest_streak_days" -> "Längste Serie"; "longest_running_turn_sec" -> "Längster Turn"
    "total_threads" -> "Chats"; "total_skills_used" -> "Skill-Aufrufe"; "unique_skills_used" -> "Verschiedene Skills"
    "input_tokens", "uncached_input_tokens", "uncached_text_input_tokens" -> "Input-Tokens"
    "output_tokens", "text_output_tokens" -> "Output-Tokens"
    "input_cached_tokens", "cached_text_input_tokens" -> "Cache-Tokens"
    "turns" -> "Turns"; "threads" -> "Chats"
    else -> key.replace('_', ' ').replaceFirstChar(Char::titlecase)
}
