package com.madebycli.aiusage.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

@Composable
internal fun UsageStatsScreen(provider: String, state: UiState, model: UsageViewModel, now: Long) {
    val stats = if (provider == "Codex") state.stats else state.claudeStats
    val loading = if (provider == "Codex") state.statsLoading else state.claudeStatsLoading
    val error = if (provider == "Codex") state.statsError else state.claudeStatsError
    val connected = if (provider == "Codex") state.apiConnected else state.claudeApiConnected
    var selectedReport by rememberSaveable(provider) { mutableStateOf(if (provider == "Codex") CodexReport.PROFILE.id else "claude.file") }
    var period by rememberSaveable(provider) { mutableIntStateOf(30) }
    var metricKey by rememberSaveable(provider, selectedReport) { mutableStateOf("") }
    var series by rememberSaveable(provider, selectedReport, metricKey) { mutableStateOf("") }
    var sources by rememberSaveable(provider) { mutableStateOf(false) }
    var key by remember { mutableStateOf("") } // Never save credentials in saved-instance state.
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val coroutine = rememberCoroutineScope()
    var fileError by remember { mutableStateOf<String?>(null) }
    val fileImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { document ->
        if (document != null) coroutine.launch {
            try {
                val body = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(document)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; require(output.size() + count <= 1_048_576); output.write(buffer, 0, count) }
                    output.toString("UTF-8")
                } ?: throw IllegalArgumentException("Datei unlesbar") }
                fileError = null; model.importClaudeStats(body)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { fileError = "Datei konnte nicht gelesen werden (maximal 1 MiB)." }
        }
    }
    LaunchedEffect(provider) { model.refreshStats(provider) }
    val report = stats.reports.firstOrNull { it.id == selectedReport } ?: stats.reports.firstOrNull { it.points.isNotEmpty() } ?: stats.reports.firstOrNull()
    val metrics = report?.points.orEmpty().map { it.metric to it.unit }.distinct()
    val metric = metrics.firstOrNull { "${it.first}|${it.second}" == metricKey } ?: metrics.firstOrNull { it.first == "tokens" || it.first == "Nachrichten" } ?: metrics.firstOrNull()
    val candidates = report?.points.orEmpty().filter { it.metric == metric?.first && it.unit == metric?.second }
    val labels = candidates.map { it.label }.distinct().sorted()
    val label = series.takeIf { it in labels } ?: labels.firstOrNull { it == "Gesamt" } ?: labels.firstOrNull()
    val end = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate()
    val points = candidates.filter { it.label == label && (it.day == null || it.day >= end.minusDays((period - 1).toLong()).toString()) }
    val daily = points.filter { it.day != null && it.day <= end.toString() }.groupBy { it.day!! }.mapValues { (_, values) -> values.sumOf { it.value } }.toSortedMap()
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SegmentedTabGroup(stats.reports.map { it.id }, report?.id.orEmpty(), { selectedReport = it }, { id -> stats.reports.first { it.id == id }.title })
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        fileError?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        if (report != null) {
            item { SectionTitle(report.title) }
            item { SoftCard {
                Text(report.scope, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                report.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                report.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (metric != null) {
                    MetricPicker("Messwert", metrics.map { "${it.first}|${it.second}" }, "${metric.first}|${metric.second}", { metricKey = it }, { metricLabel(it.substringBefore('|')) })
                    MetricPicker("Produkt / Modell / Kategorie", labels, label.orEmpty(), { series = it }, { it })
                    if (daily.isNotEmpty()) {
                        SegmentedTabGroup(listOf(7, 30), period, { period = it }, { "$it Tage" }, fillEqually = true)
                        Text(statsNumber(daily.values.sum()), style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(metric.second, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DailyStatsChart(List(period) { index -> daily[end.minusDays((period - index - 1).toLong()).toString()] })
                        Text("${daily.keys.first()} – ${daily.keys.last()} · ${daily.count { it.value > 0 }} aktive Tage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else points.firstOrNull()?.let { point ->
                        Text(statsNumber(point.value), style = MaterialTheme.typography.displayLarge.copy(fontSize = 56.sp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("${point.unit} · Gesamtzeitraum des Berichts", style = MaterialTheme.typography.labelMedium)
                    }
                } else Text(if (report.error != null) "Keine lesbaren Werte" else "Der Anbieter meldet keine Werte für diesen Bericht.", style = MaterialTheme.typography.bodyMedium)
                if (report.fetchedAt > 0) Text("Abgerufen ${statsDate(report.fetchedAt)}${report.publishedAt?.let { " · Quellenstand ${statsDate(it)}" }.orEmpty()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            val undated = report.points.filter { it.day == null }
            if (undated.isNotEmpty()) {
                item { SectionTitle("Gesamtzeitraum") }
                item { SoftCard {
                    undated.take(80).forEachIndexed { index, point ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                        StatLine("${point.label} · ${metricLabel(point.metric)}", "${statsNumber(point.value)} ${point.unit}")
                    }
                } }
            }
            if (daily.isNotEmpty()) {
                item { SectionTitle("Tageswerte") }
                item { SoftCard {
                    daily.entries.reversed().forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                        StatLine(entry.key, "${statsNumber(entry.value)} ${metric?.second.orEmpty()}")
                    }
                } }
            }
        } else item { SoftCard {
            Icon(Icons.Rounded.QueryStats, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Text(if (loading) "Berichte werden geladen" else "Deine Statistiken", style = MaterialTheme.typography.headlineSmall)
            Text(if (provider == "Codex") "Tageswerte werden mit deinem Account abgerufen, sofern OpenAI sie freigibt." else "Claude meldet über die persönliche Anmeldung Limits und Zusatzverbrauch. Für Nachrichten, Sitzungen und Modell-Tokens einen Claude-Code-Bericht importieren; API-Abrechnung separat verbinden.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { SoftCard {
            TextButton(onClick = { sources = !sources }) { Icon(Icons.Rounded.Storage, null); Spacer(Modifier.width(8.dp)); Text("Datenquellen") }
            if (sources) {
                if (provider == "Claude Code") {
                    Text("Claude Code · lokale Statistiken", style = MaterialTheme.typography.titleSmall)
                    Text("stats-cache.json aus deinem Claude-Code-Verzeichnis wählen. Erneutes Importieren ersetzt den Bericht; es wird nichts doppelt addiert.", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { fileImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !loading) { Text("Claude-Code-Bericht importieren") }
                }
                Text("API · Organisation", style = MaterialTheme.typography.titleSmall)
                Text("Optional: separater Admin-API-Key für 30 Tage Organisationsverbrauch und Kosten. Das ist keine Abrechnung deines ChatGPT-/Claude-Abos. Der Key bleibt verschlüsselt auf diesem Gerät; die App nutzt nur Berichts-GETs und ruft keine Modelle auf.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connected) TextButton(onClick = { model.disconnectAnalytics(provider) }) { Text("API-Verbindung trennen") }
                else {
                    OutlinedTextField(key, { key = it }, label = { Text("Admin-API-Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    FilledTonalButton(onClick = { model.connectAnalytics(provider, key); key = "" }, enabled = !loading && key.isNotBlank()) { Text("API verbinden und prüfen") }
                }
                TextButton(onClick = { uri.openUri(if (provider == "Codex") "https://chatgpt.com/codex/settings/usage" else "https://claude.ai/settings/usage") }) { Text("Nutzung beim Anbieter öffnen"); Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)) }
                Text("API-Berichte sind getrennte Datenquellen. Nicht freigegebene Cowork-/ChatGPT-Analysen werden nicht aus Abo-Prozenten geschätzt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }
}

@Composable
private fun MetricPicker(title: String, options: List<String>, selected: String, select: (String) -> Unit, label: (String) -> String) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$title: ${label(selected)}", modifier = Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore, null) }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(label(option)) }, onClick = { select(option); open = false }) }
        }
    }
}
@Composable
private fun DailyStatsChart(values: List<Double?>) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(164.dp).padding(top = 12.dp)) {
        val maximum = values.filterNotNull().maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1.0) ?: 1.0
        val signed = values.any { it != null && it < 0 }
        val baseline = if (signed) size.height / 2 else size.height
        val plotHeight = if (signed) size.height / 2 else size.height
        if (signed) drawLine(colors.outlineVariant, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        val gap = 4.dp.toPx()
        val width = ((size.width - gap * (values.size - 1)) / values.size.coerceAtLeast(1)).coerceAtLeast(1f)
        values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            val height = (kotlin.math.abs(value) / maximum * (plotHeight - 4.dp.toPx())).toFloat().coerceAtLeast(2.dp.toPx())
            drawRoundRect(if (value < 0) colors.tertiary else colors.primary.copy(alpha = (.35 + .65 * kotlin.math.abs(value) / maximum).toFloat()),
                Offset(index * (width + gap), if (value < 0) baseline else baseline - height), Size(width, height), CornerRadius(width / 2, width / 2))
        }
    }
}
@Composable
private fun StatLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Circle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}
private fun statsNumber(value: Double) = NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = if (kotlin.math.abs(value) < 1) 6 else 2 }.format(value)
private fun statsDate(at: Long) = Instant.ofEpochSecond(at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM. · HH:mm"))
private fun metricLabel(key: String): String = when (key) {
    "lifetime_tokens" -> "Tokens insgesamt"; "peak_daily_tokens" -> "Stärkster Tag · Tokens"; "tokens" -> "Tokens"
    "current_streak_days" -> "Aktuelle Serie"; "longest_streak_days" -> "Längste Serie"; "longest_running_turn_sec" -> "Längster Turn"
    "total_threads" -> "Chats"; "total_skills_used" -> "Skill-Aufrufe"; "unique_skills_used" -> "Verschiedene Skills"
    "input_tokens", "inputTokens", "uncached_input_tokens", "uncached_text_input_tokens" -> "Input-Tokens"
    "output_tokens", "outputTokens", "text_output_tokens" -> "Output-Tokens"
    "input_cached_tokens", "cache_read_input_tokens", "cacheReadInputTokens", "cached_text_input_tokens" -> "Cache-Tokens"
    "totalMessages" -> "Nachrichten"; "totalSessions" -> "Sitzungen"; "threads" -> "Chats"; "turns" -> "Turns"
    else -> key.replace('_', ' ').replaceFirstChar(Char::titlecase)
}
