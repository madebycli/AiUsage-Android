package com.madebycli.aiusage.widget

import android.graphics.Paint
import com.madebycli.aiusage.R
import com.madebycli.aiusage.Texts
import com.madebycli.aiusage.data.*
import kotlin.math.max
import kotlin.math.min

/**
 * The resets widget: the coming resets of the selected accounts, soonest first. The next one is large; each row shows
 * how much of its window has passed, so a glance tells how close it is.
 */
internal object ResetsWidgetDraw {
    class Entry(val account: WidgetAccount, val window: UsageWindow) {
        val at get() = window.resetAt!!
    }

    fun entries(accounts: List<WidgetAccount>, config: WidgetConfig, now: Long): List<Entry> = accounts.flatMap { a ->
        a.windows.filter { w ->
            w.resetAt != null && w.resetAt > now && when (config.windows) {
                WidgetWindows.FIVE -> w.seconds == FIVE_HOURS; WidgetWindows.WEEK -> w.seconds == ONE_WEEK; WidgetWindows.BOTH -> true
            }
        }.map { Entry(a, it) }
    }.sortedBy { it.at }

    /** Saved resets across the selected accounts, for the header chip. */
    fun banked(accounts: List<WidgetAccount>): Int = accounts.sumOf { it.banked ?: 0 }

    private fun windowName(seconds: Long) = Texts.get(if (seconds == FIVE_HOURS) R.string.widget_five_short else R.string.widget_week_short)

    private fun timeOptions(e: Entry, now: Long, format: ResetFormat): Pair<List<String>, List<String>> {
        val res = Texts.resources
        val clock = Fmt.clock(res, e.at, now)
        val left = Fmt.remaining(res, e.at - now)
        val short = Fmt.duration(res, e.at - now)
        return when (format) {
            ResetFormat.CLOCK -> listOf(clock, Fmt.clockOnly(res, e.at)) to emptyList()
            ResetFormat.RELATIVE -> listOf(short) to emptyList()
            ResetFormat.BOTH -> listOf(clock, Fmt.clockOnly(res, e.at)) to listOf(left, short)
        }
    }

    fun draw(p: Painter, config: WidgetConfig, accounts: List<WidgetAccount>, area: Area, now: Long, tile: Int) {
        val c = p.palette
        val list = entries(accounts, config, now)
        if (list.isEmpty()) { WidgetChrome.empty(p, Texts.get(R.string.widget_no_resets), area.x, area.y, area.w, area.h); return }
        val first = list.first()
        val res = Texts.resources
        // The next reset: the time left large, account and window below.
        val heroH = if (area.h >= 120f && list.size > 1) (if (area.h < 150f) 72f else 78f) else area.h
        val big = Fmt.duration(res, first.at - now)
        val size = p.fitSize(big, area.w - 4f, p.bold, listOf(34f, 30f, 26f, 22f, 18f, 15f).filter { it * .9f + 34f <= heroH || it <= 15f })
        val block = size * .9f + 34f
        val top = area.y + if (heroH == area.h) max(0f, (area.h - block) / 2) else 0f
        p.text(Texts.get(R.string.widget_next_reset), area.x, top + 11f, 11f, p.bold, c.primary, maxWidth = area.w)
        p.text(big, area.x, top + 14f + size * .9f, size, p.bold, c.text)
        val lineY = top + 14f + size * .9f + 18f
        p.brand(first.account.provider, area.x, lineY - 11f, 13f)
        val detail = "${first.account.title} · ${windowName(first.window.seconds)} · ${Fmt.clock(res, first.at, now)}"
        val shortDetail = "${first.account.title} · ${windowName(first.window.seconds)}"
        p.text(p.fit(listOf(detail, shortDetail), area.w - 18f, 11f, p.medium), area.x + 18f, lineY, 11f, p.medium, c.muted, maxWidth = area.w - 18f)
        if (heroH == area.h) return
        // The rest as rows: brand, name and window, time on the right, elapsed share of the window as a thin bar.
        val rowsTop = area.y + heroH + 8f
        val rowH = 40f
        val fit = max(0, ((area.y + area.h - rowsTop + 4f) / (rowH + 4f)).toInt())
        list.drop(1).take(fit).forEachIndexed { i, e ->
            val y = rowsTop + i * (rowH + 4f)
            p.roundRect(area.x, y, area.w, rowH, if (i == 0) 16f else 8f, tile)
            val inner = area.w - 24f
            val (times, lefts) = timeOptions(e, now, config.reset)
            val time = p.fit(times, inner * .45f, 13f, p.bold)
            val timeW = p.width(time, 13f, p.bold)
            p.brand(e.account.provider, area.x + 12f, y + 8f, 13f)
            p.text(e.account.title, area.x + 31f, y + 18f, 12.5f, p.bold, c.text, maxWidth = inner - 19f - timeW - 10f)
            p.text(time, area.x + area.w - 12f, y + 18f, 13f, p.bold, c.text, Paint.Align.RIGHT)
            val sub = if (lefts.isNotEmpty()) p.fit(lefts, inner * .5f, 10.5f, p.medium) else null
            val subW = sub?.let { p.width(it, 10.5f, p.medium) } ?: 0f
            val label = windowName(e.window.seconds)
            val labelW = p.width(label, 10.5f, p.medium)
            p.text(label, area.x + 12f, y + 33f, 10.5f, p.medium, c.muted)
            if (sub != null) p.text(sub, area.x + area.w - 12f, y + 33f, 10.5f, p.medium, c.muted, Paint.Align.RIGHT)
            // Elapsed part of the window between the label and the time left.
            val barX = area.x + 12f + labelW + 8f
            val barW = area.x + area.w - 12f - (if (sub != null) subW + 8f else 0f) - barX
            if (barW > 24f) p.bar(barX, y + 29f, barW, 4f, e.window.elapsed(now) ?: 0f,
                if (e.window.seconds == FIVE_HOURS) c.primary else c.tertiary, blend(tile, c.track, .9f))
        }
        val hidden = list.size - 1 - min(fit, list.size - 1)
        if (hidden > 0 && fit > 0) {
            val y = rowsTop + fit * (rowH + 4f)
            if (y + 14f <= area.y + area.h + 4f) p.text(Texts.get(R.string.widget_more, hidden), area.x + area.w - 4f, y + 10f, 10.5f, p.medium, c.muted, Paint.Align.RIGHT)
        }
    }
}
