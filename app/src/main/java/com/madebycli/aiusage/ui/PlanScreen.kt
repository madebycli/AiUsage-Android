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
        item(key = "week") { WeekLanes(state, plan, now, zone, Modifier.orionReveal(1), open) }
        item(key = "budgets") { BudgetList(state, plan, now, Modifier.orionReveal(2)) }
        val upcoming = plan.sessions.filter { !it.current }.take(6)
        if (upcoming.isNotEmpty()) item(key = "upcoming") { UpcomingList(upcoming, byId, now, Modifier.orionReveal(3)) }
        item(key = "hours") { HoursCard(plan.hours, starts.size >= 6, Modifier.orionReveal(4)) }
    }
}

@Composable
private fun NowCard(entry: AccountState, budget: Planner.Budget, then: List<AccountState>, now: Long, modifier: Modifier, open: () -> Unit) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = orionTonal(providerAccent(entry.provider))
    Box(modifier.fillMaxWidth().orionSpringClick(cardShape, onClick = open).background(bg)) {
        OrionSpinner(MaterialShapes.Sunny, fg.copy(.10f), 220.dp, Modifier.align(Alignment.TopEnd).offset(70.dp, (-60).dp), periodMs = 30_000)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.plan_now), style = MaterialTheme.typography.labelLarge, color = fg)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OrionMorphBlob(listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Flower, MaterialShapes.Sunny), (now / 60 % 3).toInt(), 64.dp, fg.copy(.18f)) {
                    BrandIcon(entry.provider, Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.headlineSmallEmphasized, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    entry.plan?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = fg.copy(.8f)) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                budget.weekLeft?.let { FactChip(stringResource(R.string.plan_week_left, Fmt.percent(it)), fg) }
                budget.weekReset?.let { FactChip(stringResource(R.string.plan_week_reset, Fmt.clock(res, it, now)), fg) }
                when {
                    budget.weekEmpty -> FactChip(stringResource(R.string.plan_week_empty_until, budget.weekReset?.let { Fmt.clock(res, it, now) } ?: "–"), cs.error)
                    budget.blockedByFive -> FactChip(stringResource(R.string.plan_five_full_until, budget.fiveReset?.let { Fmt.clock(res, it, now) } ?: "–"), fg)
                    (budget.fiveUsed ?: 0.0) > 0 -> FactChip(stringResource(R.string.plan_five_used, Fmt.percent(budget.fiveUsed ?: 0.0)), fg)
                    else -> FactChip(stringResource(R.string.plan_five_free), fg)
                }
            }
            if (then.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plan_then), style = MaterialTheme.typography.labelLarge, color = fg.copy(.8f))
                then.forEach { other ->
                    Surface(shape = CircleShape, color = fg.copy(.12f), contentColor = fg) {
                        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BrandIcon(other.provider, Modifier.size(20.dp))
                            Text(other.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(.12f), contentColor = color) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Seven days, one lane per account. Tapping a lane opens the account. */
@Composable
private fun WeekLanes(state: UiState, plan: Planner.Plan, now: Long, zone: ZoneId, modifier: Modifier, open: (String) -> Unit) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val start = today.atStartOfDay(zone).toEpochSecond()
    val end = start + Planner.HORIZON_DAYS * 86_400L
    val accounts = state.accounts
    val reveal = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { reveal.animateTo(1f, spec) }
    val laneHeight = 44.dp
    val labelWidth = 0.dp
    SoftCard(modifier = modifier) {
        Text(stringResource(R.string.plan_week), style = MaterialTheme.typography.titleMedium)
        // Day names over the lanes.
        Row(Modifier.fillMaxWidth().padding(start = labelWidth)) {
            repeat(Planner.HORIZON_DAYS) { d ->
                val date = today.plusDays(d.toLong())
                Text(Fmt.weekdayShort(res, date.dayOfWeek.value), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = if (d == 0) cs.primary else cs.onSurfaceVariant, fontWeight = if (d == 0) FontWeight.Bold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        accounts.forEach { entry ->
            val budget = plan.budgets.firstOrNull { it.accountId == entry.id } ?: return@forEach
            val accent = providerAccent(entry.provider)
            val (tint, onTint) = orionTonal(accent)
            val sessions = plan.sessions.filter { it.accountId == entry.id }
            val overlap = plan.overlaps.any { it.first == entry.id || it.second == entry.id }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandIcon(entry.provider, Modifier.size(18.dp))
                Text(entry.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                budget.weekLeft?.let { Text("${Fmt.percent(it)} %", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant) }
            }
            val description = listOfNotNull(entry.title, budget.weekReset?.let { stringResource(R.string.plan_week_reset, Fmt.clock(res, it, now)) }).joinToString(", ")
            Canvas(Modifier.fillMaxWidth().height(laneHeight).semantics { contentDescription = description }.pointerInput(entry.id) { detectTapGestures { open(entry.id) } }) {
                val w = size.width; val h = size.height
                fun x(t: Long) = ((t - start).toFloat() / (end - start) * w).coerceIn(0f, w)
                val r = CornerRadius(h / 2.6f)
                drawRoundRect(cs.surfaceContainerHighest.copy(alpha = .45f), Offset.Zero, Size(w, h), r)
                // Day separators.
                for (d in 1 until Planner.HORIZON_DAYS) drawLine(cs.outlineVariant.copy(.35f), Offset(w * d / Planner.HORIZON_DAYS, 4f), Offset(w * d / Planner.HORIZON_DAYS, h - 4f), 1.dp.toPx())
                // Remaining weekly budget until its reset, as a tinted band whose height is what is left.
                val left = ((budget.weekLeft ?: 0.0) / 100).toFloat()
                val bandEnd = budget.weekReset?.let { x(it) } ?: w
                if (left > 0f) drawRoundRect(tint.copy(alpha = .9f), Offset(x(now), h * (1 - left)), Size(((bandEnd - x(now)) * reveal.value).coerceAtLeast(0f), h * left), r)
                // After a reset the week starts full again.
                budget.weekReset?.let { reset ->
                    var t = reset
                    while (t < end) {
                        val nx = minOf(x(t + ONE_WEEK), w)
                        drawRoundRect(tint.copy(alpha = .45f), Offset(x(t), 0f), Size((nx - x(t)) * reveal.value, h), r)
                        t += ONE_WEEK
                    }
                }
                sessions.forEach { s ->
                    val sx = x(s.start); val ex = x(s.end)
                    if (ex <= 0f || sx >= w) return@forEach
                    val color = if (s.current) accent else accent.copy(alpha = .55f)
                    drawRoundRect(color, Offset(sx, h * .22f), Size((ex - sx).coerceAtLeast(6.dp.toPx()) * reveal.value, h * .56f), CornerRadius(h * .28f))
                }
                // Reset markers, red-ish when two accounts reset close together.
                budget.weekReset?.let { reset ->
                    var t = reset
                    while (t < end) {
                        val rx = x(t)
                        drawLine(if (overlap) cs.error else onTint, Offset(rx, 2f), Offset(rx, h - 2f), 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawCircle(if (overlap) cs.error else onTint, 4.dp.toPx(), Offset(rx, 4.dp.toPx()))
                        t += ONE_WEEK
                    }
                }
                // Now.
                val nx = x(now)
                drawLine(cs.onSurface, Offset(nx, 0f), Offset(nx, h), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }
        Legend()
        plan.overlaps.forEach { (a, b) ->
            val first = state.account(a)?.title ?: return@forEach
            val second = state.account(b)?.title ?: return@forEach
            Text(stringResource(R.string.plan_overlap, first, second), style = MaterialTheme.typography.labelMedium, color = cs.error)
        }
    }
}

@Composable
private fun Legend() {
    val cs = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable fun Item(color: Color, label: String, round: Boolean = true) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(width = if (round) 14.dp else 3.dp, height = 10.dp).background(color, RoundedCornerShape(5.dp)))
            Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Item(cs.primary, stringResource(R.string.plan_legend_running))
        Item(cs.primary.copy(alpha = .55f), stringResource(R.string.plan_legend_session))
        Item(cs.onSurface, stringResource(R.string.plan_legend_reset), round = false)
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
