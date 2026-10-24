@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madebycli.aiusage.*
import com.madebycli.aiusage.R
import com.madebycli.aiusage.data.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal const val ALL_ACCOUNTS = "all"

/** "2,4 Limits": 100 points of the 5-hour window are one full limit. */
internal fun formatLimits(points: Double): String {
    val limits = points / 100
    val text = number(limits, if (limits < 10) 1 else 0)
    return if (kotlin.math.abs(limits - 1.0) < .05) "1 Limit" else "$text Limits"
}

internal fun formatUsage(value: Double, metric: StatsMetric): String =
    if (metric == StatsMetric.FIVE) formatLimits(value) else "${number(value, if (value < 10) 1 else 0)} %"

private fun shortUsage(value: Double, metric: StatsMetric): String =
    if (metric == StatsMetric.FIVE) number(value / 100, 1) else number(value, 0)

/** Every date from [first] to [last], both included. (LocalDate.datesUntil needs Android 14.) */
private fun daysOf(first: LocalDate, last: LocalDate): List<LocalDate> =
    generateSequence(first) { day -> day.plusDays(1).takeIf { it <= last } }.toList()

private val dayLabel = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
private val monthLabel = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)
private val dayMonth = DateTimeFormatter.ofPattern("d.M.", Locale.GERMAN)

/** The statistics tab: usage per day, week, month and year, and which weekdays are busiest. */
@Composable
internal fun StatsTab(state: UiState, now: Long, scope: String, select: (String) -> Unit) {
    val accounts = state.accounts
    val active = accounts.firstOrNull { it.id == scope }
    val chosen = if (active != null) listOf(active) else accounts
    var range by rememberSaveable { mutableStateOf(state.nav.statsRange) }
    var metric by rememberSaveable { mutableStateOf(state.nav.statsMetric) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val days = remember(chosen.map { it.ledger }) { LedgerStats.merge(chosen.map { it.ledger }) }
    val period = periodOf(range, today, offset)
    val summary = remember(days, period, metric) { LedgerStats.summarize(days, period, metric) }
    val weekdayRange = when (range) {
        StatsRange.WEEK -> today.minusDays(55)..today
        else -> period.start..minOf(period.endInclusive, today)
    }
    val weekdays = remember(days, weekdayRange, metric) { LedgerStats.summarize(days, weekdayRange, metric) }
    val streak = remember(days, today, metric) { LedgerStats.currentStreak(days, today, metric) }
    val byDate = remember(days) { days.associateBy { it.date } }

    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (accounts.size > 1) item(key = "scope") {
            val ids = listOf(ALL_ACCOUNTS) + accounts.map { it.id }
            SegmentedTabGroup(ids, if (active != null) active.id else ALL_ACCOUNTS, select, { id -> if (id == ALL_ACCOUNTS) "Alle" else accounts.first { it.id == id }.title },
                icon = { id -> accounts.firstOrNull { it.id == id }?.let { ImageVector.vectorResource(if (it.provider == Providers.CODEX) R.drawable.ic_brand_codex else R.drawable.ic_brand_claude) } })
        }
        item(key = "controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SegmentedTabGroup(StatsMetric.entries, metric, { metric = it }, { it.title }, fillEqually = true)
                SegmentedTabGroup(StatsRange.entries, range, { range = it; offset = 0; selectedDay = null }, { it.title }, fillEqually = true)
            }
        }
        item(key = "period") { PeriodHeader(range, period, offset, today, onShift = { offset += it; selectedDay = null }) }
        if (days.none { it.measured }) item(key = "empty") {
            SoftCard {
                Text("Noch keine Messung", style = MaterialTheme.typography.headlineSmall)
                Text("Jedes Mal, wenn du die App öffnest, und im Hintergrund hält sie fest, wie viel von deinem Limit verbraucht ist. Ein Reset-Zyklus zählt dabei nur einmal.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            item(key = "tiles") { SummaryTiles(summary, metric, streak, range) }
            item(key = "chart") {
                SoftCard {
                    AnimatedContent(range, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "chart") { shown ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (shown) {
                                StatsRange.WEEK -> WeekChart(byDate, period, today, metric, selectedDay) { selectedDay = it }
                                StatsRange.MONTH -> MonthGrid(byDate, period, today, metric, selectedDay) { selectedDay = it }
                                StatsRange.YEAR -> YearView(byDate, period, today, metric, selectedDay) { selectedDay = it }
                            }
                        }
                    }
                }
            }
            val detail = selectedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it in period }
            if (detail != null) item(key = "day") { DayDetail(detail, byDate[detail.toString()], metric, today) }
            if (active == null && accounts.size > 1) item(key = "accounts") { AccountShareCard(accounts, period, metric) }
            item(key = "weekdays") { WeekdayCard(weekdays, metric, range, period) }
        }
        item(key = "note") { Notice("Ein Reset-Zyklus zählt einmal, mit seinem höchsten Wert. Öffnest du die App mehrmals im selben Zyklus, wird nichts doppelt gezählt. Nur Tage, an denen die App den Verbrauch lesen konnte, zählen als gemessen.") }
        item(key = "links") {
            val uri = LocalUriHandler.current
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val providers = chosen.map { it.provider }.distinct()
                if (Providers.CODEX in providers) TextButton(onClick = { uri.openUri("https://chatgpt.com/codex/settings/usage") }) {
                    Text("Codex-Nutzung"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                }
                if (Providers.CLAUDE in providers) TextButton(onClick = { uri.openUri("https://claude.ai/settings/usage") }) {
                    Text("Claude-Nutzung"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                }
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
private fun PeriodHeader(range: StatsRange, period: ClosedRange<LocalDate>, offset: Int, today: LocalDate, onShift: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) { Icon(Icons.Rounded.ChevronLeft, "Zurück") }
        AnimatedContent(period.start, transitionSpec = { fadeIn() togetherWith fadeOut() }, modifier = Modifier.weight(1f), label = "period") { start ->
            Text(when (range) {
                StatsRange.WEEK -> "${start.format(dayMonth)} – ${start.plusDays(6).format(DateTimeFormatter.ofPattern("d.M.yyyy"))}"
                StatsRange.MONTH -> start.format(monthLabel)
                StatsRange.YEAR -> start.year.toString()
            }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        IconButton(onClick = { onShift(1) }, enabled = offset < 0) { Icon(Icons.Rounded.ChevronRight, "Weiter") }
    }
}

@Composable
private fun SummaryTiles(summary: RangeSummary, metric: StatsMetric, streak: Int, range: StatsRange) {
    val windowName = if (metric == StatsMetric.FIVE) "5-Std.-Fenster" else "Wochenfenster"
    val tiles = listOf(
        Triple("Verbrauch", formatUsage(summary.total, metric), "im Zeitraum"),
        Triple("Resets", summary.resets.toString(), "$windowName zurückgesetzt"),
        Triple("Aktive Tage", "${summary.activeDays}", "von ${summary.measuredDays} gemessenen"),
        Triple("Bester Tag", summary.bestDay?.let { formatUsage(it.value(metric), metric) } ?: "–", summary.bestDay?.let { LocalDate.parse(it.date).format(dayMonth) } ?: "keine Nutzung"),
        Triple("Ø pro aktivem Tag", if (summary.activeDays == 0) "–" else formatUsage(summary.averagePerActiveDay, metric), "$windowName gestartet: ${summary.runs}"),
        Triple("Serie", "$streak ${if (streak == 1) "Tag" else "Tage"}", "in Folge mit Nutzung")
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (title, value, sub) -> StatTile(title, value, sub, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(value, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tile") {
                Text(it, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Intensity of a day between "measured, nothing used" and the busiest day of the view. */
private fun intensity(day: LedgerDay?, metric: StatsMetric, scale: Double): Float? =
    if (day == null || !day.measured) null else (day.value(metric) / scale).toFloat().coerceIn(0f, 1f)

@Composable
private fun WeekChart(byDate: Map<String, LedgerDay>, period: ClosedRange<LocalDate>, today: LocalDate, metric: StatsMetric, selected: String?, pick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val days = List(7) { period.start.plusDays(it.toLong()) }
    val values = days.map { day -> byDate[day.toString()]?.takeIf { it.measured }?.value(metric) }
    val top = (values.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(if (metric == StatsMetric.FIVE) 100.0 else 10.0)
    val reveal = remember(period.start, metric) { Animatable(0f) }
    LaunchedEffect(period.start, metric) { reveal.animateTo(1f, softSpring) }
    Canvas(Modifier.fillMaxWidth().height(180.dp).pointerInput(period.start) {
        detectTapGestures { position -> val index = (position.x / (size.width / 7f)).toInt().coerceIn(0, 6); if (days[index] <= today) pick(days[index].toString()) }
    }.semantics { contentDescription = "Verbrauch pro Tag dieser Woche" }) {
        val slot = size.width / 7
        val barWidth = (slot * .46f).coerceAtMost(26.dp.toPx())
        val labelSpace = 22.dp.toPx()
        val valueSpace = 18.dp.toPx()
        val chart = size.height - labelSpace - valueSpace
        val base = size.height - labelSpace
        days.forEachIndexed { i, day ->
            val x = i * slot + (slot - barWidth) / 2
            val value = values[i]
            val isSelected = day.toString() == selected
            // The bar is never wider than it is tall: any usage shows as at least a full circle.
            val height = if (value == null || value <= 0) 0f else (value / top * chart * reveal.value).toFloat().coerceAtLeast(barWidth)
            drawRoundRect(colors.surfaceContainerHighest.copy(alpha = if (day > today) .35f else 1f), Offset(x, base - chart), Size(barWidth, chart), CornerRadius(barWidth / 2))
            when {
                value == null -> Unit
                value <= 0 -> drawCircle(colors.onSurfaceVariant.copy(alpha = .5f), barWidth * .16f, Offset(x + barWidth / 2, base - barWidth / 2))
                else -> drawRoundRect(if (isSelected) colors.primary else colors.primary.copy(alpha = .7f), Offset(x, base - height), Size(barWidth, height), CornerRadius(barWidth / 2))
            }
        }
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; textSize = 11.sp.toPx() }
            days.forEachIndexed { i, day ->
                paint.color = (if (day.toString() == selected) colors.primary else colors.onSurfaceVariant).toArgb()
                paint.isFakeBoldText = day.toString() == selected
                canvas.nativeCanvas.drawText(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix("."), i * slot + slot / 2, size.height - 4.dp.toPx(), paint)
                val value = values[i]
                if (value != null && value > 0 && reveal.value > .9f) {
                    paint.color = colors.onSurface.toArgb(); paint.textSize = 10.sp.toPx(); paint.isFakeBoldText = false
                    val height = (value / top * chart).toFloat().coerceAtLeast(barWidth)
                    canvas.nativeCanvas.drawText(shortUsage(value, metric), i * slot + slot / 2, base - height - 5.dp.toPx(), paint)
                    paint.textSize = 11.sp.toPx()
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(byDate: Map<String, LedgerDay>, period: ClosedRange<LocalDate>, today: LocalDate, metric: StatsMetric, selected: String?, pick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val first = period.start
    val lead = first.dayOfWeek.value - 1
    val total = lead + first.lengthOfMonth()
    val rows = (total + 6) / 7
    val scale = (daysOf(first, first.plusMonths(1).minusDays(1)).maxOfOrNull { (byDate[it.toString()]?.takeIf { d -> d.measured }?.value(metric)) ?: 0.0 } ?: 0.0)
        .coerceAtLeast(if (metric == StatsMetric.FIVE) 100.0 else 10.0)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DayOfWeek.entries.forEach { Text(it.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix(".").take(2), Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        }
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(7) { column ->
                    val dayNumber = row * 7 + column - lead + 1
                    if (dayNumber < 1 || dayNumber > first.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f))
                    else {
                        val date = first.withDayOfMonth(dayNumber)
                        val record = byDate[date.toString()]
                        val level = intensity(record, metric, scale)
                        val future = date > today
                        val background = when {
                            future -> Color.Transparent
                            level == null -> colors.surfaceContainerHigh.copy(alpha = .6f)
                            level == 0f -> colors.primary.copy(alpha = .07f)
                            else -> colors.primary.copy(alpha = .2f + .8f * level)
                        }
                        val shape = RoundedCornerShape(10.dp)
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(shape).background(background)
                            .then(if (date.toString() == selected) Modifier.border(2.dp, colors.primary, shape) else if (date == today) Modifier.border(1.dp, colors.outline, shape) else Modifier)
                            .semantics { contentDescription = "${date.format(dayMonth)}: ${record?.takeIf { it.measured }?.let { formatUsage(it.value(metric), metric) } ?: "keine Messung"}" }
                            .clickable(enabled = !future) { pick(date.toString()) }, contentAlignment = Alignment.Center) {
                            Text(dayNumber.toString(), style = MaterialTheme.typography.labelSmall,
                                color = if (level != null && level > .55f) colors.onPrimary else if (future) colors.onSurfaceVariant.copy(alpha = .4f) else colors.onSurface)
                        }
                    }
                }
            }
        }
        LegendRow()
    }
}

@Composable
private fun LegendRow() {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).background(colors.surfaceContainerHigh.copy(alpha = .6f), RoundedCornerShape(3.dp)))
        Text("keine Messung", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text("Weniger", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        listOf(.12f, .3f, .5f, .75f, 1f).forEach { alpha -> Box(Modifier.size(12.dp).background(colors.primary.copy(alpha = alpha), RoundedCornerShape(3.dp))) }
        Text("Mehr", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun YearView(byDate: Map<String, LedgerDay>, period: ClosedRange<LocalDate>, today: LocalDate, metric: StatsMetric, selected: String?, pick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val year = period.start.year
    val first = period.start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weeks = ((period.endInclusive.toEpochDay() - first.toEpochDay()) / 7 + 1).toInt()
    val scale = daysOf(period.start, period.endInclusive).maxOfOrNull { (byDate[it.toString()]?.takeIf { d -> d.measured }?.value(metric)) ?: 0.0 }
        ?.coerceAtLeast(if (metric == StatsMetric.FIVE) 100.0 else 10.0) ?: 100.0
    val reveal = remember(year, metric) { Animatable(0f) }
    LaunchedEffect(year, metric) { reveal.animateTo(1f, softSpring) }
    val scroll = rememberScrollState()
    val cellDp = 15.dp
    val gapDp = 3.dp
    val monthRowDp = 16.dp
    // The year is wider than the screen on purpose: cells stay large enough to read and tap, and the view starts at today.
    LaunchedEffect(year, scroll.maxValue) {
        scroll.scrollTo(if (period.endInclusive >= today) scroll.maxValue else 0)
    }
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
            }.semantics { contentDescription = "Jahresübersicht $year" }) {
                val cell = cellDp.toPx()
                val g = gapDp.toPx()
                val monthRow = monthRowDp.toPx()
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.sp.toPx(); color = colors.onSurfaceVariant.toArgb() }
                    for (week in 0 until weeks) {
                        val monday = first.plusWeeks(week.toLong())
                        val label = (0..6).map { monday.plusDays(it.toLong()) }.firstOrNull { it.dayOfMonth == 1 && it.year == year }
                        if (label != null) canvas.nativeCanvas.drawText(label.month.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix("."), week * (cell + g), 11.dp.toPx(), paint)
                    }
                }
                for (week in 0 until weeks) for (row in 0..6) {
                    val date = first.plusDays(week * 7L + row)
                    if (date !in period) continue
                    val level = intensity(byDate[date.toString()], metric, scale)
                    val color = when {
                        date > today -> colors.surfaceContainerHigh.copy(alpha = .25f)
                        level == null -> colors.surfaceContainerHigh.copy(alpha = .6f)
                        level == 0f -> colors.primary.copy(alpha = .08f)
                        else -> colors.primary.copy(alpha = (.22f + .78f * level) * reveal.value.coerceAtLeast(.15f))
                    }
                    val x = week * (cell + g)
                    val y = monthRow + row * (cell + g)
                    drawRoundRect(color, Offset(x, y), Size(cell, cell), CornerRadius(cell * .3f))
                    if (date.toString() == selected) drawRoundRect(colors.primary, Offset(x, y), Size(cell, cell), CornerRadius(cell * .3f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
            }
        }
        LegendRow()
        Text("Pro Monat", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        val monthly = (1..12).map { month ->
            val start = LocalDate.of(year, month, 1)
            val parts = daysOf(start, start.plusMonths(1).minusDays(1)).mapNotNull { byDate[it.toString()]?.takeIf { d -> d.measured } }
            if (parts.isEmpty()) null else parts.sumOf { it.value(metric) }
        }
        MiniBars(monthly, (1..12).map { Month.of(it).getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix(".").take(3) }, height = 120)
    }
}

@Composable
private fun DayDetail(date: LocalDate, record: LedgerDay?, metric: StatsMetric, today: LocalDate) {
    SoftCard {
        Text(date.format(dayLabel), style = MaterialTheme.typography.titleMedium)
        if (record == null || !record.measured) {
            Text(if (date > today) "Liegt in der Zukunft." else "An diesem Tag hat die App den Verbrauch nicht gelesen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(formatUsage(record.value(metric), metric), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (record.legacy) Text("Aus dem früheren Verlauf: höchster Wert des Tages.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                DetailFact("Fenster gestartet", record.runs(metric).toString())
                DetailFact("Resets", record.resets(metric).toString())
                if (metric == StatsMetric.FIVE) DetailFact("Spitze", "${percent(record.peak)} %")
                DetailFact("Messungen", record.reads.toString())
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
    val colors = MaterialTheme.colorScheme
    val rows = accounts.map { it to LedgerStats.summarize(it.ledger.days, period, metric) }
    val top = rows.maxOf { it.second.total }.coerceAtLeast(1.0)
    val sum = rows.sumOf { it.second.total }
    SoftCard {
        Text("Pro Account", style = MaterialTheme.typography.titleMedium)
        rows.sortedByDescending { it.second.total }.forEach { (entry, summary) ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BrandIcon(entry.provider, Modifier.size(20.dp))
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(formatUsage(summary.total, metric), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                UsageTrack((summary.total / top).toFloat(), colors.primary, Modifier.fillMaxWidth().height(8.dp))
                Text("${if (sum > 0) percent(Math.round(summary.total / sum * 100).toDouble()) else "0"} % der Nutzung · ${summary.resets} Resets · ${summary.activeDays} aktive Tage",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WeekdayCard(summary: RangeSummary, metric: StatsMetric, range: StatsRange, period: ClosedRange<LocalDate>) {
    val colors = MaterialTheme.colorScheme
    val averages = summary.weekdayAverage
    val top = (averages.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val basis = when (range) {
        StatsRange.WEEK -> "Ø pro Tag, letzte 8 Wochen"
        StatsRange.MONTH -> "Ø pro Tag im ${period.start.format(monthLabel)}"
        StatsRange.YEAR -> "Ø pro Tag ${period.start.year}"
    }
    SoftCard {
        Text("Wochentage", style = MaterialTheme.typography.titleMedium)
        val busiest = summary.busiestWeekday
        Text(if (busiest == null) "Noch zu wenig Daten" else "Am meisten: ${DayOfWeek.of(busiest + 1).getDisplayName(TextStyle.FULL, Locale.GERMAN)}",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (busiest == null) colors.onSurfaceVariant else colors.primary)
        Text(basis, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        val reveal = remember(summary) { Animatable(0f) }
        LaunchedEffect(summary) { reveal.animateTo(1f, softSpring) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            repeat(7) { index ->
                val value = averages[index]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(DayOfWeek.of(index + 1).getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix("."), Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (index == busiest) FontWeight.Bold else FontWeight.Normal)
                    UsageTrack(((value ?: 0.0) / top).toFloat() * reveal.value, if (index == busiest) colors.primary else colors.primary.copy(alpha = .45f), Modifier.weight(1f).height(10.dp))
                    Text(value?.let { formatUsage(it, metric) } ?: "–", Modifier.width(78.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, color = colors.onSurfaceVariant)
                }
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
    LaunchedEffect(values) { reveal.animateTo(1f, softSpring) }
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
