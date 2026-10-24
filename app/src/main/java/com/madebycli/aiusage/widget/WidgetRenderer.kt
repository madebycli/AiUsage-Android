package com.madebycli.aiusage.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.R
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Colours of one widget, taken from the app palette (or Android's own colours on request). */
class WidgetPalette(
    val surface: Int, val tile: Int, val text: Int, val muted: Int, val track: Int,
    val primary: Int, val secondary: Int, val error: Int, val good: Int, val warn: Int, val dark: Boolean
)

object WidgetRenderer {
    /** Data older than this is marked as outdated. */
    const val STALE_SECONDS = 6 * 3600L

    fun palette(context: Context, config: WidgetConfig): WidgetPalette {
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (config.theme) { WidgetTheme.SYSTEM -> systemDark; WidgetTheme.LIGHT -> false; else -> true }
        val black = config.theme == WidgetTheme.BLACK
        val base = if (dark) WidgetPalette(
            surface = if (black) 0xFF000000.toInt() else 0xFF14161C.toInt(), tile = if (black) 0xFF14161C.toInt() else 0xFF1F222B.toInt(),
            text = 0xFFE4E5EE.toInt(), muted = 0xFFAFB1C0.toInt(), track = if (black) 0xFF2A2D37.toInt() else 0xFF30333E.toInt(),
            primary = 0xFFB8C5ED.toInt(), secondary = 0xFFD7BFEA.toInt(), error = 0xFFF2B8B5.toInt(),
            good = 0xFF8FD3A8.toInt(), warn = 0xFFF0C46A.toInt(), dark = true)
        else WidgetPalette(
            surface = 0xFFF0F2F8.toInt(), tile = 0xFFE3E7F1.toInt(), text = 0xFF1D2029.toInt(), muted = 0xFF5D6171.toInt(), track = 0xFFD2D7E4.toInt(),
            primary = 0xFF4A5D8A.toInt(), secondary = 0xFF74588C.toInt(), error = 0xFFB3261E.toInt(),
            good = 0xFF2E7D55.toInt(), warn = 0xFFB87A10.toInt(), dark = false)
        if (!config.dynamic || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return base
        fun color(id: Int) = runCatching { ContextCompat.getColor(context, id) }.getOrNull()
        return if (dark) WidgetPalette(
            color(android.R.color.system_neutral1_900)?.takeUnless { black } ?: base.surface, color(android.R.color.system_neutral1_800)?.takeUnless { black } ?: base.tile,
            color(android.R.color.system_neutral1_50) ?: base.text, color(android.R.color.system_neutral2_200) ?: base.muted,
            color(android.R.color.system_neutral1_700) ?: base.track, color(android.R.color.system_accent1_200) ?: base.primary,
            color(android.R.color.system_accent3_200) ?: base.secondary, base.error, base.good, base.warn, true)
        else WidgetPalette(
            color(android.R.color.system_neutral1_50) ?: base.surface, color(android.R.color.system_neutral1_100) ?: base.tile,
            color(android.R.color.system_neutral1_900) ?: base.text, color(android.R.color.system_neutral2_700) ?: base.muted,
            color(android.R.color.system_neutral1_200) ?: base.track, color(android.R.color.system_accent1_600) ?: base.primary,
            color(android.R.color.system_accent3_600) ?: base.secondary, base.error, base.good, base.warn, false)
    }

    private class Row(val label: String, val seconds: Long, val window: UsageWindow?)

    private enum class State { UNKNOWN, EXPIRED, LIVE }

    private fun Row.state(now: Long) = when {
        window == null -> State.UNKNOWN
        window.resetAt != null && window.resetAt <= now -> State.EXPIRED
        else -> State.LIVE
    }

    private fun rowsFor(account: WidgetAccount, config: WidgetConfig): List<Row> = buildList {
        if (config.windows != WidgetWindows.WEEK) add(Row("5 Std.", FIVE_HOURS, account.window(FIVE_HOURS)))
        if (config.windows != WidgetWindows.FIVE) add(Row("Woche", ONE_WEEK, account.window(ONE_WEEK)))
    }

    /** Draws everything with density-independent numbers; [px] converts to pixels. */
    private class Painter(val context: Context, val canvas: Canvas, val density: Float, val fontScale: Float, val palette: WidgetPalette, val config: WidgetConfig) {
        fun px(dp: Float) = dp * density
        val regular: Typeface = font(com.axiel7.anihyou.core.ui.R.font.usage_rounded_regular)
        val medium: Typeface = font(com.axiel7.anihyou.core.ui.R.font.usage_rounded_medium)
        val bold: Typeface = font(com.axiel7.anihyou.core.ui.R.font.usage_rounded_bold)
        private fun font(id: Int): Typeface = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val scale = config.textScale / 100f * min(fontScale, 1.3f)

        fun sp(size: Float) = size * scale * density

        fun measure(text: String, size: Float, face: Typeface): Float {
            textPaint.typeface = face; textPaint.textSize = sp(size)
            return textPaint.measureText(text)
        }

        /** Text with its baseline at [y]; [maxWidth] (dp) cuts it with an ellipsis. */
        fun text(text: String, x: Float, y: Float, size: Float, face: Typeface, color: Int, align: Paint.Align = Paint.Align.LEFT, maxWidth: Float? = null) {
            textPaint.typeface = face; textPaint.textSize = sp(size); textPaint.color = color; textPaint.textAlign = align
            val shown = if (maxWidth == null) text else android.text.TextUtils.ellipsize(text, textPaint, px(maxWidth), android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, px(x), px(y), textPaint)
        }

        /** The largest of [sizes] at which [text] still fits in [maxWidth] dp; the smallest otherwise. */
        fun fitSize(text: String, maxWidth: Float, face: Typeface, sizes: List<Float>): Float =
            sizes.firstOrNull { measure(text, it, face) <= px(maxWidth) } ?: sizes.last()

        /** The first of [options] that fits in [maxWidth] dp, or the shortest cut off. */
        fun fit(options: List<String>, maxWidth: Float, size: Float, face: Typeface): String =
            options.firstOrNull { measure(it, size, face) <= px(maxWidth) } ?: options.last()

        fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
            paint.style = Paint.Style.FILL; paint.color = color
            canvas.drawRoundRect(RectF(px(x), px(y), px(x + w), px(y + h)), px(radius), px(radius), paint)
        }

        fun bar(x: Float, y: Float, w: Float, h: Float, progress: Float, color: Int) {
            roundRect(x, y, w, h, h / 2, palette.track)
            val filled = (w * progress.coerceIn(0f, 1f)).let { if (progress > 0f) max(it, h) else 0f }
            if (filled > 0f) roundRect(x, y, min(filled, w), h, h / 2, color)
        }

        fun ring(cx: Float, cy: Float, radius: Float, stroke: Float, progress: Float, color: Int) {
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = px(stroke)
            val rect = RectF(px(cx - radius), px(cy - radius), px(cx + radius), px(cy + radius))
            paint.color = palette.track
            canvas.drawArc(rect, 135f, 270f, false, paint)
            if (progress > 0f) { paint.color = color; canvas.drawArc(rect, 135f, 270f * progress.coerceIn(0f, 1f), false, paint) }
            paint.style = Paint.Style.FILL
        }

        fun icon(resource: Int, x: Float, y: Float, size: Float, color: Int) {
            val drawable = ContextCompat.getDrawable(context, resource)?.mutate() ?: return
            DrawableCompat.setTint(drawable, color)
            drawable.setBounds(px(x).toInt(), px(y).toInt(), px(x + size).toInt(), px(y + size).toInt())
            drawable.draw(canvas)
        }

        fun brand(provider: String, x: Float, y: Float, size: Float) = icon(
            if (provider == Providers.CODEX) R.drawable.ic_brand_codex else R.drawable.ic_brand_claude, x, y, size,
            if (provider == Providers.CODEX) palette.text else 0xFFD58C70.toInt())
    }

    private fun usageColor(p: WidgetPalette, config: WidgetConfig, seconds: Long, percent: Double): Int = when {
        percent >= 100 -> p.error
        config.colors == WidgetColors.TRAFFIC -> when { percent >= 90 -> p.error; percent >= 70 -> p.warn; else -> p.good }
        seconds == FIVE_HOURS -> p.primary
        else -> p.secondary
    }

    private fun resetOptions(row: Row, now: Long, config: WidgetConfig, prefix: String = ""): List<String> {
        val window = row.window
        return when (row.state(now)) {
            State.UNKNOWN -> listOf("nicht gemeldet")
            State.EXPIRED -> listOf("Reset erreicht", "zurückgesetzt")
            State.LIVE -> {
                val at = window?.resetAt ?: return listOf("Reset unbekannt", "–")
                val clock = TimeText.clock(at, now)
                val left = TimeText.remaining(at - now)
                when (config.reset) {
                    ResetFormat.CLOCK -> listOf("$prefix$clock", clock)
                    ResetFormat.RELATIVE -> listOf("$prefix$left", left)
                    ResetFormat.BOTH -> listOf("$prefix$clock · $left", "$prefix$clock", clock)
                }
            }
        }
    }

    private fun percentText(row: Row, now: Long) = when (row.state(now)) {
        State.LIVE -> "${formatPercent(row.window!!.usedPercent)}%"
        else -> "–"
    }

    private fun progress(row: Row, now: Long) = if (row.state(now) == State.LIVE) (row.window!!.usedPercent / 100).toFloat() else 0f

    private fun accent(p: WidgetPalette, config: WidgetConfig, row: Row, now: Long) =
        if (row.state(now) == State.LIVE) usageColor(p, config, row.seconds, row.window!!.usedPercent) else p.muted

    /** Smallest tile a style needs, in dp: (width, height). Below this height a tile falls back to the one-line layout. */
    private fun minTile(config: WidgetConfig, rows: Int): Pair<Float, Float> = when (config.style) {
        WidgetStyle.BARS -> (if (rows > 1) 150f else 130f) to (if (rows > 1) 74f else 54f)
        WidgetStyle.COMPACT -> (if (rows > 1) 210f else 160f) to 34f
        WidgetStyle.PERCENT -> (if (rows > 1) 104f else 72f) to 62f
        WidgetStyle.RINGS -> (if (rows > 1) 104f else 72f) to 80f
    }

    private fun maxTileHeight(config: WidgetConfig, rows: Int) = when (config.style) {
        WidgetStyle.BARS -> 22f + rows * 38f + 18f
        WidgetStyle.COMPACT -> 44f
        WidgetStyle.PERCENT -> 96f
        WidgetStyle.RINGS -> 118f
    }

    fun render(context: Context, config: WidgetConfig, all: List<WidgetAccount>, widthDp: Int, heightDp: Int, now: Long = Instant.now().epochSecond,
        refreshing: Boolean = false, density: Float = context.resources.displayMetrics.density): Bitmap {
        val width = max(40, widthDp).toFloat()
        val height = max(40, heightDp).toFloat()
        val bitmap = Bitmap.createBitmap((width * density).toInt().coerceAtLeast(1), (height * density).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = palette(context, config)
        val painter = Painter(context, canvas, density, context.resources.configuration.fontScale, palette, config)
        val alpha = config.opacity / 100f
        fun withAlpha(color: Int) = Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        painter.roundRect(0f, 0f, width, height, config.corner.toFloat(), withAlpha(palette.surface))

        val accounts = WidgetData.select(all, config)
        val pad = if (height < 90f || width < 120f) 8f else 12f
        val gap = 8f
        val hasHeader = (config.showRefresh || config.showUpdated) && height >= 100f
        val headerHeight = if (hasHeader) 18f else 0f
        val rowCount = if (config.windows == WidgetWindows.BOTH) 2 else 1
        val left = pad
        val top = pad + headerHeight
        val contentW = width - pad * 2
        // A style that cannot fit even one tile in this height shows the one-line layout for the whole widget.
        val style = if (height - pad - top < minTile(config, rowCount).second - 1f) WidgetStyle.COMPACT else config.style
        val (minW, minH) = minTile(config.copy(style = style), rowCount)
        val wouldOverflow = accounts.size > floor((contentW + gap) / (minW + gap)).toInt().coerceIn(1, max(1, accounts.size)) *
            max(1, floor((height - pad - top + gap) / (minH + gap)).toInt())
        // The "+N" marker lives in the header; widgets without one reserve a thin strip when there is room.
        val stripHeight = if (wouldOverflow && !hasHeader && height >= 70f) 16f else 0f
        val contentH = height - pad - top - stripHeight
        if (accounts.isEmpty()) {
            painter.text(if (all.isEmpty()) "Noch kein Account verbunden" else "Kein Account gewählt", width / 2, height / 2 + 4, 12f, painter.medium, palette.muted, Paint.Align.CENTER, contentW)
            return bitmap
        }
        val columns = floor((contentW + gap) / (minW + gap)).toInt().coerceIn(1, accounts.size)
        val fittingRows = max(1, floor((contentH + gap) / (minH + gap)).toInt())
        val capacity = columns * fittingRows
        val shown = accounts.take(capacity)
        val usedRows = ceil(shown.size / columns.toFloat()).toInt()
        val tileW = (contentW - gap * (columns - 1)) / columns
        val tileH = min((contentH - gap * (usedRows - 1)) / usedRows, maxTileHeight(config.copy(style = style), rowCount))
        val gridH = tileH * usedRows + gap * (usedRows - 1)
        val gridTop = top + max(0f, (contentH - gridH) / 2)
        val framed = accounts.size > 1 || shown.size > 1
        if (hasHeader) drawHeader(painter, accounts, now, refreshing, width, pad, config, hidden = accounts.size - shown.size)

        shown.forEachIndexed { index, account ->
            val x = left + (index % columns) * (tileW + gap)
            val y = gridTop + (index / columns) * (tileH + gap)
            if (framed) painter.roundRect(x, y, tileW, tileH, max(8f, config.corner * 0.55f), withAlpha(palette.tile))
            val inner = if (framed) 10f else 2f
            val rows = rowsFor(account, config)
            when (style) {
                WidgetStyle.BARS -> drawBars(painter, account, rows, x + inner, y + (if (framed) 9f else 2f), tileW - inner * 2, tileH - (if (framed) 18f else 4f), now)
                WidgetStyle.COMPACT -> drawCompact(painter, account, rows, x + inner, y, tileW - inner * 2, tileH, now)
                WidgetStyle.PERCENT -> drawPercent(painter, account, rows, x + inner, y + (if (framed) 8f else 2f), tileW - inner * 2, tileH - (if (framed) 16f else 4f), now)
                WidgetStyle.RINGS -> drawRings(painter, account, rows, x + inner, y + (if (framed) 8f else 2f), tileW - inner * 2, tileH - (if (framed) 16f else 4f), now)
            }
        }
        if (accounts.size > shown.size && !hasHeader && stripHeight > 0f) {
            val label = "+${accounts.size - shown.size} weitere"
            painter.text(label, width - pad - 2, height - pad + 1f, 10f, painter.medium, palette.muted, Paint.Align.RIGHT)
        }
        return bitmap
    }

    private fun drawHeader(p: Painter, accounts: List<WidgetAccount>, now: Long, refreshing: Boolean, width: Float, pad: Float, config: WidgetConfig, hidden: Int) {
        val newest = accounts.mapNotNull { it.fetchedAt }.maxOrNull()
        val stale = newest != null && now - newest > STALE_SECONDS
        val label = when {
            refreshing -> "Aktualisiere…"
            !config.showUpdated -> ""
            newest == null -> "Noch keine Messung"
            else -> "Stand " + DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(newest)) + if (stale) " · veraltet" else ""
        }
        var right = width - pad
        if (config.showRefresh) { p.icon(R.drawable.ic_widget_refresh, right - 16f, pad - 1f, 16f, p.palette.muted); right -= 22f }
        if (hidden > 0) {
            val badge = "+$hidden"
            val w = p.measure(badge, 10f, p.bold) / p.density + 14f
            p.roundRect(right - w, pad - 1f, w, 16f, 8f, p.palette.primary)
            p.text(badge, right - w / 2, pad + 10.5f, 10f, p.bold, if (p.palette.dark) 0xFF20304F.toInt() else Color.WHITE, Paint.Align.CENTER)
            right -= w + 6f
        }
        if (label.isNotEmpty()) {
            val options = listOf(label, label.removePrefix("Stand ").removeSuffix(" · veraltet").let { if (stale) "$it !" else it })
            p.text(p.fit(options, right - pad - 4f, 10f, p.medium), pad + 2, pad + 9f, 10f, p.medium, if (stale && !refreshing) p.palette.warn else p.palette.muted, maxWidth = right - pad - 4f)
        }
    }

    private fun nameOf(account: WidgetAccount) = account.title

    private fun drawBars(p: Painter, a: WidgetAccount, rows: List<Row>, x: Float, y: Float, w: Float, h: Float, now: Long) {
        val colors = p.palette
        p.brand(a.provider, x, y + 1f, 14f)
        val plan = if (p.config.showPlan && w >= 150f) a.plan else null
        val planW = plan?.let { min(p.measure(it, 10f, p.medium) / p.density + 4f, w * 0.38f) } ?: 0f
        p.text(nameOf(a), x + 20f, y + 12f, 12.5f, p.bold, colors.text, maxWidth = w - 20f - planW - 4f)
        if (plan != null) p.text(plan, x + w, y + 12f, 10f, p.medium, colors.muted, Paint.Align.RIGHT, planW)
        var cursor = y + 20f
        val room = h - 20f
        val blocks = rows.size
        val perWindow = room / blocks
        rows.forEach { row ->
            val accent = accent(colors, p.config, row, now)
            val percent = percentText(row, now)
            val percentColor = if (row.state(now) == State.LIVE) colors.text else colors.muted
            when {
                // label and percentage, the bar, and the reset line below it
                perWindow >= 36f -> {
                    p.text(row.label, x, cursor + 11f, 10f, p.medium, colors.muted)
                    p.text(percent, x + w, cursor + 12f, 13f, p.bold, percentColor, Paint.Align.RIGHT)
                    p.bar(x, cursor + 16f, w, 6f, progress(row, now), accent)
                    p.text(p.fit(resetOptions(row, now, p.config, "Reset "), w, 10f, p.regular), x, cursor + 34f, 10f, p.regular, colors.muted, maxWidth = w)
                    cursor += min(40f, perWindow)
                }
                // reset time on the label line, so it survives in shorter tiles
                perWindow >= 26f -> {
                    p.text(row.label, x, cursor + 11f, 10f, p.medium, colors.muted)
                    p.text(percent, x + w, cursor + 12f, 12.5f, p.bold, percentColor, Paint.Align.RIGHT)
                    val room2 = w - 38f - 40f
                    p.text(p.fit(resetOptions(row, now, p.config), room2, 9.5f, p.regular), x + 38f, cursor + 11f, 9.5f, p.regular, colors.muted, maxWidth = room2)
                    p.bar(x, cursor + 16f, w, 5f, progress(row, now), accent)
                    cursor += perWindow
                }
                else -> {
                    val labelW = 34f
                    val percentW = 34f
                    val mid = cursor + perWindow / 2
                    p.text(row.label, x, mid + 3.5f, 10f, p.medium, colors.muted, maxWidth = labelW)
                    p.bar(x + labelW, mid - 3f, w - labelW - percentW - 6f, 6f, progress(row, now), accent)
                    p.text(percent, x + w, mid + 4f, 11.5f, p.bold, percentColor, Paint.Align.RIGHT)
                    cursor += perWindow
                }
            }
        }
    }

    /** Narrow tiles: the name on top, the windows side by side below it. */
    private fun drawCompactNarrow(p: Painter, a: WidgetAccount, rows: List<Row>, x: Float, y: Float, w: Float, h: Float, now: Long) {
        val colors = p.palette
        val withBars = h >= 44f
        val top = y + (h - (if (withBars) 36f else 30f)) / 2
        p.brand(a.provider, x, top + 1f, 12f)
        p.text(nameOf(a), x + 17f, top + 11f, 11.5f, p.bold, colors.text, maxWidth = w - 17f)
        val column = w / rows.size
        rows.forEachIndexed { i, row ->
            val cx = x + column * i
            val cw = column - if (i < rows.lastIndex) 6f else 0f
            val live = row.state(now) == State.LIVE
            val label = if (row.seconds == FIVE_HOURS) "5h" else "Wo"
            p.text(label, cx, top + 25f, 8.5f, p.medium, colors.muted)
            p.text(percentText(row, now), cx + cw, top + 25.5f, 11f, p.bold, if (live) colors.text else colors.muted, Paint.Align.RIGHT)
            if (withBars) p.bar(cx, top + 30f, cw, 5f, progress(row, now), accent(colors, p.config, row, now))
        }
    }

    private fun drawCompact(p: Painter, a: WidgetAccount, rows: List<Row>, x: Float, y: Float, w: Float, h: Float, now: Long) {
        if (w < 190f && rows.size > 1 || w < 130f) { drawCompactNarrow(p, a, rows, x, y, w, h, now); return }
        val colors = p.palette
        val mid = y + h / 2
        p.brand(a.provider, x, mid - 7f, 14f)
        val wide = w > 300f
        val chunk = if (wide) 78f else if (rows.size > 1) 54f else 64f
        val textW = w - 22f - chunk * rows.size - 2f
        val twoLines = h >= 34f
        p.text(nameOf(a), x + 22f, if (twoLines) mid - 1.5f else mid + 4f, 12f, p.bold, colors.text, maxWidth = textW)
        if (twoLines) {
            val live = rows.firstOrNull { it.state(now) == State.LIVE }
            val at = live?.window?.resetAt
            val options = when {
                live != null && at != null -> {
                    val clock = TimeText.clock(at, now)
                    val left = TimeText.remaining(at - now)
                    val short = if (live.seconds == FIVE_HOURS) "5h" else "Wo"
                    when (p.config.reset) {
                        ResetFormat.CLOCK -> listOf("${live.label} $clock", "$short $clock", clock)
                        ResetFormat.RELATIVE -> listOf("${live.label} $left", "$short $left", left)
                        ResetFormat.BOTH -> listOf("${live.label} $clock · $left", "${live.label} $clock", "$short $clock", clock)
                    }
                }
                rows.any { it.state(now) == State.EXPIRED } -> listOf("Reset erreicht", "Reset da")
                else -> listOf("Keine Messung", "–")
            }
            p.text(p.fit(options, textW, 9.5f, p.regular), x + 22f, mid + 10f, 9.5f, p.regular, colors.muted, maxWidth = textW)
        }
        rows.forEachIndexed { i, row ->
            val cx = x + w - chunk * (rows.size - i) + 3f
            val cw = chunk - 7f
            val label = if (wide || rows.size == 1) row.label else if (row.seconds == FIVE_HOURS) "5h" else "Wo"
            p.text(label, cx, mid - 3f, 9f, p.medium, colors.muted, maxWidth = cw * 0.55f)
            p.text(percentText(row, now), cx + cw, mid - 2.5f, 11.5f, p.bold, if (row.state(now) == State.LIVE) colors.text else colors.muted, Paint.Align.RIGHT)
            p.bar(cx, mid + 3f, cw, 5f, progress(row, now), accent(colors, p.config, row, now))
        }
    }

    private fun drawPercent(p: Painter, a: WidgetAccount, rows: List<Row>, x: Float, y: Float, w: Float, h: Float, now: Long) {
        val colors = p.palette
        p.brand(a.provider, x, y, 12f)
        p.text(nameOf(a), x + 17f, y + 10f, 11f, p.bold, colors.text, maxWidth = w - 17f)
        val top = y + 14f
        val column = w / rows.size
        val sizes = if (h >= 64f) listOf(24f, 21f, 18f, 16f, 14f, 12f) else listOf(20f, 18f, 16f, 14f, 12f)
        // One size for all columns of a tile, so the numbers line up.
        val big = rows.minOf { p.fitSize(percentText(it, now), column - 6f, p.bold, sizes) }
        rows.forEachIndexed { i, row ->
            val cx = x + column * i + column / 2
            val live = row.state(now) == State.LIVE
            p.text(percentText(row, now), cx, top + 3f + big * 0.9f, big, p.bold, if (live) accent(colors, p.config, row, now) else colors.muted, Paint.Align.CENTER)
            var line = top + big * 0.9f + 3f + 12f
            p.text(row.label, cx, line, 9.5f, p.medium, colors.muted, Paint.Align.CENTER, column - 2f)
            if (h >= 62f) {
                line += 11.5f
                val options = resetOptions(row, now, p.config).let { if (p.config.reset == ResetFormat.BOTH) it.drop(1) + it.last() else it }
                p.text(p.fit(options, column - 2f, 9.5f, p.regular), cx, line, 9.5f, p.regular, colors.muted, Paint.Align.CENTER, column - 2f)
            }
        }
    }

    private fun drawRings(p: Painter, a: WidgetAccount, rows: List<Row>, x: Float, y: Float, w: Float, h: Float, now: Long) {
        val colors = p.palette
        p.brand(a.provider, x, y, 12f)
        p.text(nameOf(a), x + 17f, y + 10f, 11f, p.bold, colors.text, maxWidth = w - 17f)
        val column = w / rows.size
        val labelRoom = if (h >= 70f) 24f else 12f
        val diameter = min(min(column - 8f, h - 16f - labelRoom), 60f).coerceAtLeast(26f)
        val cy = y + 16f + diameter / 2
        rows.forEachIndexed { i, row ->
            val cx = x + column * i + column / 2
            val live = row.state(now) == State.LIVE
            p.ring(cx, cy, diameter / 2 - 3f, 5f, progress(row, now), accent(colors, p.config, row, now))
            val text = percentText(row, now)
            val size = p.fitSize(text, diameter - 14f, p.bold, listOf(12f, 11f, 10f, 9f, 8f))
            p.text(text, cx, cy + size * 0.35f, size, p.bold, if (live) colors.text else colors.muted, Paint.Align.CENTER)
            p.text(row.label, cx, y + 16f + diameter + 10f, 9.5f, p.medium, colors.muted, Paint.Align.CENTER, column - 2f)
            if (h >= 70f) {
                val options = resetOptions(row, now, p.config).let { if (p.config.reset == ResetFormat.BOTH) it.drop(1) + it.last() else it }
                p.text(p.fit(options, column - 2f, 9.5f, p.regular), cx, y + 16f + diameter + 21f, 9.5f, p.regular, colors.muted, Paint.Align.CENTER, column - 2f)
            }
        }
    }
}
