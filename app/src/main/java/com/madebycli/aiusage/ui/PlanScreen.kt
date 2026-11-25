@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.animation.togetherWith
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
        item(key = "week") { WeekAgenda(state, plan, now, zone, Modifier.orionReveal(1)) }
        item(key = "budgets") { BudgetList(state, plan, now, Modifier.orionReveal(2)) }
        item(key = "hours") { HoursCard(plan.hours, starts.size >= 6, Modifier.orionReveal(4)) }
    }
}

/** Who to use now, and the three facts that decide it, each in a tile wide enough for its value. */
@Composable
private fun NowCard(entry: AccountState, budget: Planner.Budget, then: List<AccountState>, now: Long, modifier: Modifier, open: () -> Unit) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().orionSpringClick(cardShape, onClick = open).background(cs.panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProviderBadge(entry.provider, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.plan_use_now), style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    Text(entry.title, style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = cs.onSurfaceVariant)
            }
            budget.weekLeft?.let { left ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${Fmt.percent(left)} %", style = MaterialTheme.typography.displaySmallEmphasized, color = cs.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.plan_fact_week), style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    UsageTrack((left / 100).toFloat(), providerAccent(entry.provider), Modifier.fillMaxWidth().height(10.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlanFact(stringResource(R.string.plan_fact_reset), budget.weekReset?.let { Fmt.clock(res, it, now) } ?: "–", Modifier.weight(1f))
                PlanFact(stringResource(R.string.plan_fact_five), when {
                    budget.blockedByFive -> budget.fiveReset?.let { stringResource(R.string.plan_five_until, Fmt.clockOnly(res, it)) } ?: stringResource(R.string.plan_fact_full)
                    (budget.fiveUsed ?: 0.0) > 0 -> "${Fmt.percent(budget.fiveUsed ?: 0.0)} %"
                    else -> stringResource(R.string.plan_five_free_now)
                }, Modifier.weight(1f))
            }
            if (then.isNotEmpty()) Text(stringResource(R.string.plan_then_list, then.joinToString(", ") { it.title }), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlanFact(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.panelRaised, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.titleMedium.fontSize))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A colour per account: light tones of the Orion hues, in account order. */
@Composable
private fun accountColors(accounts: List<AccountState>): Map<String, Color> {
    val hues = listOf(OrionAccents.Blue, OrionAccents.Pink, OrionAccents.Teal, OrionAccents.Orange, OrionAccents.Yellow, OrionAccents.Purple)
    return accounts.mapIndexed { i, a -> a.id to orionTonal(hues[i % hues.size]).second }.toMap()
}

private sealed interface AgendaItem { val at: Long }
private data class AgendaSession(val session: Planner.Session) : AgendaItem { override val at get() = session.start }
private data class AgendaReset(val accountId: String, override val at: Long) : AgendaItem

/**
 * The next seven days as a calendar strip, one dot per planned window in the colour of its account and a ring on days
 * with a weekly reset; below it the selected day as a list of windows and resets.
 */
@Composable
private fun WeekAgenda(state: UiState, plan: Planner.Plan, now: Long, zone: ZoneId, modifier: Modifier) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    val colors = accountColors(state.accounts)
    val byId = state.accounts.associateBy { it.id }
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val days = List(Planner.HORIZON_DAYS) { today.plusDays(it.toLong()) }
    fun dayOf(t: Long) = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
    val items: Map<java.time.LocalDate, List<AgendaItem>> = remember(plan, now / 60) {
        val sessions = plan.sessions.map { AgendaSession(it) }
        val resets = plan.budgets.flatMap { b -> b.weekReset?.let { r -> generateSequence(r) { it + ONE_WEEK }.takeWhile { dayOf(it) <= days.last() }.map { AgendaReset(b.accountId, it) }.toList() }.orEmpty() }
        (sessions + resets).groupBy { dayOf(it.at) }.mapValues { (_, list) -> list.sortedBy { it.at } }
    }
    var selected by remember { mutableIntStateOf(0) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.plan_week))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { i, day ->
                val chosen = i == selected
                val list = items[day].orEmpty()
                val hasReset = list.any { it is AgendaReset }
                val container by androidx.compose.animation.animateColorAsState(if (chosen) cs.primaryContainer else cs.panel, MaterialTheme.motionScheme.fastEffectsSpec(), label = "day")
                val corner by androidx.compose.animation.core.animateDpAsState(if (chosen) 22.dp else 14.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
                Column(Modifier.weight(1f).orionSpringClick(RoundedCornerShape(corner)) { selected = i }.background(container, RoundedCornerShape(corner))
                    .then(if (hasReset && !chosen) Modifier.border(1.5.dp, cs.tertiary.copy(.7f), RoundedCornerShape(corner)) else Modifier)
                    .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val content = if (chosen) cs.onPrimaryContainer else cs.onSurface
                    Text(Fmt.weekdayShort(res, day.dayOfWeek.value), style = MaterialTheme.typography.labelSmall, color = if (chosen) content else cs.onSurfaceVariant)
                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = content)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(6.dp)) {
                        list.filterIsInstance<AgendaSession>().take(3).forEach { Box(Modifier.size(6.dp).background(colors[it.session.accountId] ?: cs.primary, CircleShape)) }
                    }
                }
            }
        }
        val list = items[days[selected]].orEmpty()
        androidx.compose.animation.AnimatedContent(selected, transitionSpec = {
            (androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInHorizontally { if (targetState > initialState) it / 6 else -it / 6 }) togetherWith androidx.compose.animation.fadeOut()
        }, label = "agenda") { shown ->
            val shownList = items[days[shown]].orEmpty()
            SettingsGroup {
                if (shownList.isEmpty()) SettingsBlock { Text(stringResource(R.string.plan_day_empty), color = cs.onSurfaceVariant) }
                shownList.forEach { item ->
                    Surface(color = cs.panel, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (item) {
                                is AgendaSession -> {
                                    val entry = byId[item.session.accountId]
                                    Box(Modifier.size(10.dp).background(colors[item.session.accountId] ?: cs.primary, CircleShape))
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.plan_session_at, Fmt.clockOnly(res, item.session.start), Fmt.clockOnly(res, item.session.end)), style = MaterialTheme.typography.titleSmall)
                                        Text(entry?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                                    }
                                    if (item.session.current) Text(stringResource(R.string.plan_legend_running), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                                    Text(Fmt.limits(res, item.session.share * 100), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                                }
                                is AgendaReset -> {
                                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp), tint = cs.tertiary)
                                    Column(Modifier.weight(1f)) {
                                        Text(Fmt.clockOnly(res, item.at), style = MaterialTheme.typography.titleSmall)
                                        Text(stringResource(R.string.plan_reset_row, byId[item.accountId]?.title.orEmpty()), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (list.isNotEmpty() || plan.overlaps.isNotEmpty()) {
            val groups = mutableListOf<MutableSet<String>>()
            plan.overlaps.forEach { (a, b) ->
                val group = groups.firstOrNull { a in it || b in it } ?: mutableSetOf<String>().also { groups += it }
                group += a; group += b
            }
            groups.forEach { group ->
                val names = state.accounts.filter { it.id in group }.map { it.title }
                if (names.size >= 2) Text(stringResource(R.string.plan_overlap, names.dropLast(1).joinToString(", "), names.last()), style = MaterialTheme.typography.labelMedium,
                    color = cs.tertiary, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
            }
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
