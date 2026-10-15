@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.data.*

@Composable
internal fun ResetScreen(state: UiState, now: Long, provider: String, select: (String) -> Unit, open: (String, String) -> Unit) {
    val names = state.connectedProviders
    val active = provider.takeIf { it in names } ?: names.firstOrNull() ?: return
    val consensus = ForecastConsensus.combine(active, state.forecasts, state.community, now)
    val hours = if (active == Providers.CODEX) listOf(6, 24, 48) else listOf(48, 168)
    val usage = state.provider(active).usage
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxSize()) {
        if (names.size > 1) Box(Modifier.padding(20.dp, 4.dp, 20.dp, 8.dp)) { ProviderTabs(names, active, select) }
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionTitle("Reset-Chance") }
            item {
                SoftCard {
                    if (consensus.isEmpty()) {
                        if (state.forecastLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.forecastLoading) "Schätzungen werden geladen…" else "Keine aktuelle Schätzung", style = MaterialTheme.typography.titleMedium)
                    }
                    (hours + consensus.map { it.hours }).distinct().sorted().forEachIndexed { index, hour ->
                        val chance = consensus.firstOrNull { it.hours == hour }
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            ProbabilityDial(chance?.percent, Modifier.semantics { contentDescription = "${horizon(hour)}: ${chance?.let { "${percent(it.percent)} Prozent Reset-Chance" } ?: "Keine aktuelle Schätzung"}" })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(horizon(hour), style = MaterialTheme.typography.titleMedium)
                                Text(chance?.let { "${it.sources.size} ${if (it.sources.size == 1) "Quelle" else "Quellen"}" } ?: "Nicht aktuell verfügbar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text("Öffentliche Schätzung", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            item {
                MenuCard {
                    MenuRow("Quellen & Berechnung", Icons.Rounded.QueryStats) { open(active, "Quellen") }
                    MenuDivider()
                    MenuRow("Reset-Verlauf", Icons.Rounded.History) { open(active, "Verlauf") }
                }
            }
            usage?.takeIf { it.availableResets != null || it.resetCredits.isNotEmpty() || it.creditsWarning != null }?.let { snapshot ->
                item { SectionTitle("Dein Vorrat") }
                item { BankedCard(snapshot, now, active == Providers.CODEX && state.creditsLoading) }
            }
        }
    }
}

private fun horizon(hours: Int) = if (hours % 24 == 0 && hours > 48) "in ${hours / 24} Tagen" else "in $hours Stunden"

@Composable
private fun ProbabilityDial(value: Double?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val animated by animateFloatAsState(((value ?: 0.0) / 100).toFloat().coerceIn(0f, 1f), label = "probability")
    Box(modifier.size(128.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 10.dp.toPx()
            val bounds = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(colors.primary.copy(alpha = .12f), -90f, 360f, false, Offset(inset, inset), bounds, style = Stroke(10.dp.toPx()))
            if (animated > 0) drawArc(colors.primary, -90f, 360f * animated, false, Offset(inset, inset), bounds, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(value?.let { "${percent(it)}%" } ?: "—", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ResetDetailsScreen(state: UiState, now: Long, model: UsageViewModel, provider: String, destination: String) {
    val uri = LocalUriHandler.current
    val sources = historySources(provider)
    var historySource by rememberSaveable(provider) { mutableStateOf(if (provider == Providers.CODEX) "api:${state.source.name}" else sources.firstOrNull()?.id.orEmpty()) }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (destination == "Quellen") {
            item { SoftCard { Disclosure("So wird gerechnet") {
                Text("Pro Zeitraum zählt jede aktuelle Quelle einmal. Der angezeigte Wert ist ihr einfacher Durchschnitt, keine Addition und keine kalibrierte Wahrscheinlichkeit. Zeiträume und Anbieter bleiben getrennt.", style = MaterialTheme.typography.bodySmall)
                Text("Abrufe dürfen höchstens 2 Stunden alt sein. Quellenstände ebenso; beim Tracker gilt dessen gemeldeter nächster Sync, begrenzt auf 6 Stunden. Ohne Quellenstand bleibt das Modellalter unbekannt. Fehlgeschlagene Abrufe zählen nicht.", style = MaterialTheme.typography.bodySmall)
            } } }
            if (provider == Providers.CODEX) ResetSource.entries.forEach { source -> item(key = source.name) {
                val result = state.forecasts[source]
                val forecast = result?.forecast
                SourceCard(source.label, source.site, forecast?.chances.orEmpty(), forecast?.fetchedAt, forecast?.publishedAt, result?.error, now, state.forecastLoading) {
                    forecast?.confidence?.let { Text("Vertrauen: $it", style = MaterialTheme.typography.bodySmall) }
                    forecast?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    forecast?.accuracy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            } }
            CommunitySource.entries.filter { it.provider == provider }.forEach { source -> item(key = source.name) {
                val result = state.community[source]
                val report = result?.report
                SourceCard(source.label, source.site, report?.let { listOf(it.chance) }.orEmpty(), report?.fetchedAt, report?.publishedAt, result?.error, now, state.forecastLoading,
                    freshOverride = report?.let { result?.error == null && ForecastConsensus.isCommunityFresh(it, now) }) {
                    report?.expectedAt?.let { Text("Geschätzter Termin: ${date(it)}${if (it < now) " · vergangen" else ""}", style = MaterialTheme.typography.bodySmall) }
                }
            } }
        } else {
            item {
                SegmentedTabGroup(sources, sources.firstOrNull { it.id == historySource } ?: sources.first(), { source ->
                    historySource = source.id
                    ResetSource.entries.firstOrNull { "api:${it.name}" == source.id }?.let(model::setSource)
                }, { it.label })
                if (historySource.startsWith("api:")) {
                    state.feedError?.let { Notice(it, true) }
                    if (state.feedLoading && state.feed?.source?.name != historySource.substringAfter(':')) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            val sourceLabel = sources.firstOrNull { it.id == historySource }?.label.orEmpty()
            val events = historyEvents(provider, historySource, state.feed, state.community)
            if (events.isEmpty()) item { SoftCard { Text("Noch keine Meldungen", style = MaterialTheme.typography.titleMedium) } }
            items(events, key = { it.id + ":" + it.at }) { event -> SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(event.kind, style = MaterialTheme.typography.titleSmall)
                        Text(date(event.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    event.url?.let { link -> IconButton(onClick = { uri.openUri(link) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Originalquelle öffnen") } }
                }
                if (event.summary.isNotBlank()) Disclosure("Meldung") { Text(event.summary, style = MaterialTheme.typography.bodySmall) }
                Text(sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }

        }
    }
}

@Composable
private fun SourceCard(label: String, url: String, chances: List<ForecastChance>, fetched: Long?, published: Long?, error: String?, now: Long, loading: Boolean, freshOverride: Boolean? = null,
    details: @Composable ColumnScope.() -> Unit) {
    val uri = LocalUriHandler.current
    val fresh = freshOverride ?: (fetched != null && error == null && ForecastConsensus.isFresh(fetched, published, now))
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { uri.openUri(url) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "${label} öffnen", Modifier.size(20.dp)) }
        }
        Text(when { error != null -> "Abruf fehlgeschlagen · nicht eingerechnet"; fetched == null -> if (loading) "Wird geladen…" else "Noch keine Daten"; !fresh -> "Veraltet · nicht eingerechnet"; published == null -> "Eingerechnet · Modellstand unbekannt"; else -> "Eingerechnet" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        chances.forEach { chance -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(horizon(chance.hours), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${percent(chance.percent)}%", style = MaterialTheme.typography.titleMedium)
        } }
        Disclosure("Details") {
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Quellenstand: ${published?.let { date(it, true) } ?: "unbekannt"}", style = MaterialTheme.typography.labelSmall)
            fetched?.let { Text("Abgerufen: ${date(it, true)}", style = MaterialTheme.typography.labelSmall) }
            chances.forEach { chance -> if (chance.low != null && chance.high != null) Text("${horizon(chance.hours)}: ${percent(chance.low)}–${percent(chance.high)}% Quellenintervall", style = MaterialTheme.typography.labelSmall) }
            details()
        }
    }
}
