@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madebycli.aiusage.*
import com.madebycli.aiusage.R
import com.madebycli.aiusage.data.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal const val ALL_ACCOUNTS = "all"

/** Every date from [first] to [last], both included. (LocalDate.datesUntil needs Android 14.) */
private fun daysOf(first: LocalDate, last: LocalDate): List<LocalDate> =
    generateSequence(first) { day -> day.plusDays(1).takeIf { it <= last } }.toList()

private fun usageText(res: android.content.res.Resources, value: Double, metric: StatsMetric): String =
    if (metric == StatsMetric.FIVE) Fmt.limits(res, value) else "${Fmt.number(res, value, if (value < 10) 1 else 0)} %"

private fun shortUsage(res: android.content.res.Resources, value: Double, metric: StatsMetric): String =
    if (metric == StatsMetric.FIVE) Fmt.number(res, value / 100, 1) else Fmt.number(res, value, 0)

/** The statistics tab: account and window in one row, the period below, then numbers, chart, day, weekdays and hours. */
@Composable
internal fun StatsTab(state: UiState, now: Long, scope: String, select: (String) -> Unit) {
    val res = LocalResources.current
    val accounts = state.accounts
    val active = accounts.firstOrNull { it.id == scope }
    val chosen = if (active != null) listOf(active) else accounts
    var range by rememberSaveable { mutableStateOf(state.nav.statsRange) }
    var metric by rememberSaveable { mutableStateOf(state.nav.statsMetric) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val ledgers = chosen.map { it.ledger }
    val days = remember(ledgers) { LedgerStats.merge(ledgers) }
    val starts = remember(ledgers) { chosen.flatMap { a -> UsageTracker.upgraded(a.ledger).fiveCycles.filter { !it.migrated && it.peak > 0 }.map { it.start(FIVE_HOURS) } } }
    val period = periodOf(range, today, offset)
    val summary = remember(days, period, metric, starts) { LedgerStats.summarize(days, period, metric, starts) }
    val weekdayRange = if (range == StatsRange.WEEK) today.minusDays(55)..today else period.start..minOf(period.endInclusive, today)
    val weekdays = remember(days, weekdayRange, metric) { LedgerStats.summarize(days, weekdayRange, metric, starts) }
    val streak = remember(days, today, metric) { LedgerStats.currentStreak(days, today, metric) }

    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (accounts.size > 1) AccountPicker(accounts, active, select, Modifier.weight(.8f))
                    Box(Modifier.weight(if (accounts.size > 1) 1.4f else 1f)) {
                        ConnectedChoice(StatsMetric.entries, metric, { metric = it }) { stringResource(if (it == StatsMetric.FIVE) R.string.window_five else R.string.metric_week) }
                    }
                }
                ConnectedChoice(StatsRange.entries, range, { range = it; offset = 0; selectedDay = null }) { stringResource(it.label) }
            }
        }
        item(key = "period") { PeriodHeader(range, period, offset) { offset += it; selectedDay = null } }
        if (days.values.none { it.measured }) item(key = "empty") {
            SoftCard { Text(stringResource(R.string.stats_no_data), style = MaterialTheme.typography.titleMedium) }
        } else {
            item(key = "total") { TotalCard(summary, metric, streak) }
            item(key = "chart") {
                val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>(); val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                SoftCard {
                    AnimatedContent(range, transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) }, label = "chart") { shown ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (shown) {
                                StatsRange.WEEK -> WeekChart(days, period, today, metric, selectedDay) { selectedDay = it }
                                StatsRange.MONTH -> MonthGrid(days, period, today, metric, selectedDay) { selectedDay = it }
                                StatsRange.YEAR -> YearView(days, period, today, metric, selectedDay) { selectedDay = it }
                            }
                        }
                    }
                }
            }
            val detail = selectedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it in period }
            if (detail != null) item(key = "day") { DayDetail(detail, days[detail], metric, today, chosen, now) }
            if (active == null && accounts.size > 1) item(key = "accounts") { AccountShareCard(accounts, period, metric) }
            item(key = "weekdays") { WeekdayCard(weekdays, metric, range) }
            if (metric == StatsMetric.FIVE && summary.startHours.sum() > 0) item(key = "hours") { HoursChart(summary.startHours) }
        }
        item(key = "links") {
            val uri = LocalUriHandler.current
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val providers = chosen.map { it.provider }.distinct()
                listOf(Providers.CODEX to "https://chatgpt.com/codex/settings/usage", Providers.CLAUDE to "https://claude.ai/settings/usage").filter { it.first in providers }.forEach { (provider, link) ->
                    TextButton(onClick = { uri.openUri(link) }) {
                        Text(stringResource(R.string.stats_provider_usage, Providers.short(provider))); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPicker(accounts: List<AccountState>, active: AccountState?, select: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(onClick = { open = true }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.panelRaised, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active != null) BrandIcon(active.provider, Modifier.size(20.dp)) else Icon(Icons.Rounded.Groups, null, Modifier.size(20.dp))
                Text(active?.title ?: stringResource(R.string.stats_all_short), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.stats_all)) }, leadingIcon = { Icon(Icons.Rounded.Groups, null) }, onClick = { open = false; select(ALL_ACCOUNTS) })
            accounts.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.title) }, leadingIcon = { BrandIcon(entry.provider, Modifier.size(20.dp)) }, onClick = { open = false; select(entry.id) })
            }
        }
    }
}

private fun periodOf(range: StatsRange, today: LocalDate, offset: Int): ClosedRange<LocalDate> = when (range) {
    StatsRange.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong()).let { it..it.plusDays(6) }
    StatsRange.MONTH -> today.withDayOfMonth(1).plusMonths(offset.toLong()).let { it..it.with(TemporalAdjusters.lastDayOfMonth()) }
    StatsRange.YEAR -> LocalDate.of(today.year + offset, 1, 1).let { it..it.with(TemporalAdjusters.lastDayOfYear()) }
}

@Composable
private fun PeriodHeader(range: StatsRange, period: ClosedRange<LocalDate>, offset: Int, onShift: (Int) -> Unit) {
    val res = LocalResources.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onShift(-1) }, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.stats_previous)) }
        AnimatedContent(period.start, transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.weight(1f), label = "period") { start ->
            Text(when (range) {
                StatsRange.WEEK -> stringResource(R.string.stats_week_label, Fmt.dayMonth(res, start), Fmt.dayMonth(res, start.plusDays(6)))
                StatsRange.MONTH -> "${Fmt.monthLong(res, start.monthValue)} ${start.year}"
                StatsRange.YEAR -> start.year.toString()
            }, style = MaterialTheme.typography.titleMediumEmphasized, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        FilledTonalIconButton(onClick = { onShift(1) }, enabled = offset < 0, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.ChevronRight, stringResource(R.string.stats_next)) }
    }
}

/** The total of the period, large; a lower bound is marked, an estimate stands apart. */
@Composable
private fun TotalCard(summary: RangeSummary, metric: StatsMetric, streak: Int) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    SoftCard {
        if (summary.open > 0) Text(stringResource(R.string.stats_at_least), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedContent(usageText(res, summary.total, metric), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "total") {
                Text(it, style = MaterialTheme.typography.displaySmallEmphasized, color = cs.primary)
            }
            summary.estimate?.takeIf { it >= 10 }?.let {
                Text(stringResource(R.string.stats_unseen, Fmt.limits(res, it)), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        val facts = buildList {
            add(stringResource(R.string.stats_windows_started) to summary.cycles.toString())
            add(stringResource(R.string.stats_resets) to summary.resets.toString())
            add(stringResource(R.string.stats_active_days) to "${summary.activeDays}/${summary.measuredDays}")
            add(stringResource(R.string.stats_best_day) to (summary.bestDay?.let { "${shortUsage(res, it.value(metric), metric)} · ${Fmt.dayMonth(res, it.date)}" } ?: "–"))
            add(stringResource(R.string.stats_avg_day) to (if (summary.activeDays == 0) "–" else shortUsage(res, summary.averagePerActiveDay, metric)))
            add(stringResource(R.string.stats_streak) to pluralStringResource(R.plurals.stats_streak_days, streak, streak))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
            facts.forEach { (label, value) ->
                Column(Modifier.weight(1f).background(cs.panelRaised, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Intensity of a day between "measured, nothing used" and the busiest day of the view. */
private fun intensity(day: DayUsage?, metric: StatsMetric, scale: Double): Float? =
    if (day == null || !day.measured) null else (day.value(metric) / scale).toFloat().coerceIn(0f, 1f)

private fun scaleFor(values: List<Double>, metric: StatsMetric) = (values.maxOrNull() ?: 0.0).coerceAtLeast(if (metric == StatsMetric.FIVE) 100.0 else 10.0)

@Composable
private fun WeekChart(days: Map<LocalDate, DayUsage>, period: ClosedRange<LocalDate>, today: LocalDate, metric: StatsMetric, selected: String?, pick: (String) -> Unit) {
    val res = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val dates = List(7) { period.start.plusDays(it.toLong()) }
    val records = dates.map { days[it]?.takeIf { d -> d.measured } }
    val estimates = records.map { if (metric == StatsMetric.FIVE) it?.fiveEstimate?.takeIf { e -> e >= 10 } ?: 0.0 else 0.0 }
    val top = scaleFor(records.mapIndexed { i, r -> (r?.value(metric) ?: 0.0) + estimates[i] }, metric)
    val reveal = remember(period.start, metric) { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(period.start, metric) { reveal.animateTo(1f, spec) }
    val description = stringResource(R.string.stats_chart)
    Canvas(Modifier.fillMaxWidth().height(190.dp).pointerInput(period.start) {
        detectTapGestures { position -> val index = (position.x / (size.width / 7f)).toInt().coerceIn(0, 6); if (dates[index] <= today) pick(dates[index].toString()) }
    }.semantics { contentDescription = description }) {
        val slot = size.width / 7
        val barWidth = (slot * .5f).coerceAtMost(28.dp.toPx())
        val labelSpace = 22.dp.toPx()
        val valueSpace = 18.dp.toPx()
        val chart = size.height - labelSpace - valueSpace
        val base = size.height - labelSpace
        dates.forEachIndexed { i, day ->
            val x = i * slot + (slot - barWidth) / 2
            val record = records[i]
            val value = record?.value(metric)
            val isSelected = day.toString() == selected
            drawRoundRect(colors.surfaceContainerHighest.copy(alpha = if (day > today) .35f else 1f), Offset(x, base - chart), Size(barWidth, chart), CornerRadius(barWidth / 2))
            val total = (value ?: 0.0) + estimates[i]
            if (total > 0) {
                val h = (total / top * chart * reveal.value).toFloat().coerceAtLeast(barWidth)
                // The estimate sits on top, lighter; the measured part below; an open part as an outline.
                drawRoundRect(colors.primary.copy(alpha = .25f), Offset(x, base - h), Size(barWidth, h), CornerRadius(barWidth / 2))
                if ((value ?: 0.0) > 0) {
                    val mh = ((value!! / top) * chart * reveal.value).toFloat().coerceAtLeast(barWidth)
                    drawRoundRect(if (isSelected) colors.primary else colors.primary.copy(alpha = .78f), Offset(x, base - mh), Size(barWidth, mh), CornerRadius(barWidth / 2))
                    val open = if (metric == StatsMetric.FIVE) record.fiveOpen else 0.0
                    if (open > 0) drawRoundRect(colors.onPrimary.copy(alpha = .7f), Offset(x + 2, base - mh + 2), Size(barWidth - 4, mh - 4), CornerRadius(barWidth / 2), style = Stroke(1.5.dp.toPx()))
                }
            } else if (value != null) drawCircle(colors.onSurfaceVariant.copy(alpha = .5f), barWidth * .16f, Offset(x + barWidth / 2, base - barWidth / 2))
        }
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; textSize = 11.sp.toPx() }
            dates.forEachIndexed { i, day ->
                paint.color = (if (day.toString() == selected) colors.primary else colors.onSurfaceVariant).toArgb()
                paint.isFakeBoldText = day.toString() == selected
                canvas.nativeCanvas.drawText(Fmt.weekdayShort(res, day.dayOfWeek.value), i * slot + slot / 2, size.height - 4.dp.toPx(), paint)
                val value = records[i]?.value(metric)
                if (value != null && value > 0 && reveal.value > .9f) {
                    paint.color = colors.onSurface.toArgb(); paint.textSize = 10.sp.toPx(); paint.isFakeBoldText = false
                    val h = ((value + estimates[i]) / top * chart).toFloat().coerceAtLeast(barWidth)
                    canvas.nativeCanvas.drawText(shortUsage(res, value, metric), i * slot + slot / 2, base - h - 5.dp.toPx(), paint)
                    paint.textSize = 11.sp.toPx()
                }
            }
        }
    }
    ChartLegend(metric, records.any { (it?.fiveOpen ?: 0.0) > 0 }, estimates.any { it > 0 })
}

@Composable
private fun ChartLegend(metric: StatsMetric, open: Boolean, estimate: Boolean) {
    if (metric != StatsMetric.FIVE || (!open && !estimate)) return
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        @Composable fun Item(fill: Color, label: String, outline: Boolean = false) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(12.dp).background(fill, RoundedCornerShape(4.dp)).then(if (outline) Modifier.border(1.5.dp, cs.onPrimary.copy(.7f), RoundedCornerShape(4.dp)) else Modifier))
            Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Item(cs.primary.copy(.78f), stringResource(R.string.stats_legend_measured))
        if (open) Item(cs.primary.copy(.78f), stringResource(R.string.stats_legend_open), outline = true)
        if (estimate) Item(cs.primary.copy(.25f), stringResource(R.string.stats_legend_estimate))
    }
}

@Composable
private fun MonthGrid(days: Map<LocalDate, DayUsage>, period: ClosedRange<LocalDate>, today: LocalDate, metric: StatsMetric, selected: String?, pick: (String) -> Unit) {
    val res = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val first = period.start
    val lead = first.dayOfWeek.value - 1
    val rows = (lead + first.lengthOfMonth() + 6) / 7
    val scale = scaleFor(daysOf(first, period.endInclusive).mapNotNull { days[it]?.takeIf { d -> d.measured }?.value(metric) }, metric)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (1..7).forEach { Text(Fmt.weekdayShort(res, it), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        }
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(7) { column ->
                    val dayNumber = row * 7 + column - lead + 1
                    if (dayNumber < 1 || dayNumber > first.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f))
                    else {
                        val date = first.withDayOfMonth(dayNumber)
                        val record = days[date]
                        val level = intensity(record, metric, scale)
                        val future = date > today
                        val background = when {
                            future -> Color.Transparent
                            level == null -> colors.surfaceContainerHigh.copy(alpha = .6f)
                            level == 0f -> colors.primary.copy(alpha = .07f)
                            else -> colors.primary.copy(alpha = .2f + .8f * level)
                        }
                        val shape = RoundedCornerShape(10.dp)
                        val label = "${Fmt.dayMonth(res, date)}: ${record?.takeIf { it.measured }?.let { usageText(res, it.value(metric), metric) } ?: stringResource(R.string.stats_not_read)}"
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(shape).background(background)
                            .then(if (date.toString() == selected) Modifier.border(2.dp, colors.primary, shape) else if (date == today) Modifier.border(1.dp, colors.outline, shape) else Modifier)
                            .semantics { contentDescription = label }
                            .clickable(enabled = !future) { pick(date.toString()) }, contentAlignment = Alignment.Center) {
                            Text(dayNumber.toString(), style = MaterialTheme.typography.labelSmall,
                                color = if (level != null && level > .55f) colors.onPrimary else if (future) colors.onSurfaceVariant.copy(alpha = .4f) else colors.onSurface)
                        }
                    }
                }
            }
        }
        HeatLegend()
    }
}

@Composable
private fun HeatLegend() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).background(colors.surfaceContainerHigh.copy(alpha = .6f), RoundedCornerShape(3.dp)))
        Text(stringResource(R.string.stats_not_read), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.stats_less), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        listOf(.12f, .3f, .5f, .75f, 1f).forEach { alpha -> Box(Modifier.size(12.dp).background(colors.primary.copy(alpha = alpha), RoundedCornerShape(3.dp))) }
        Text(stringResource(R.string.stats_more), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun YearView(days: Map<LocalDate, DayUsage>, period: ClosedRange<LocalDate>, today: LocalDate, metric: StatsMetric, selected: String?, pick: (String) -> Unit) {
    val res = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val year = period.start.year
    val first = period.start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = ((period.endInclusive.toEpochDay() - first.toEpochDay()) / 7 + 1).toInt()
    val scale = scaleFor(daysOf(period.start, period.endInclusive).mapNotNull { days[it]?.takeIf { d -> d.measured }?.value(metric) }, metric)
    val reveal = remember(year, metric) { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(year, metric) { reveal.animateTo(1f, spec) }
    val scroll = rememberScrollState()
    val cellDp = 15.dp; val gapDp = 3.dp; val monthRowDp = 16.dp
    LaunchedEffect(year, scroll.maxValue) { scroll.scrollTo(if (period.endInclusive >= today) scroll.maxValue else 0) }
    val description = stringResource(R.string.stats_year, year)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(scroll)) {
            Canvas(Modifier.width((cellDp + gapDp) * weeks).height(monthRowDp + (cellDp + gapDp) * 7).pointerInput(year, weeks) {
                detectTapGestures { position ->
                    val cell = (cellDp + gapDp).toPx()
                    val col = (position.x / cell).toInt().coerceIn(0, weeks - 1)
                    val row = ((position.y - monthRowDp.toPx()) / cell).toInt().coerceIn(0, 6)
                    val date = first.plusDays(col * 7L + row)
                    if (date in period && date <= today) pick(date.toString())
                }
            }.semantics { contentDescription = description }) {
                val cell = cellDp.toPx(); val g = gapDp.toPx(); val monthRow = monthRowDp.toPx()
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.sp.toPx(); color = colors.onSurfaceVariant.toArgb() }
                    for (week in 0 until weeks) {
                        val monday = first.plusWeeks(week.toLong())
                        val label = (0..6).map { monday.plusDays(it.toLong()) }.firstOrNull { it.dayOfMonth == 1 && it.year == year }
                        if (label != null) canvas.nativeCanvas.drawText(Fmt.monthShort(res, label.monthValue), week * (cell + g), 11.dp.toPx(), paint)
                    }
                }
                for (week in 0 until weeks) for (row in 0..6) {
                    val date = first.plusDays(week * 7L + row)
                    if (date !in period) continue
                    val level = intensity(days[date], metric, scale)
                    val color = when {
                        date > today -> colors.surfaceContainerHigh.copy(alpha = .25f)
                        level == null -> colors.surfaceContainerHigh.copy(alpha = .6f)
                        level == 0f -> colors.primary.copy(alpha = .08f)
                        else -> colors.primary.copy(alpha = (.22f + .78f * level) * reveal.value.coerceAtLeast(.15f))
                    }
                    val x = week * (cell + g); val y = monthRow + row * (cell + g)
                    drawRoundRect(color, Offset(x, y), Size(cell, cell), CornerRadius(cell * .3f))
                    if (date.toString() == selected) drawRoundRect(colors.primary, Offset(x, y), Size(cell, cell), CornerRadius(cell * .3f), style = Stroke(2.dp.toPx()))
                }
            }
        }
        HeatLegend()
        Text(stringResource(R.string.stats_months), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        val monthly = (1..12).map { month ->
            val start = LocalDate.of(year, month, 1)
            val parts = daysOf(start, start.plusMonths(1).minusDays(1)).mapNotNull { days[it]?.takeIf { d -> d.measured } }
            if (parts.isEmpty()) null else parts.sumOf { it.value(metric) }
        }
        MiniBars(monthly, (1..12).map { Fmt.monthShort(res, it).take(3) }, height = 120)
    }
}

/** One day: total, facts and the windows that started on it. */
@Composable
private fun DayDetail(date: LocalDate, record: DayUsage?, metric: StatsMetric, today: LocalDate, accounts: List<AccountState>, now: Long) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    SoftCard {
        Text(Fmt.longDate(res, date), style = MaterialTheme.typography.titleMedium)
        if (record == null || !record.measured) {
            Text(stringResource(if (date > today) R.string.stats_future else R.string.stats_not_read), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            return@SoftCard
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(usageText(res, record.value(metric), metric), style = MaterialTheme.typography.headlineMediumEmphasized, color = cs.primary)
            if (metric == StatsMetric.FIVE && record.fiveOpen > 0) Text(stringResource(R.string.stats_at_least), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (record.legacy) Text(stringResource(R.string.stats_legacy), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            DetailFact(stringResource(R.string.stats_windows_started), record.cycles(metric).toString())
            DetailFact(stringResource(R.string.stats_resets), record.resets(metric).toString())
            if (metric == StatsMetric.FIVE) DetailFact(stringResource(R.string.stats_peak), "${Fmt.percent(record.peak)} %")
            DetailFact(stringResource(R.string.stats_reads), record.reads.toString())
        }
        val cycles = remember(accounts.map { it.ledger }, date, metric) { LedgerStats.cyclesOn(accounts.associate { it.id to it.ledger }, date, metric) }
        val names = accounts.associate { it.id to it }
        cycles.forEach { view ->
            val entry = names[view.accountId]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                entry?.let { BrandIcon(it.provider, Modifier.size(18.dp)) }
                Text("${Fmt.clockOnly(res, view.start)} – ${Fmt.clock(res, view.record.end, view.start)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(132.dp), maxLines = 1)
                UsageTrack((view.record.peak / 100).toFloat(), cs.primary, Modifier.weight(1f).height(6.dp))
                Text("${Fmt.percent(view.record.peak)}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (!view.record.complete && view.record.closedAt != null) Text("≥", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DetailFact(title: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Who used how much in the period, when several accounts are shown together. */
@Composable
private fun AccountShareCard(accounts: List<AccountState>, period: ClosedRange<LocalDate>, metric: StatsMetric) {
    val res = LocalResources.current
    val rows = accounts.map { it to LedgerStats.summarize(LedgerStats.days(it.ledger), period, metric) }
    val top = rows.maxOf { it.second.total }.coerceAtLeast(1.0)
    val sum = rows.sumOf { it.second.total }
    SoftCard {
        Text(stringResource(R.string.stats_per_account), style = MaterialTheme.typography.titleMedium)
        rows.sortedByDescending { it.second.total }.forEach { (entry, summary) ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandIcon(entry.provider, Modifier.size(20.dp))
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(usageText(res, summary.total, metric), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                UsageTrack((summary.total / top).toFloat(), providerAccent(entry.provider), Modifier.fillMaxWidth().height(8.dp))
                Text(stringResource(R.string.stats_share, if (sum > 0) Fmt.percent(summary.total / sum * 100) else "0"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WeekdayCard(summary: RangeSummary, metric: StatsMetric, range: StatsRange) {
    val res = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val averages = summary.weekdayAverage
    val top = (averages.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    SoftCard {
        Text(stringResource(R.string.stats_weekdays), style = MaterialTheme.typography.titleMedium)
        val busiest = summary.busiestWeekday
        Text(if (busiest == null) stringResource(R.string.stats_too_little) else stringResource(R.string.stats_busiest, Fmt.weekdayLong(res, busiest + 1)),
            style = MaterialTheme.typography.titleLargeEmphasized, color = if (busiest == null) colors.onSurfaceVariant else colors.primary)
        Text(stringResource(if (range == StatsRange.WEEK) R.string.stats_basis_week else R.string.stats_basis_period), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        val reveal = remember(summary) { Animatable(0f) }
        val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
        LaunchedEffect(summary) { reveal.animateTo(1f, spec) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            repeat(7) { index ->
                val value = averages[index]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(Fmt.weekdayShort(res, index + 1), Modifier.width(32.dp), style = MaterialTheme.typography.labelMedium, fontWeight = if (index == busiest) FontWeight.Bold else FontWeight.Normal)
                    UsageTrack(((value ?: 0.0) / top).toFloat() * reveal.value, if (index == busiest) colors.primary else colors.primary.copy(alpha = .45f), Modifier.weight(1f).height(10.dp))
                    Text(value?.let { usageText(res, it, metric) } ?: "–", Modifier.width(78.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** When the 5-hour windows of the period started, per hour of the day. */
@Composable
private fun HoursChart(hours: List<Int>) {
    val cs = MaterialTheme.colorScheme
    val top = (hours.maxOrNull() ?: 0).coerceAtLeast(1)
    SoftCard {
        Text(stringResource(R.string.stats_hours), style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / 24
            val label = 14.dp.toPx()
            val chart = size.height - label
            hours.forEachIndexed { h, count ->
                val bh = if (count == 0) 3.dp.toPx() else chart * count / top
                drawRoundRect(if (count == top) cs.primary else cs.primary.copy(alpha = if (count == 0) .12f else .55f), Offset(h * slot + slot * .15f, chart - bh), Size(slot * .7f, bh), CornerRadius(slot * .35f))
            }
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; textSize = 10.sp.toPx(); color = cs.onSurfaceVariant.toArgb() }
                listOf(0, 6, 12, 18).forEach { canvas.nativeCanvas.drawText("%02d".format(it), it * slot + slot / 2, size.height - 1, paint) }
            }
        }
    }
}

/** Small column chart with a label under every column. Missing values stay empty; a bar is never wider than it is tall. */
@Composable
internal fun MiniBars(values: List<Double?>, labels: List<String>, height: Int = 96) {
    val colors = MaterialTheme.colorScheme
    val top = (values.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val reveal = remember(values) { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(values) { reveal.animateTo(1f, spec) }
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val slot = size.width / values.size
        val barWidth = (slot * .5f).coerceAtMost(20.dp.toPx())
        val labelSpace = 18.dp.toPx()
        val chart = size.height - labelSpace
        values.forEachIndexed { i, value ->
            val x = i * slot + (slot - barWidth) / 2
            drawRoundRect(colors.surfaceContainerHighest, Offset(x, size.height - labelSpace - chart), Size(barWidth, chart), CornerRadius(barWidth / 2))
            if (value != null) {
                if (value <= 0) drawCircle(colors.onSurfaceVariant.copy(alpha = .5f), barWidth * .16f, Offset(x + barWidth / 2, size.height - labelSpace - barWidth / 2))
                else {
                    val h = (value / top * chart * reveal.value).toFloat().coerceAtLeast(barWidth)
                    drawRoundRect(colors.primary.copy(alpha = .85f), Offset(x, size.height - labelSpace - h), Size(barWidth, h), CornerRadius(barWidth / 2))
                }
            }
        }
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; textSize = 10.sp.toPx(); color = colors.onSurfaceVariant.toArgb() }
            labels.forEachIndexed { i, label -> canvas.nativeCanvas.drawText(label, i * slot + slot / 2, size.height - 3.dp.toPx(), paint) }
        }
    }
}
