package com.madebycli.aiusage.widget

import android.graphics.Paint
import com.madebycli.aiusage.R
import com.madebycli.aiusage.Texts
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * The statistics widget: what the selected accounts used in the last days, from the same ledger as the statistics
 * tab. Values the app did not see the end of are drawn lighter, exactly as in the app.
 */
internal object StatsWidgetDraw {
    class Data(
        val days: List<DayUsage?>,
        val dates: List<LocalDate>,
        val summary: RangeSummary,
        val today: Double,
        val streak: Int,
        val shares: List<Pair<WidgetAccount, Double>>
    )

    fun data(accounts: List<WidgetAccount>, config: WidgetConfig, now: Long, zone: ZoneId = ZoneId.systemDefault()): Data {
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val from = today.minusDays(config.span.days - 1L)
        val merged = LedgerStats.merge(accounts.map { it.ledger }, zone)
        val dates = (0 until config.span.days).map { from.plusDays(it.toLong()) }
        val summary = LedgerStats.summarize(merged, from..today, config.metric)
        val shares = accounts.map { a -> a to LedgerStats.summarize(LedgerStats.days(a.ledger, zone), from..today, config.metric).total }
            .filter { it.second > 0 }.sortedByDescending { it.second }
        return Data(dates.map { merged[it] }, dates, summary, merged[today]?.value(config.metric) ?: 0.0,
            LedgerStats.currentStreak(merged, today, config.metric), shares)
    }

    /** "2,4" for five-hour limits, "38" for weekly points. */
    fun number(value: Double, metric: StatsMetric): String =
        if (metric == StatsMetric.FIVE) Fmt.number(Texts.resources, value / 100, if (value < 1000) 1 else 0) else Fmt.number(Texts.resources, value, 0)

    fun unit(value: Double, metric: StatsMetric): String =
        if (metric == StatsMetric.FIVE) Texts.plural(R.plurals.widget_limits_unit, if (Math.round(value) == 100L) 1 else 2)
        else Texts.get(R.string.widget_week_points)

    fun draw(p: Painter, config: WidgetConfig, accounts: List<WidgetAccount>, area: Area, now: Long, tile: Int) {
        val c = p.palette
        if (accounts.isEmpty()) return
        val data = data(accounts, config, now)
        val metric = config.metric
        val total = data.summary.total
        val spanLabel = Texts.get(config.span.label)
        val wide = area.w >= 220f
        val tall = area.h >= 190f
        if (area.h < 64f) {
            // One line: the number with its unit, the days as small bars beside it when there is room.
            val size = p.fitSize(number(total, metric), area.w * .4f, p.bold, listOf(30f, 26f, 22f, 18f).filter { it * .9f <= area.h - 4f }.ifEmpty { listOf(18f) })
            val base = area.y + area.h / 2 + size * .34f
            p.text(number(total, metric), area.x, base, size, p.bold, c.primary)
            val ux = area.x + p.width(number(total, metric), size, p.bold) + 8f
            val chartW = if (area.w >= 200f) area.w * .42f else 0f
            val unitRoom = area.x + area.w - ux - (if (chartW > 0) chartW + 10f else 0f)
            p.text(unit(total, metric), ux, base - size * .42f, 12f, p.medium, c.text, maxWidth = unitRoom)
            p.text(spanLabel, ux, base, 11f, p.medium, c.muted, maxWidth = unitRoom)
            if (chartW > 0) chart(p, data, metric, Area(area.x + area.w - chartW, area.y + 4f, chartW, area.h - 8f), labels = false)
            return
        }
        if (!wide) {
            // Small: the number, its unit and, when there is room, the last days as tiny bars.
            val chartH = if (area.h >= 110f) min(40f, area.h - 70f) else 0f
            val numberSize = p.fitSize(number(total, metric), area.w - 4f, p.bold, listOf(38f, 32f, 28f, 24f, 20f))
            val block = numberSize * .9f + 18f + (if (chartH > 0) chartH + 10f else 0f)
            var y = area.y + max(0f, (area.h - block) / 2) + numberSize * .9f
            p.text(number(total, metric), area.x, y, numberSize, p.bold, c.primary)
            y += 16f
            p.text("${unit(total, metric)} · $spanLabel", area.x, y, 11f, p.medium, c.muted, maxWidth = area.w)
            if (chartH > 0) chart(p, data, metric, Area(area.x, y + 10f, area.w, chartH), labels = false)
            return
        }
        // Wide: number on the left, chart on the right; tall widgets add three facts and the split per account.
        val leftW = if (tall) area.w else min(area.w * .4f, 150f)
        val numberSize = p.fitSize(number(total, metric), leftW - 4f, p.bold, listOf(44f, 38f, 32f, 28f, 24f))
        var y = area.y
        if (data.summary.open > 0) { p.text(Texts.get(R.string.stats_at_least), area.x, y + 11f, 11f, p.medium, c.muted); y += 14f }
        val base = y + numberSize * .88f
        p.text(number(total, metric), area.x, base, numberSize, p.bold, c.primary)
        val unitText = unit(total, metric)
        if (tall) {
            val nx = area.x + p.width(number(total, metric), numberSize, p.bold) + 8f
            p.text(unitText, nx, base - 2f, 13f, p.medium, c.text, maxWidth = area.x + area.w - nx)
            p.text(spanLabel, area.x, base + 17f, 11f, p.medium, c.muted, maxWidth = area.w)
            y = base + 28f
            val facts = listOf(
                Texts.get(R.string.widget_today) to number(data.today, metric),
                Texts.get(R.string.widget_per_day) to number(total / config.span.days, metric),
                Texts.get(R.string.stats_streak) to Texts.plural(R.plurals.stats_streak_days, data.streak, data.streak))
            val factW = (area.w - 12f) / 3
            facts.forEachIndexed { i, (label, value) ->
                val fx = area.x + i * (factW + 6f)
                p.roundRect(fx, y, factW, 44f, 14f, tile)
                p.text(label, fx + 10f, y + 17f, 10f, p.medium, c.muted, maxWidth = factW - 16f)
                p.text(value, fx + 10f, y + 35f, 14f, p.bold, c.text, maxWidth = factW - 16f)
            }
            y += 54f
            val sharesH = if (data.shares.size > 1 && area.y + area.h - y >= 118f) 30f else 0f
            chart(p, data, metric, Area(area.x, y, area.w, area.y + area.h - y - sharesH - (if (sharesH > 0) 8f else 0f)), labels = true)
            if (sharesH > 0) shares(p, data, Area(area.x, area.y + area.h - sharesH, area.w, sharesH))
        } else {
            p.text(unitText, area.x, base + 16f, 12f, p.medium, c.text, maxWidth = leftW)
            p.text(spanLabel, area.x, base + 31f, 11f, p.medium, c.muted, maxWidth = leftW)
            val streak = Texts.plural(R.plurals.widget_streak_chip, data.streak, data.streak)
            if (data.streak > 1 && area.y + area.h - (base + 31f) >= 26f && p.width(streak, 10.5f, p.medium) + 18f <= leftW)
                p.chip(streak, area.x, base + 40f, 20f, 10.5f, tile, c.text)
            chart(p, data, metric, Area(area.x + leftW + 10f, area.y, area.w - leftW - 10f, area.h), labels = area.h >= 80f)
        }
    }

    /** Bars per day; today in the full colour, ends not seen lighter, the day letters under them. */
    private fun chart(p: Painter, data: Data, metric: StatsMetric, area: Area, labels: Boolean) {
        val c = p.palette
        if (area.h < 12f) return
        val labelH = if (labels && data.dates.size <= 14) 16f else 0f
        val plot = Area(area.x, area.y, area.w, area.h - labelH)
        val n = data.dates.size
        val gap = if (n <= 7) 6f else if (n <= 14) 3f else 2f
        val barW = (plot.w - gap * (n - 1)) / n
        // At least one full limit (or 25 weekly points) as the top, so a quiet week does not look full.
        val top = max(data.days.maxOfOrNull { it?.value(metric) ?: 0.0 } ?: 0.0, if (metric == StatsMetric.FIVE) 100.0 else 25.0)
        data.dates.forEachIndexed { i, date ->
            val day = data.days[i]
            val x = plot.x + i * (barW + gap)
            val value = day?.value(metric) ?: 0.0
            val open = if (metric == StatsMetric.FIVE) day?.fiveOpen ?: 0.0 else 0.0
            val radius = min(barW / 2, 6f)
            p.roundRect(x, plot.y, barW, plot.h, radius, blend(c.surface, c.track, .55f))
            val isToday = i == n - 1
            val accent = if (isToday) c.primary else blend(c.surface, c.primary, if (c.dark) .62f else .55f)
            val h = (plot.h * (value / top)).toFloat()
            if (value > 0) {
                val shown = max(h, barW.coerceAtMost(6f))
                p.roundRect(x, plot.y + plot.h - shown, barW, shown, radius, accent)
                if (open > 0) {
                    val oh = (plot.h * (open / top)).toFloat().coerceAtMost(shown)
                    p.roundRect(x, plot.y + plot.h - shown, barW, oh, radius, blend(c.surface, c.primary, .3f))
                }
            }
            if (labelH > 0) p.text(Fmt.weekdayLetter(Texts.resources, date.dayOfWeek.value), x + barW / 2, area.y + area.h - 2f, 10f,
                if (isToday) p.bold else p.medium, if (isToday) c.text else c.muted, Paint.Align.CENTER)
        }
    }

    /** The share of each account as one segmented bar with names below. */
    private fun shares(p: Painter, data: Data, area: Area) {
        val c = p.palette
        val total = data.shares.sumOf { it.second }.takeIf { it > 0 } ?: return
        var x = area.x
        val gap = 3f
        val usable = area.w - gap * (data.shares.size - 1)
        data.shares.forEach { (account, value) ->
            val w = (usable * (value / total)).toFloat().coerceAtLeast(6f)
            p.roundRect(x, area.y, min(w, area.x + area.w - x), 8f, 4f, WidgetColorsKit.brand(account.provider, c))
            x += w + gap
        }
        var lx = area.x
        data.shares.take(3).forEach { (account, value) ->
            val label = "${account.title} ${Math.round(value / total * 100)}%"
            val w = p.width(label, 10.5f, p.medium) + 14f
            if (lx + w > area.x + area.w) return
            p.circle(lx + 3.5f, area.y + 22f, 3.5f, WidgetColorsKit.brand(account.provider, c))
            p.text(label, lx + 10f, area.y + 26f, 10.5f, p.medium, c.muted)
            lx += w + 6f
        }
    }
}
