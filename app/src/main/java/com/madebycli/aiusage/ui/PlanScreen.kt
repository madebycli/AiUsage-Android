@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madebycli.aiusage.AccountState
import com.madebycli.aiusage.R
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.ZoneId

/** The planner: which account to use now, and a week of lanes with resets, the running window and suggested windows. */
@Composable
internal fun PlanTab(state: UiState, now: Long, open: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    // Minute resolution is enough and keeps the plan from being rebuilt every tick.
    val minute = now / 60 * 60
    val inputs = remember(state.accounts.map { it.usage to it.ledger }) {
        state.accounts.map { entry ->
            Planner.Input(entry.id, entry.windows().firstOrNull { it.seconds == FIVE_HOURS }, entry.windows().firstOrNull { it.seconds == ONE_WEEK },
                UsageTracker.upgraded(entry.ledger).calibration.weekPerLimit)
        }
    }
    val starts = remember(state.accounts.map { it.ledger }) {
        state.accounts.flatMap { e -> UsageTracker.upgraded(e.ledger).fiveCycles.filter { !it.migrated && it.peak > 0 }.map { it.start(FIVE_HOURS) } }
    }
    val plan = remember(inputs, starts, minute) { Planner.plan(inputs, minute, starts, zone) }
    val byId = state.accounts.associateBy { it.id }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val first = plan.order.firstOrNull()?.let(byId::get)
        if (first != null) item(key = "now") {
            NowCard(first, plan.budgets.first { it.accountId == first.id }, plan.order.drop(1).mapNotNull(byId::get).take(2), now, Modifier.orionReveal(0)) { open(first.id) }
        }
        item(key = "week") { WeekChart(state, plan, now, zone, Modifier.orionReveal(1)) }
        item(key = "budgets") { BudgetList(state, plan, now, Modifier.orionReveal(2)) }
        val upcoming = plan.sessions.filter { !it.current }.take(6)
        if (upcoming.isNotEmpty()) item(key = "upcoming") { UpcomingList(upcoming, byId, now, Modifier.orionReveal(3)) }
        item(key = "hours") { HoursCard(plan.hours, starts.size >= 6, Modifier.orionReveal(4)) }
    }
}

/** Who to use now: the account, its two windows as filling shapes, and the facts that decide it. Calm, like a usage card. */
@Composable
private fun NowCard(entry: AccountState, budget: Planner.Budget, then: List<AccountState>, now: Long, modifier: Modifier, open: () -> Unit) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    val five = entry.windows().firstOrNull { it.seconds == FIVE_HOURS }
    val week = entry.windows().firstOrNull { it.seconds == ONE_WEEK }
    Box(modifier.fillMaxWidth().orionSpringClick(cardShape, onClick = open).background(cs.panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandIcon(entry.provider)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.plan_now), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                entry.plan?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant) }
            }
            Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                listOfNotNull(five, week).forEachIndexed { i, window ->
                    LiquidShape(if (window.seconds == FIVE_HOURS) MaterialShapes.Cookie9Sided else MaterialShapes.Flower, (window.usedPercent / 100).toFloat(),
                        windowColor(window, cs), 92.dp, i) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${Fmt.percent(window.usedPercent)}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(windowTitle(window.seconds), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            val facts = listOf(
                stringResource(R.string.plan_fact_week) to (budget.weekLeft?.let { "${Fmt.percent(it)} %" } ?: "–"),
                stringResource(R.string.plan_fact_reset) to (budget.weekReset?.let { Fmt.clock(res, it, now) } ?: "–"),
                stringResource(R.string.plan_fact_five) to when {
                    budget.blockedByFive -> budget.fiveReset?.let { Fmt.clockOnly(res, it) } ?: stringResource(R.string.plan_fact_full)
                    (budget.fiveUsed ?: 0.0) > 0 -> budget.fiveReset?.let { Fmt.clockOnly(res, it) } ?: "${Fmt.percent(budget.fiveUsed ?: 0.0)} %"
                    else -> stringResource(R.string.plan_fact_free)
                })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                facts.forEachIndexed { i, (label, value) ->
                    Column(Modifier.weight(if (i == 1) 1.5f else 1f).background(cs.panelRaised, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (then.isNotEmpty()) Text(stringResource(R.string.plan_then_list, then.joinToString(", ") { it.title }), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** A colour per account for charts: light tones of the Orion hues, in account order. */
@Composable
private fun accountColors(accounts: List<AccountState>): Map<String, Color> {
    val hues = listOf(OrionAccents.Blue, OrionAccents.Pink, OrionAccents.Teal, OrionAccents.Orange, OrionAccents.Yellow, OrionAccents.Purple)
    return accounts.mapIndexed { i, a -> a.id to orionTonal(hues[i % hues.size]).second }.toMap()
}

/**
 * The next seven days in the style of the statistics chart: one column per day with the suggested windows stacked by
 * account (height = share of a limit), and a dot under the day for every weekly reset.
 */
@Composable
private fun WeekChart(state: UiState, plan: Planner.Plan, now: Long, zone: ZoneId, modifier: Modifier) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    val colors = accountColors(state.accounts)
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val days = List(Planner.HORIZON_DAYS) { today.plusDays(it.toLong()) }
    fun dayOf(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
    val perDay = days.map { day -> plan.sessions.filter { dayOf(it.start) == day } }
    val resets = days.map { day -> plan.budgets.filter { b -> b.weekReset?.let { r -> generateSequence(r) { it + ONE_WEEK }.takeWhile { dayOf(it) <= day }.any { dayOf(it) == day } } == true } }
    val top = (perDay.maxOfOrNull { list -> list.sumOf { it.share } } ?: 0.0).coerceAtLeast(3.0)
    val reveal = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { reveal.animateTo(1f, spec) }
    SoftCard(modifier = modifier) {
        Text(stringResource(R.string.plan_week), style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = "" }) {
            val slot = size.width / 7
            val barWidth = (slot * .5f).coerceAtMost(28.dp.toPx())
            val labelSpace = 34.dp.toPx()
            val valueSpace = 16.dp.toPx()
            val chart = size.height - labelSpace - valueSpace
            val base = size.height - labelSpace
            days.forEachIndexed { i, _ ->
                val x = i * slot + (slot - barWidth) / 2
                drawRoundRect(cs.surfaceContainerHighest, Offset(x, base - chart), Size(barWidth, chart), CornerRadius(barWidth / 2))
                var y = base
                perDay[i].forEach { s ->
                    val h = (s.share / top * chart * reveal.value).toFloat()
                    val color = colors[s.accountId] ?: cs.primary
                    drawRoundRect(if (s.current) color else color.copy(alpha = .75f), Offset(x, y - h), Size(barWidth, h), CornerRadius(barWidth / 2))
                    y -= h + 2.dp.toPx()
                }
                resets[i].forEachIndexed { j, b ->
                    val cx = i * slot + slot / 2 + (j - (resets[i].size - 1) / 2f) * 9.dp.toPx()
                    drawCircle(colors[b.accountId] ?: cs.primary, 3.5.dp.toPx(), Offset(cx, size.height - 6.dp.toPx()))
                }
            }
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; textSize = 11.sp.toPx() }
                days.forEachIndexed { i, day ->
                    paint.color = (if (i == 0) cs.primary else cs.onSurfaceVariant).toArgb(); paint.isFakeBoldText = i == 0
                    canvas.nativeCanvas.drawText(Fmt.weekdayShort(res, day.dayOfWeek.value), i * slot + slot / 2, base + 18.dp.toPx(), paint)
                    val sum = perDay[i].sumOf { it.share }
                    if (sum > 0 && reveal.value > .9f) {
                        paint.color = cs.onSurface.toArgb(); paint.isFakeBoldText = false; paint.textSize = 10.sp.toPx()
                        canvas.nativeCanvas.drawText(Fmt.number(res, sum, 1), i * slot + slot / 2, base - (sum / top * chart).toFloat() - 6.dp.toPx(), paint)
                        paint.textSize = 11.sp.toPx()
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.accounts.filter { a -> plan.sessions.any { it.accountId == a.id } || plan.budgets.any { it.accountId == a.id && it.weekReset != null } }.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(colors[a.id] ?: cs.primary, CircleShape))
                    Text(a.title, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(cs.onSurfaceVariant, CircleShape))
                Text(stringResource(R.string.plan_legend_reset), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        // Pairs are joined into groups, so three accounts resetting together give one line.
        val groups = mutableListOf<MutableSet<String>>()
        plan.overlaps.forEach { (a, b) ->
            val group = groups.firstOrNull { a in it || b in it } ?: mutableSetOf<String>().also { groups += it }
            group += a; group += b
        }
        groups.forEach { group ->
            val names = state.accounts.filter { it.id in group }.map { it.title }
            if (names.size >= 2) Text(stringResource(R.string.plan_overlap, names.dropLast(1).joinToString(", "), names.last()), style = MaterialTheme.typography.labelMedium, color = cs.tertiary)
        }
    }
}

@Composable
private fun BudgetList(state: UiState, plan: Planner.Plan, now: Long, modifier: Modifier) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SettingsSectionLabel(stringResource(R.string.plan_budgets))
        SettingsGroup {
            plan.order.mapNotNull { id -> state.account(id)?.let { it to plan.budgets.first { b -> b.accountId == id } } }.forEach { (entry, budget) ->
                Surface(color = cs.panel, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProviderBadge(entry.provider, 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(budget.weekReset?.let { stringResource(R.string.plan_week_reset, Fmt.clock(res, it, now)) } ?: stringResource(R.string.plan_no_week),
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            budget.weekLeft?.let { Text("${Fmt.percent(it)} %", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                        }
                        budget.weekLeft?.let { UsageTrack((it / 100).toFloat(), providerAccent(entry.provider), Modifier.fillMaxWidth().height(8.dp)) }
                        val facts = listOfNotNull(
                            budget.sessionsLeft?.let { val n = Math.round(it * 10) / 10.0; pluralStringResource(R.plurals.plan_sessions, if (n == 1.0) 1 else 2, Fmt.number(res, n, 1)) },
                            budget.perDay?.takeIf { budget.weekReset != null && budget.weekReset - now > 86_400 }?.let { stringResource(R.string.plan_per_day, Fmt.percent(it)) })
                        if (facts.isNotEmpty()) Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        if (budget.sessionsLeft == null && budget.weekLeft != null) Text(stringResource(R.string.plan_no_calibration), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        plan.expiring[entry.id]?.let { Text(stringResource(R.string.plan_expiring, Fmt.percent(it)), style = MaterialTheme.typography.labelMedium, color = cs.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingList(sessions: List<Planner.Session>, accounts: Map<String, AccountState>, now: Long, modifier: Modifier) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        SettingsSectionLabel(stringResource(R.string.plan_upcoming))
        SettingsGroup {
            sessions.forEach { s ->
                val entry = accounts[s.accountId] ?: return@forEach
                Surface(color = cs.panel, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandIcon(entry.provider, Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.plan_session_at, Fmt.clock(res, s.start, now), Fmt.clockOnly(res, s.end)), style = MaterialTheme.typography.titleSmall)
                            Text(entry.title, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                        Text(Fmt.limits(res, s.share * 100), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** The hours of the day the planner places windows at. */
@Composable
private fun HoursCard(hours: List<Int>, learned: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    SoftCard(modifier = modifier) {
        Text(stringResource(R.string.plan_hours), style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val slot = size.width / 24
            for (h in 0 until 24) {
                val active = hours.any { Math.floorMod(h - it, 24) < 5 }
                val begin = h in hours
                drawRoundRect(if (begin) cs.primary else if (active) cs.primary.copy(.35f) else cs.surfaceContainerHighest, Offset(h * slot + 1, 0f), Size(slot - 2, size.height * .6f), CornerRadius(4.dp.toPx()))
            }
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER; textSize = 10.sp.toPx(); color = cs.onSurfaceVariant.toArgb() }
                listOf(0, 6, 12, 18).forEach { canvas.nativeCanvas.drawText("%02d".format(it), it * slot + slot / 2, size.height - 1, paint) }
            }
        }
        if (!learned) Text(stringResource(R.string.plan_hours_default), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}
