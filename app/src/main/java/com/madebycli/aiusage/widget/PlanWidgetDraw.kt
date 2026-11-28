package com.madebycli.aiusage.widget

import android.graphics.Paint
import com.madebycli.aiusage.R
import com.madebycli.aiusage.Texts
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * The planner widget: which account to use now, which ones next, and on large widgets the coming week with the
 * suggested windows and weekly resets per account. Same plan as the planner tab.
 */
internal object PlanWidgetDraw {
    fun plan(accounts: List<WidgetAccount>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Planner.Plan {
        val minute = now / 60 * 60
        val inputs = accounts.map { a ->
            Planner.Input(a.id, a.window(FIVE_HOURS), a.window(ONE_WEEK), UsageTracker.upgraded(a.ledger).calibration.weekPerLimit)
        }
        val starts = accounts.flatMap { a -> UsageTracker.upgraded(a.ledger).fiveCycles.filter { !it.migrated && it.peak > 0 }.map { it.start(FIVE_HOURS) } }
        return Planner.plan(inputs, minute, starts, zone)
    }

    /** What the account can give right now: free weekly share, or when it is free again. */
    private fun state(b: Planner.Budget, now: Long): String = when {
        b.weekEmpty -> b.weekReset?.let { Texts.get(R.string.widget_free_at, Fmt.clock(Texts.resources, it, now)) } ?: Texts.get(R.string.plan_fact_full)
        b.blockedByFive -> b.fiveReset?.let { Texts.get(R.string.widget_free_at, Fmt.clock(Texts.resources, it, now)) } ?: Texts.get(R.string.plan_fact_full)
        b.weekLeft != null -> Texts.get(R.string.widget_week_left_value, Fmt.percent(b.weekLeft))
        b.fiveUsed != null -> Texts.get(R.string.widget_five_left_value, Fmt.percent(100 - b.fiveUsed))
        else -> Texts.get(R.string.usage_no_reading)
    }

    /** The same in a few words, for the list of accounts that come next. */
    private fun shortState(b: Planner.Budget, now: Long): String = when {
        b.weekEmpty || b.blockedByFive -> (if (b.weekEmpty) b.weekReset else b.fiveReset)?.let { Texts.get(R.string.widget_from, compactClock(it, now)) } ?: Texts.get(R.string.plan_fact_full)
        b.weekLeft != null -> Texts.get(R.string.widget_left_short, Fmt.percent(b.weekLeft))
        b.fiveUsed != null -> Texts.get(R.string.widget_left_short, Fmt.percent(100 - b.fiveUsed))
        else -> "–"
    }

    /** "17:40" today, otherwise "Sat 17:40". */
    private fun compactClock(at: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochSecond(at).atZone(zone)
        val time = Fmt.clockOnly(Texts.resources, at)
        return if (day.toLocalDate() == Instant.ofEpochSecond(now).atZone(zone).toLocalDate()) time
            else "${Fmt.weekdayShort(Texts.resources, day.dayOfWeek.value)} $time"
    }

    fun draw(p: Painter, config: WidgetConfig, accounts: List<WidgetAccount>, area: Area, now: Long, tile: Int) {
        val c = p.palette
        if (accounts.isEmpty()) return
        val plan = plan(accounts, now)
        val byId = accounts.associateBy { it.id }
        val order = plan.order.mapNotNull { byId[it] }
        val first = order.firstOrNull() ?: return
        val budget = plan.budgets.first { it.accountId == first.id }
        val wide = area.w >= 280f && area.h >= 64f
        val week = area.h >= 196f && order.isNotEmpty()
        val heroH = if (week) min(area.h * .5f, 124f) else area.h
        val heroW = if (wide && order.size > 1) area.w * .56f else area.w
        hero(p, first, budget, Area(area.x, area.y, heroW, heroH), now)
        if (wide && order.size > 1) {
            val listX = area.x + heroW + 10f
            val list = Area(listX, area.y, area.x + area.w - listX, heroH)
            p.roundRect(list.x, list.y, list.w, list.h, 18f, tile)
            p.text(Texts.get(R.string.widget_then), list.x + 12f, list.y + 19f, 11f, p.bold, c.muted, maxWidth = list.w - 20f)
            val rows = order.drop(1)
            val rowH = 34f
            val fit = max(1, ((list.h - 28f) / rowH).toInt())
            rows.take(fit).forEachIndexed { i, a ->
                val y = list.y + 28f + i * rowH
                val b = plan.budgets.first { it.accountId == a.id }
                p.brand(a.provider, list.x + 12f, y + 3f, 14f)
                p.text(a.title, list.x + 32f, y + 14f, 12.5f, p.bold, c.text, maxWidth = list.w - 44f)
                p.text(shortState(b, now), list.x + 32f, y + 28f, 10.5f, p.medium, if (b.weekEmpty || b.blockedByFive) c.muted else c.text, maxWidth = list.w - 44f)
            }
        }
        if (week) weekGrid(p, plan, order, Area(area.x, area.y + heroH + 10f, area.w, area.h - heroH - 10f), now, tile)
    }

    /** The account to use now: badge, name, free weekly share as a number and a bar, as far as the space allows. */
    private fun hero(p: Painter, a: WidgetAccount, b: Planner.Budget, area: Area, now: Long) {
        val c = p.palette
        val label = Texts.get(if (b.availableAt > now) R.string.widget_next_up else R.string.plan_use_now)
        val free = b.weekLeft?.takeIf { !b.weekEmpty && !b.blockedByFive }
        val number = free?.let { "${Fmt.percent(it)}%" }
        if (area.h < 96f && area.w >= 190f) {
            // A row: who on the left, how much on the right.
            val badge = min(36f, area.h - 4f)
            val top = area.y + (area.h - badge) / 2
            p.brandBadge(a.provider, area.x, top, badge)
            val rightW = if (number != null) max(p.width(number, 26f, p.bold), p.width(Texts.get(R.string.plan_fact_week), 10.5f, p.medium)) else 0f
            val tx = area.x + badge + 10f
            val room = area.x + area.w - tx - (if (number != null) rightW + 10f else 0f)
            val mid = area.y + area.h / 2
            if (p.fits(listOf(label), room, 11f, p.bold)) p.text(label, tx, mid - 4f, 11f, p.bold, c.primary)
            p.text(a.title, tx, mid + 13f, 16f, p.bold, c.text, maxWidth = room)
            if (number != null) {
                p.text(number, area.x + area.w, mid + 4f, 26f, p.bold, c.text, Paint.Align.RIGHT)
                p.text(Texts.get(R.string.plan_fact_week), area.x + area.w, mid + 19f, 10.5f, p.medium, c.muted, Paint.Align.RIGHT)
            } else p.text(shortState(b, now), area.x + area.w, mid + 5f, 12f, p.bold, c.muted, Paint.Align.RIGHT)
            return
        }
        val small = area.h < 110f
        val badge = if (small) 30f else 40f
        p.brandBadge(a.provider, area.x, area.y, badge)
        val tx = area.x + badge + 10f
        val room = area.x + area.w - tx
        val showLabel = p.fits(listOf(label), room, 11f, p.bold)
        if (showLabel) p.text(label, tx, area.y + badge / 2 - 3f, 11f, p.bold, c.primary)
        p.text(a.title, tx, area.y + badge / 2 + (if (showLabel) 13f else 5f), if (small) 14f else 17f, p.bold, c.text, maxWidth = room)
        var y = area.y + badge + 8f
        val bottom = area.y + area.h
        if (number == null) {
            if (bottom - y >= 16f) p.text(state(b, now), area.x, y + 13f, 12f, p.bold, c.muted, maxWidth = area.w)
            return
        }
        val size = listOf(32f, 28f, 24f, 20f, 17f).firstOrNull { it * .86f <= bottom - y && p.width(number, it, p.bold) <= area.w * .6f } ?: return
        y += size * .86f
        p.text(number, area.x, y, size, p.bold, c.text)
        val nx = area.x + p.width(number, size, p.bold) + 6f
        if (p.fits(listOf(Texts.get(R.string.plan_fact_week)), area.x + area.w - nx, 11f, p.medium)) p.text(Texts.get(R.string.plan_fact_week), nx, y, 11f, p.medium, c.muted)
        if (y + 16f <= bottom) p.bar(area.x, y + 8f, area.w, 8f, (free!! / 100).toFloat(), WidgetColorsKit.brand(a.provider, c))
        if (y + 34f <= bottom && b.weekReset != null) {
            val options = listOf(Texts.get(R.string.widget_week_reset, Fmt.clock(Texts.resources, b.weekReset, now)), Texts.get(R.string.widget_week_reset, compactClock(b.weekReset, now)))
            p.text(p.fit(options, area.w, 11f, p.medium), area.x, y + 32f, 11f, p.medium, c.muted, maxWidth = area.w)
        }
    }

    /**
     * Seven days from today in columns, one row per account: planned windows as filled pills, the weekly reset as a
     * ring, today marked. Rows that do not fit are left out.
     */
    private fun weekGrid(p: Painter, plan: Planner.Plan, order: List<WidgetAccount>, area: Area, now: Long, tile: Int) {
        val c = p.palette
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        p.roundRect(area.x, area.y, area.w, area.h, 18f, tile)
        val inner = Area(area.x + 10f, area.y + 8f, area.w - 20f, area.h - 16f)
        val nameW = min(inner.w * .26f, 76f)
        val gridX = inner.x + nameW + 6f
        val colW = (inner.x + inner.w - gridX) / 7
        // Day letters on top.
        (0 until 7).forEach { d ->
            val date = today.plusDays(d.toLong())
            val cx = gridX + d * colW + colW / 2
            if (d == 0) p.roundRect(cx - colW / 2 + 1f, inner.y, colW - 2f, inner.h, 10f, blend(tile, c.primary, .14f))
            p.text(Fmt.weekdayLetter(Texts.resources, date.dayOfWeek.value), cx, inner.y + 13f, 10.5f, if (d == 0) p.bold else p.medium, if (d == 0) c.primary else c.muted, Paint.Align.CENTER)
        }
        val rowH = 24f
        val fit = max(0, ((inner.h - 20f) / rowH).toInt())
        order.take(fit).forEachIndexed { r, a ->
            val y = inner.y + 20f + r * rowH
            val mid = y + rowH / 2
            p.brand(a.provider, inner.x, mid - 6f, 12f)
            p.text(a.title, inner.x + 16f, mid + 4f, 10.5f, p.medium, c.text, maxWidth = nameW - 16f)
            val tone = WidgetColorsKit.brand(a.provider, c)
            val budget = plan.budgets.firstOrNull { it.accountId == a.id }
            (0 until 7).forEach { d ->
                val date = today.plusDays(d.toLong())
                val cx = gridX + d * colW + colW / 2
                val count = plan.sessions.count { it.accountId == a.id && Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate() == date }
                val pillW = min(colW - 6f, 22f)
                if (count > 0) {
                    p.roundRect(cx - pillW / 2, mid - 5f, pillW, 10f, 5f, tone)
                    if (count > 1 && pillW >= 18f) p.text(count.toString(), cx, mid + 3.5f, 9f, p.bold, if (c.dark) 0xFF101114.toInt() else 0xFFFFFFFF.toInt(), Paint.Align.CENTER)
                } else p.circle(cx, mid, 2f, blend(tile, c.muted, .45f))
                val reset = budget?.weekReset
                if (reset != null && Instant.ofEpochSecond(reset).atZone(zone).toLocalDate() == date) {
                    p.outline(cx - pillW / 2 - 3f, mid - 8f, pillW + 6f, 16f, 8f, 1.5f, tone)
                }
            }
        }
    }
}
