package com.madebycli.aiusage.widget

import android.graphics.Paint
import com.madebycli.aiusage.R
import com.madebycli.aiusage.Texts
import com.madebycli.aiusage.data.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A part of the widget in dp. */
internal data class Area(val x: Float, val y: Float, val w: Float, val h: Float)

/** One window of one account as the widget shows it. */
internal class Slot(val seconds: Long, val window: UsageWindow?, now: Long) {
    val live = window != null && (window.resetAt == null || window.resetAt > now)
    val expired = window?.resetAt?.let { it <= now } == true
    /** After the reset the window starts empty. */
    val used: Double? = when { live -> window!!.usedPercent; expired -> 0.0; else -> null }
    val progress get() = ((used ?: 0.0) / 100).toFloat()
    val label: String get() = Texts.get(if (seconds == FIVE_HOURS) R.string.widget_five_short else R.string.widget_week_short)
    val tiny: String get() = Texts.get(if (seconds == FIVE_HOURS) R.string.widget_five_tiny else R.string.widget_week_tiny)
    val percent get() = used?.let { "${Fmt.percent(it)}%" } ?: "–"
}

internal object UsageWidgetDraw {
    fun slots(account: WidgetAccount, config: WidgetConfig, now: Long): List<Slot> = buildList {
        if (config.windows != WidgetWindows.WEEK) add(Slot(FIVE_HOURS, account.window(FIVE_HOURS), now))
        if (config.windows != WidgetWindows.FIVE) add(Slot(ONE_WEEK, account.window(ONE_WEEK), now))
    }

    fun color(p: WidgetPalette, config: WidgetConfig, slot: Slot): Int {
        val used = slot.used ?: return p.muted
        if (!slot.live) return p.muted
        return when {
            used >= 100 -> p.error
            config.colors == WidgetColors.TRAFFIC -> when { used >= 90 -> p.error; used >= 70 -> p.warn; else -> p.good }
            slot.seconds == FIVE_HOURS -> p.primary
            else -> p.tertiary
        }
    }

    /** Reset options from longest to shortest, for [Painter.fit]. */
    fun resetOptions(slot: Slot, now: Long, format: ResetFormat): List<String> {
        val res = Texts.resources
        val at = slot.window?.resetAt
        return when {
            slot.window == null -> listOf(Texts.get(R.string.widget_not_reported), "–")
            slot.expired -> listOf(Texts.get(R.string.widget_reset_reached), Texts.get(R.string.widget_reset_done))
            at == null -> listOf(Texts.get(R.string.widget_reset_unknown), "–")
            else -> {
                val clock = Fmt.clock(res, at, now)
                val left = Fmt.remaining(res, at - now)
                val short = Fmt.duration(res, at - now)
                val tight = compactClock(slot, now)
                when (format) {
                    ResetFormat.CLOCK -> (listOf(clock) + tight).distinct()
                    ResetFormat.RELATIVE -> listOf(left, short)
                    ResetFormat.BOTH -> (listOf("$clock · $left", "${tight.last()} · $short", clock) + tight).distinct()
                }
            }
        }
    }

    /** The reset time for tight places: with the day as a word or a short weekday, the time alone only today. */
    fun compactClock(slot: Slot, now: Long): List<String> {
        val at = slot.window?.resetAt ?: return emptyList()
        if (!slot.live) return listOf(Texts.get(R.string.widget_reset_done))
        val res = Texts.resources
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.Instant.ofEpochSecond(at).atZone(zone)
        val sameDay = day.toLocalDate() == java.time.Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val time = Fmt.clockOnly(res, at)
        return if (sameDay) listOf(time) else listOf(Fmt.clock(res, at, now), "${Fmt.weekdayShort(res, day.dayOfWeek.value)} $time")
    }

    // ---- sizes -----------------------------------------------------------------------------------------

    /** Smallest tile a style can draw in, in dp. */
    fun minTile(style: WidgetStyle, rows: Int): Pair<Float, Float> = when (style) {
        WidgetStyle.LIQUID -> (if (rows > 1) 136f else 92f) to 84f
        WidgetStyle.RINGS -> (if (rows > 1) 132f else 90f) to 84f
        WidgetStyle.BARS -> 150f to 24f + rows * 30f
        WidgetStyle.PERCENT -> (if (rows > 1) 120f else 80f) to 76f
        WidgetStyle.COMPACT -> (if (rows > 1) 190f else 150f) to 40f
    }

    /** The size at which a tile looks right; larger tiles draw everything bigger, up to [MAX_ENLARGE]. */
    fun naturalTile(style: WidgetStyle, rows: Int): Pair<Float, Float> = when (style) {
        WidgetStyle.LIQUID -> (if (rows > 1) 210f else 124f) to 156f
        WidgetStyle.RINGS -> (if (rows > 1) 200f else 116f) to 150f
        WidgetStyle.BARS -> 210f to 26f + rows * 46f
        WidgetStyle.PERCENT -> (if (rows > 1) 176f else 100f) to 112f
        WidgetStyle.COMPACT -> (if (rows > 1) 270f else 220f) to 50f
    }

    private const val MAX_ENLARGE = 2.0f

    fun columnCount(config: WidgetConfig, width: Float, minimum: Float, accounts: Int): Int {
        val fits = floor((width + 8f) / (minimum + 8f)).toInt().coerceIn(1, accounts.coerceAtLeast(1))
        return when (config.arrangement) { WidgetArrangement.AUTO -> fits; WidgetArrangement.STACK -> 1; WidgetArrangement.GRID -> fits.coerceAtMost(2) }
    }

    /** The style actually drawn: one that does not fit even a single tile in this height becomes the one-line layout. */
    fun effectiveStyle(config: WidgetConfig, contentH: Float): WidgetStyle {
        val rows = if (config.windows == WidgetWindows.BOTH) 2 else 1
        return if (contentH < minTile(config.style, rows).second - 1) WidgetStyle.COMPACT else config.style
    }

    /** How many accounts fit; the rest is announced in the header. */
    fun capacity(config: WidgetConfig, area: Area, accounts: Int): Int {
        val rows = if (config.windows == WidgetWindows.BOTH) 2 else 1
        val style = effectiveStyle(config, area.h)
        val (minW, minH) = minTile(style, rows)
        val columns = columnCount(config, area.w, minW, accounts)
        return columns * max(1, floor((area.h + GAP) / (minH + GAP)).toInt())
    }

    private const val GAP = 6f

    fun draw(p: Painter, config: WidgetConfig, accounts: List<WidgetAccount>, area: Area, now: Long, surface: Int, tile: Int, hidden: Int? = null) {
        val rows = if (config.windows == WidgetWindows.BOTH) 2 else 1
        val style = effectiveStyle(config, area.h)
        val (minW, minH) = minTile(style, rows)
        val columns = columnCount(config, area.w, minW, accounts.size)
        val fittingRows = max(1, floor((area.h + GAP) / (minH + GAP)).toInt())
        val shown = accounts.take(columns * fittingRows)
        val usedRows = ceil(shown.size / columns.toFloat()).toInt().coerceAtLeast(1)
        val tileW = (area.w - GAP * (columns - 1)) / columns
        val tileH = (area.h - GAP * (usedRows - 1)) / usedRows
        val (naturalW, naturalH) = naturalTile(style, rows)
        val k = min(tileW / naturalW, tileH / naturalH).coerceIn(1f, MAX_ENLARGE)
        val framed = shown.size > 1
        val radius = max(12f, config.corner * .64f)
        shown.forEachIndexed { index, account ->
            val col = index % columns
            val row = index / columns
            val x = area.x + col * (tileW + GAP)
            val y = area.y + row * (tileH + GAP)
            // Joined tiles like the app's settings groups: large outer corners, small inner ones.
            if (framed) p.roundRect(x, y, tileW, tileH, if (columns == 1 && usedRows > 1) radius * .55f else radius * .8f, tile)
            val inner = if (framed) 12f else 4f
            val d = p.scaled(k)
            val contentH = min(tileH / k, naturalH)
            val cx = x / k
            val cy = (y + (tileH - contentH * k) / 2) / k
            val cw = tileW / k
            val box = Area(cx + inner, cy + if (framed) 8f else 2f, cw - inner * 2, contentH - if (framed) 16f else 4f)
            val back = if (framed) tile else surface
            val slots = slots(account, config, now)
            val more = if (index == 0) hidden else null
            when (style) {
                WidgetStyle.LIQUID -> shapes(d, account, slots, box, now, back, liquid = true, more = more)
                WidgetStyle.RINGS -> shapes(d, account, slots, box, now, back, liquid = false, more = more)
                WidgetStyle.BARS -> bars(d, account, slots, box, now, more)
                WidgetStyle.PERCENT -> numbers(d, account, slots, box, now, more)
                WidgetStyle.COMPACT -> compact(d, account, slots, Area(cx + inner, cy, cw - inner * 2, contentH), now, more)
            }
        }
    }

    /** Brand, name and plan (or the count of accounts left out) in one line; returns the height used. */
    private fun title(p: Painter, a: WidgetAccount, box: Area, more: Int? = null, size: Float = 13f): Float {
        val c = p.palette
        p.brand(a.provider, box.x, box.y + 1f, 15f)
        if (more != null) {
            val chipW = p.chip("+$more", box.x + box.w, box.y, 18f, 10.5f, c.primaryContainer, c.onPrimaryContainer, Paint.Align.RIGHT)
            p.text(a.title, box.x + 21f, box.y + 13f, size, p.bold, c.text, maxWidth = box.w - 21f - chipW - 8f)
            return 20f
        }
        val plan = if (p.config.showPlan && box.w >= 140f) a.plan else null
        val planW = plan?.let { min(p.width(it, 11f, p.medium), box.w * .36f) } ?: 0f
        p.text(a.title, box.x + 21f, box.y + 13f, size, p.bold, c.text, maxWidth = box.w - 21f - (if (plan != null) planW + 8f else 0f))
        if (plan != null) p.text(plan, box.x + box.w, box.y + 13f, 11f, p.medium, c.muted, Paint.Align.RIGHT, planW)
        return 20f
    }

    /** Liquid shapes or rings, one per window, side by side or stacked, whichever gives the larger picture. */
    private fun shapes(p: Painter, a: WidgetAccount, slots: List<Slot>, box: Area, now: Long, back: Int, liquid: Boolean, more: Int?) {
        val c = p.palette
        val headH = 24f
        val avail = Area(box.x, box.y + headH, box.w, box.h - headH)
        val withReset = avail.h >= 72f
        val resetH = if (withReset) 16f else 0f
        val n = slots.size
        val sideD = min(avail.w / n - 10f, avail.h - resetH)
        val stackD = min(avail.w - 10f, avail.h / n - resetH - 4f)
        val stacked = n > 1 && stackD > sideD + 8f
        val d = (if (stacked) stackD else sideD).coerceAtLeast(30f)
        // Title and shapes as one block in the middle of the tile, so a tall tile has no gap under the name.
        val cellH = d + resetH
        val blockH = headH + if (stacked) n * cellH + (n - 1) * 4f else cellH
        val shift = max(0f, (box.h - blockH) / 2)
        title(p, a, Area(box.x, box.y + shift, box.w, box.h), more)
        val body = Area(box.x, box.y + shift + headH, box.w, blockH - headH)
        slots.forEachIndexed { i, slot ->
            val colW = if (stacked) body.w else body.w / n
            val centerX = body.x + (if (stacked) 0f else i * colW) + colW / 2
            val top = body.y + if (stacked) i * (cellH + 4f) else 0f
            val accent = color(c, p.config, slot)
            if (liquid) {
                val fill = blend(back, accent, if (c.dark) .62f else .5f)
                val track = blend(back, accent, if (c.dark) .16f else .14f)
                p.liquid(WidgetShapes.window(slot.seconds), centerX - d / 2, top, d, slot.progress, fill, track, rotation = if (slot.seconds == FIVE_HOURS) -8f else 10f)
            } else {
                val stroke = (d * .1f).coerceIn(5f, 9f)
                p.ring(centerX, top + d / 2, d / 2 - stroke / 2, stroke, slot.progress, accent)
            }
            // The number in the middle, the window name under it while there is room.
            val inner = if (liquid) d * .78f else d - (d * .1f).coerceIn(5f, 9f) * 2 - 14f
            val size = p.fitSize(slot.percent, inner, p.bold, listOf(30f, 26f, 22f, 19f, 16f, 14f, 12f, 10f).filter { it <= max(10f, d * .3f) }.ifEmpty { listOf(10f) })
            // Under the number: the window name, or the reset time when there is no line for it below the shape.
            val second = when {
                d < 54f -> null
                withReset -> slot.label
                else -> compactClock(slot, now).firstOrNull { p.fits(listOf(it), inner, 10f, p.medium) }
                    ?: compactClock(slot, now).lastOrNull()?.takeIf { p.fits(listOf(it), inner, 8.5f, p.medium) }
            }
            val secondSize = if (second != null && !p.fits(listOf(second), inner, 10f, p.medium)) 8.5f else 10f
            val numberY = top + d / 2 + size * .36f * p.textFactor - (if (second != null) 5f * p.textFactor else 0f)
            p.text(slot.percent, centerX, numberY, size, p.bold, if (slot.live) c.text else c.muted, Paint.Align.CENTER)
            if (second != null) p.text(second, centerX, numberY + 13f * p.textFactor, secondSize, p.medium, if (liquid) c.text else c.muted, Paint.Align.CENTER, inner)
            if (withReset) {
                // The longest form that fits, at a smaller size before anything is cut off.
                val options = resetOptions(slot, now, p.config.reset)
                val size = if (p.fits(options, colW - 4f, 11f, p.medium)) 11f else 9.5f
                p.text(p.fit(options, colW - 4f, size, p.medium), centerX, top + d + 13f, size, p.medium, c.muted, Paint.Align.CENTER, colW - 4f)
            }
        }
    }

    /** Label and number above a thick bar, the reset under it. */
    private fun bars(p: Painter, a: WidgetAccount, slots: List<Slot>, box: Area, now: Long, more: Int?) {
        val c = p.palette
        val head = title(p, a, box, more)
        val body = Area(box.x, box.y + head + 2f, box.w, box.h - head - 2f)
        val per = body.h / slots.size
        slots.forEachIndexed { i, slot ->
            val y = body.y + i * per
            val accent = color(c, p.config, slot)
            val full = per >= 40f
            val number = if (full) 16f else 14f
            p.text(slot.label, body.x, y + 13f, 11.5f, p.medium, c.muted)
            p.text(slot.percent, body.x + body.w, y + 14f, number, p.bold, if (slot.live) c.text else c.muted, Paint.Align.RIGHT)
            if (!full) {
                // Short rows keep the reset on the label line, between name and number.
                val from = body.x + p.width(slot.label, 11.5f, p.medium) + 8f
                val room = body.x + body.w - p.width(slot.percent, number, p.bold) - 8f - from
                if (room > 30f) p.text(p.fit(resetOptions(slot, now, p.config.reset), room, 10.5f, p.regular), from, y + 13f, 10.5f, p.regular, c.muted, maxWidth = room)
            }
            p.bar(body.x, y + 19f, body.w, 8f, slot.progress, accent)
            if (full) {
                val options = resetOptions(slot, now, p.config.reset)
                if (p.config.reset == ResetFormat.BOTH && slot.live && slot.window?.resetAt != null) {
                    val at = slot.window.resetAt
                    val left = Fmt.remaining(Texts.resources, at - now)
                    val clock = Fmt.clock(Texts.resources, at, now)
                    val leftW = p.width(left, 11f, p.medium)
                    p.text(clock, body.x, y + 41f, 11f, p.medium, c.muted, maxWidth = body.w - leftW - 8f)
                    p.text(left, body.x + body.w, y + 41f, 11f, p.medium, c.muted, Paint.Align.RIGHT)
                } else p.line(options, body.x, y + 41f, body.w, 11f, p.medium, c.muted)
            }
        }
    }

    /** Large numbers in the window colour. */
    private fun numbers(p: Painter, a: WidgetAccount, slots: List<Slot>, box: Area, now: Long, more: Int?) {
        val c = p.palette
        val head = title(p, a, box, more)
        val body = Area(box.x, box.y + head + 2f, box.w, box.h - head - 2f)
        val column = body.w / slots.size
        val withReset = body.h >= 60f
        val sizes = listOf(34f, 30f, 26f, 22f, 19f, 16f)
        val big = slots.minOf { p.fitSize(it.percent, column - 8f, p.bold, sizes) }
        val block = big * .9f + 16f + (if (withReset) 14f else 0f)
        val top = body.y + max(0f, (body.h - block) / 2)
        slots.forEachIndexed { i, slot ->
            val cx = body.x + column * i + column / 2
            val base = top + big * .9f
            p.text(slot.percent, cx, base, big, p.bold, color(c, p.config, slot), Paint.Align.CENTER)
            p.text(slot.label, cx, base + 16f, 11f, p.medium, c.text, Paint.Align.CENTER, column - 4f)
            if (withReset) p.line(resetOptions(slot, now, p.config.reset), cx, base + 30f, column - 4f, 10.5f, p.regular, c.muted, Paint.Align.CENTER)
        }
    }

    /** One line: brand, name and next reset, then a small number and bar per window. */
    private fun compact(p: Painter, a: WidgetAccount, slots: List<Slot>, box: Area, now: Long, more: Int?) {
        val c = p.palette
        val mid = box.y + box.h / 2
        val narrow = box.w < 200f
        val chunk = if (box.w > 300f) 86f else if (narrow) 50f else 62f
        val textW = box.w - 24f - chunk * slots.size - 4f
        if (textW < 40f) {
            // Too narrow for a name next to the numbers: name above, numbers below.
            p.brand(a.provider, box.x, mid - 17f, 13f)
            val chipW = more?.let { p.chip("+$it", box.x + box.w, mid - 20f, 16f, 9.5f, c.primaryContainer, c.onPrimaryContainer, Paint.Align.RIGHT) + 4f } ?: 0f
            p.text(a.title, box.x + 18f, mid - 6f, 12f, p.bold, c.text, maxWidth = box.w - 18f - chipW)
            val col = box.w / slots.size
            slots.forEachIndexed { i, slot ->
                val x = box.x + col * i
                val w = col - if (i < slots.lastIndex) 8f else 0f
                // The label only where it fits with a gap before the number.
                if (p.width(slot.tiny, 9.5f, p.medium) + p.width(slot.percent, 12f, p.bold) + 4f <= w) p.text(slot.tiny, x, mid + 9f, 9.5f, p.medium, c.muted)
                p.text(slot.percent, x + w, mid + 9.5f, 12f, p.bold, if (slot.live) c.text else c.muted, Paint.Align.RIGHT)
                p.bar(x, mid + 13f, w, 4f, slot.progress, color(c, p.config, slot))
            }
            return
        }
        p.brand(a.provider, box.x, mid - 8f, 16f)
        val twoLines = box.h >= 36f
        val chipW = more?.let { p.width("+$it", 9.5f, p.medium) + 18f } ?: 0f
        p.text(a.title, box.x + 24f, if (twoLines) mid - 1f else mid + 4.5f, 13f, p.bold, c.text, maxWidth = textW - chipW)
        if (more != null) p.chip("+$more", box.x + 24f + min(p.width(a.title, 13f, p.bold), textW - chipW) + 6f, (if (twoLines) mid - 1f else mid + 4.5f) - 12f, 16f, 9.5f,
            c.primaryContainer, c.onPrimaryContainer)
        if (twoLines) {
            val next = slots.filter { it.live && it.window?.resetAt != null }.minByOrNull { it.window!!.resetAt!! }
            val line = if (next != null) resetOptions(next, now, p.config.reset).map { "${next.tiny} $it" } else listOf(Texts.get(R.string.usage_no_reading))
            p.text(p.fit(line, textW, 10.5f, p.regular), box.x + 24f, mid + 12f, 10.5f, p.regular, c.muted, maxWidth = textW)
        }
        slots.forEachIndexed { i, slot ->
            val x = box.x + box.w - chunk * (slots.size - i) + 6f
            val w = chunk - 6f
            val number = p.width(slot.percent, 13f, p.bold) + 5f
            val label = listOf(slot.label, slot.tiny).drop(if (narrow) 1 else 0).firstOrNull { p.width(it, 10f, p.medium) + number <= w }
            if (label != null) p.text(label, x, mid - 3f, 10f, p.medium, c.muted)
            p.text(slot.percent, x + w, mid - 2.5f, 13f, p.bold, if (slot.live) c.text else c.muted, Paint.Align.RIGHT)
            p.bar(x, mid + 3f, w, 5f, slot.progress, color(c, p.config, slot))
        }
    }
}
