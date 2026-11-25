@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.*
import com.madebycli.aiusage.R
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Resets: the personal timeline of every account first, then the global resets of the chosen provider. */
@Composable
internal fun ResetScreen(state: UiState, now: Long, provider: String, select: (String) -> Unit, open: (String, String) -> Unit) {
    val names = state.connectedProviders.filter { it != Providers.GEMINI }
    val active = provider.takeIf { it in names } ?: names.firstOrNull()
    val outlook = remember(state.intel, active, now / 30) { active?.let { ResetOutlookBuilder.build(it, state.intel, now) } }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "yours") { PersonalTimeline(state, now) }
        if (active != null && outlook != null) {
            if (names.size > 1) item(key = "tabs") { Box(Modifier.padding(top = 8.dp)) { ProviderTabs(names, active, select) } }
            item(key = "global:$active") {
                val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>(); val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                AnimatedContent(active, transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) }, label = "provider") { shown ->
                    val shownOutlook = remember(state.intel, shown, now / 30) { ResetOutlookBuilder.build(shown, state.intel, now) }
                    GlobalSection(state, now, shown, shownOutlook, open)
                }
            }
        }
    }
}

/** One row per account with its running windows side by side; accounts whose next reset is soonest come first. */
@Composable
private fun PersonalTimeline(state: UiState, now: Long) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    val rows = state.accounts.mapNotNull { entry ->
        val running = entry.windows().filter { it.usedPercent > 0 && (it.resetAt ?: 0) > now }.sortedBy { it.seconds }
        if (running.isEmpty()) null else entry to running
    }.sortedBy { (_, windows) -> windows.minOf { it.resetAt!! } }
    Column {
        SettingsSectionLabel(stringResource(R.string.resets_yours))
        SettingsGroup {
            if (rows.isEmpty()) SettingsBlock { Text(stringResource(R.string.resets_none_running), color = cs.onSurfaceVariant) }
            rows.forEach { (entry, windows) ->
                Surface(color = cs.panel, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BrandIcon(entry.provider, Modifier.size(22.dp))
                            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            windows.take(2).forEach { window ->
                                val at = window.resetAt!!
                                Column(Modifier.weight(1f).background(cs.panelRaised, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text("${windowTitle(window.seconds)} · ${Fmt.percent(window.usedPercent)} %", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                                    Text(Fmt.clock(res, at, now), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(Fmt.remaining(res, at - now), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                                }
                            }
                            if (windows.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalSection(state: UiState, now: Long, active: String, outlook: ResetOutlook, open: (String, String) -> Unit) {
    val problems = outlook.sources.filter { it.state == SourceState.ERROR }
    val loaded = outlook.sources.any { it.state != SourceState.EMPTY }
    val banked = state.accountsOf(active).filter { entry -> entry.usage?.let { it.availableResets != null || it.resetCredits.isNotEmpty() || it.creditsWarning != null } == true }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.intelLoading && !loaded) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        if (problems.isNotEmpty()) Notice(problems.joinToString("\n") { "${it.source.label}: ${it.error}" }, error = outlook.horizons.isEmpty() && outlook.timeline.isEmpty())
        LastResetCard(outlook, now, loaded || state.intelLoading)
        if (outlook.horizons.isNotEmpty()) {
            SettingsSectionLabel(stringResource(R.string.resets_chance))
            OutlookCard(outlook, now)
        }
        if (outlook.hints.isNotEmpty() || outlook.expected.isNotEmpty()) HintsCard(outlook, now)
        if (outlook.resets.isNotEmpty()) {
            SettingsSectionLabel(stringResource(R.string.resets_history))
            SoftCard {
                val shown = outlook.resets.take(5)
                val gaps = ResetOutlookBuilder.gapsDays(outlook.resets)
                shown.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
                    ResetRow(entry, gaps[index])
                }
            }
        }
        SettingsGroup {
            NavigationSettingsItem(stringResource(R.string.resets_history_all), null, Icons.Rounded.History) { open(active, PAGE_HISTORY) }
            NavigationSettingsItem(stringResource(R.string.resets_sources), stringResource(R.string.resets_sources_fresh, outlook.sources.count { it.state == SourceState.FRESH }, outlook.sources.size),
                Icons.Rounded.QueryStats) { open(active, PAGE_SOURCES) }
        }
        if (banked.isNotEmpty()) {
            SettingsSectionLabel(stringResource(R.string.resets_bank))
            banked.forEach { entry -> BankedCard(entry.usage!!, now, entry.creditsLoading, title = if (state.accountsOf(active).size > 1) entry.title else null) }
        }
    }
}

internal const val PAGE_HISTORY = "history"
internal const val PAGE_SOURCES = "sources"

@Composable
private fun daysText(days: Double): String {
    val res = LocalResources.current
    val rounded = if (days >= 10) Math.round(days).toDouble() else Math.round(days * 10) / 10.0
    return pluralStringResource(R.plurals.days_short, if (rounded == 1.0) 1 else 2, Fmt.number(res, rounded, 1))
}

@Composable
private fun LastResetCard(outlook: ResetOutlook, now: Long, loading: Boolean) {
    val res = LocalResources.current
    val colors = MaterialTheme.colorScheme
    SoftCard {
        Text(stringResource(R.string.resets_last), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        val last = outlook.lastResetAt
        if (last == null) {
            Text(stringResource(if (loading) R.string.resets_loading else R.string.resets_no_data), style = MaterialTheme.typography.headlineSmall)
            return@SoftCard
        }
        val dayCount = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochSecond(last).atZone(ZoneOffset.UTC).toLocalDate(), Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate())
        Text(if (outlook.lastResetTimeKnown) Fmt.ago(res, now - last) else when (dayCount) {
            0L -> stringResource(R.string.resets_today); 1L -> stringResource(R.string.resets_yesterday)
            else -> pluralStringResource(R.plurals.time_days_ago, dayCount.toInt(), dayCount.toInt())
        }, style = MaterialTheme.typography.displaySmallEmphasized)
        val date = Instant.ofEpochSecond(last).atZone(if (outlook.lastResetTimeKnown) ZoneId.systemDefault() else ZoneOffset.UTC).toLocalDate()
        Text(if (outlook.lastResetTimeKnown) "${Fmt.mediumDate(res, date)} · ${Fmt.clockOnly(res, last)}" else "${Fmt.mediumDate(res, date)} · ${stringResource(R.string.resets_time_unknown)}",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        outlook.rhythm?.let { rhythm ->
            val since = (now - last) / 86_400.0
            RhythmMeter(since, rhythm)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(colors.primary, stringResource(R.string.resets_median, daysText(rhythm.medianDays)))
                LegendDot(colors.onSurfaceVariant.copy(.6f), stringResource(R.string.resets_longest, daysText(rhythm.longestDays)))
                LegendDot(if (since > rhythm.longestDays) colors.error else colors.primary, stringResource(R.string.resets_now_marker, daysText(since)))
            }
        }
        outlook.window?.let { window ->
            val (from, to) = ResetOutlookBuilder.localHours(window, now)
            Text(stringResource(R.string.resets_usual_hours, from, to), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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

@Composable
private fun horizonTitle(hours: Int): String =
    if (hours >= 96 && hours % 24 == 0) stringResource(R.string.resets_within_days, hours / 24) else stringResource(R.string.resets_within_hours, hours)

@Composable
private fun OutlookCard(outlook: ResetOutlook, now: Long) {
    val res = LocalResources.current
    val colors = MaterialTheme.colorScheme
    SoftCard {
        outlook.horizons.forEachIndexed { index, horizon ->
            if (index > 0) HorizontalDivider(color = colors.outlineVariant.copy(alpha = .35f))
            Text(horizonTitle(horizon.hours), style = MaterialTheme.typography.titleSmallEmphasized, color = colors.primary)
            horizon.rows.forEach { row ->
                val tint = if (row.fresh) colors.primary else colors.onSurfaceVariant.copy(alpha = .6f)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(row.source.label, style = MaterialTheme.typography.bodyMedium)
                            if (!row.fresh) Text("${stringResource(R.string.resets_stale)} · ${Fmt.ago(res, now - row.fetchedAt)}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${Fmt.percent(row.chance.percent)} %", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (row.fresh) colors.onSurface else colors.onSurfaceVariant)
                            if (row.chance.low != null && row.chance.high != null) Text("${Fmt.percent(row.chance.low)}–${Fmt.percent(row.chance.high)} %", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                    RangeBar(row.chance.percent, row.chance.low, row.chance.high, tint)
                }
            }
            if (horizon.disagree) Text(stringResource(R.string.resets_sources_disagree, Fmt.percent(horizon.spread!!)), style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
        }
    }
}

@Composable
private fun HintsCard(outlook: ResetOutlook, now: Long) {
    val res = LocalResources.current
    SoftCard {
        Text(stringResource(R.string.resets_hints), style = MaterialTheme.typography.titleSmall)
        outlook.hints.distinct().forEachIndexed { index, (source, text) ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Text(source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        outlook.expected.forEach { (source, at) ->
            Text(stringResource(R.string.resets_expected, source.label, Fmt.clock(res, at, now)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One reset: date, kind, time, what was said, how long after the previous one, and a link to the original. */
@Composable
internal fun ResetRow(entry: ResetEntry, gapDays: Double?) {
    val res = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val moment = Instant.ofEpochSecond(entry.at).atZone(ZoneId.systemDefault())
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(moment.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(Fmt.monthShort(res, moment.monthValue), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                KindChip(entry.kind)
                if (!entry.verified) Chip(stringResource(R.string.resets_unverified), colors.errorContainer, colors.onErrorContainer)
                Text(if (entry.timeKnown) "${Fmt.weekdayShort(res, moment.dayOfWeek.value)} ${Fmt.clockOnly(res, entry.at)}" else Fmt.weekdayShort(res, moment.dayOfWeek.value),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Text(entry.title.ifBlank { stringResource(R.string.reset_event_generic) }, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            entry.detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
            gapDays?.takeIf { entry.kind != ResetKind.BANKED }?.let { Text(stringResource(R.string.resets_after_previous, daysText(it)), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        }
        entry.url?.let { link -> IconButton(onClick = { uri.openUri(link) }, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.resets_open_source), Modifier.size(18.dp)) } }
    }
}

@Composable
private fun KindChip(kind: ResetKind) {
    val colors = MaterialTheme.colorScheme
    when (kind) {
        ResetKind.GLOBAL -> Chip(stringResource(R.string.kind_global), colors.primaryContainer, colors.onPrimaryContainer)
        ResetKind.PARTIAL -> Chip(stringResource(R.string.kind_partial), colors.secondaryContainer, colors.onSecondaryContainer)
        ResetKind.BANKED -> Chip(stringResource(R.string.kind_banked), colors.tertiaryContainer, colors.onTertiaryContainer)
        ResetKind.EVENT -> Chip(stringResource(R.string.kind_event), colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
}

@Composable
private fun Chip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1)
    }
}

/** Full history or source status of one provider, as a page with a tinted hero. */
@Composable
internal fun ResetDetailsPage(state: UiState, now: Long, model: UsageViewModel, provider: String, page: String, back: () -> Unit) {
    val outlook = remember(state.intel, provider, now / 30) { ResetOutlookBuilder.build(provider, state.intel, now) }
    val sources = page == PAGE_SOURCES
    SettingsPageScaffold(if (sources) Icons.Rounded.QueryStats else Icons.Rounded.History,
        stringResource(if (sources) R.string.resets_sources else R.string.resets_history), Providers.label(provider), providerAccent(provider),
        if (sources) MaterialShapes.Pentagon else MaterialShapes.Gem, back, actions = { RefreshButton(state.intelLoading) { model.loadResetData() } }) {
        if (sources) sourcesList(outlook, now) else historyList(outlook)
    }
}

private enum class HistoryFilter(val label: Int) { ALL(R.string.filter_all), RESETS(R.string.filter_resets), BANKED(R.string.filter_banked) }

private fun androidx.compose.foundation.lazy.LazyListScope.historyList(outlook: ResetOutlook) {
    settingsItem("history") {
        val res = LocalResources.current
        var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
        val entries = outlook.timeline.filter { when (filter) { HistoryFilter.ALL -> true; HistoryFilter.RESETS -> it.kind != ResetKind.BANKED; HistoryFilter.BANKED -> it.kind == ResetKind.BANKED } }
        val resets = outlook.timeline.filter { it.kind != ResetKind.BANKED }
        val gaps = resets.zip(ResetOutlookBuilder.gapsDays(resets)).toMap()
        ConnectedChoice(HistoryFilter.entries, filter, { filter = it }) { stringResource(it.label) }
        if (entries.isEmpty()) SoftCard { Text(stringResource(R.string.resets_empty), style = MaterialTheme.typography.titleMedium) }
        entries.groupBy { Instant.ofEpochSecond(it.at).atZone(ZoneId.systemDefault()).let { d -> d.year to d.monthValue } }.forEach { (month, rows) ->
            SettingsSectionLabel("${Fmt.monthLong(res, month.second)} ${month.first}")
            SoftCard {
                rows.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
                    ResetRow(entry, gaps[entry])
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sourcesList(outlook: ResetOutlook, now: Long) {
    settingsItem("sources") {
        val res = LocalResources.current
        val uri = LocalUriHandler.current
        val colors = MaterialTheme.colorScheme
        outlook.sources.forEach { status ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status.source.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    when (status.state) {
                        SourceState.FRESH -> Chip(stringResource(R.string.source_fresh), colors.primaryContainer, colors.onPrimaryContainer)
                        SourceState.STALE -> Chip(stringResource(R.string.source_stale), colors.tertiaryContainer, colors.onTertiaryContainer)
                        SourceState.ERROR -> Chip(stringResource(R.string.source_error), colors.errorContainer, colors.onErrorContainer)
                        SourceState.EMPTY -> Chip(stringResource(R.string.source_empty), colors.surfaceContainerHighest, colors.onSurfaceVariant)
                    }
                    IconButton(onClick = { uri.openUri(status.source.site) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.resets_open_source), Modifier.size(20.dp)) }
                }
                status.fetchedAt?.let {
                    Text(listOfNotNull(stringResource(R.string.source_fetched, Fmt.ago(res, now - it)), status.publishedAt?.let { p -> stringResource(R.string.source_published, Fmt.ago(res, now - p)) })
                        .joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                status.partial?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                outlook.horizons.flatMap { it.rows }.filter { it.source == status.source }.forEach { row ->
                    Text("${horizonTitle(row.chance.hours)}: ${Fmt.percent(row.chance.percent)} %" + (row.chance.low?.let { " (${Fmt.percent(it)}–${Fmt.percent(row.chance.high ?: it)} %)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
