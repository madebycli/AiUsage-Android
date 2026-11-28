@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.material3.MaterialShapes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.R
import com.madebycli.aiusage.Texts
import com.madebycli.aiusage.data.FIVE_HOURS
import kotlin.math.max
import kotlin.math.min

/**
 * Colours of one widget. The defaults are the app's own: the darker panels of 0.9.1 on the dark side, and the same
 * steps of the light scheme. [tile] is a container on the widget, [tileHigh] one step above it.
 */
class WidgetPalette(
    val surface: Int, val tile: Int, val tileHigh: Int, val text: Int, val muted: Int, val track: Int,
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int,
    val tertiary: Int, val error: Int, val good: Int, val warn: Int, val dark: Boolean
)

internal object WidgetColorsKit {
    fun palette(context: Context, config: WidgetConfig): WidgetPalette {
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (config.theme) { WidgetTheme.SYSTEM -> systemDark; WidgetTheme.LIGHT -> false; else -> true }
        val black = config.theme == WidgetTheme.BLACK
        val base = if (dark) WidgetPalette(
            surface = if (black) 0xFF000000.toInt() else 0xFF14161C.toInt(), tile = if (black) 0xFF111318.toInt() else 0xFF1B1D24.toInt(),
            tileHigh = if (black) 0xFF1B1D24.toInt() else 0xFF252832.toInt(),
            text = 0xFFE4E5EE.toInt(), muted = 0xFFAFB1C0.toInt(), track = if (black) 0xFF252832.toInt() else 0xFF30333E.toInt(),
            primary = 0xFFB8C5ED.toInt(), onPrimary = 0xFF20304F.toInt(), primaryContainer = 0xFF333D55.toInt(), onPrimaryContainer = 0xFFE0E7FF.toInt(),
            tertiary = 0xFFD7BFEA.toInt(), error = 0xFFF2B8B5.toInt(), good = 0xFF8FD3A8.toInt(), warn = 0xFFF0C46A.toInt(), dark = true)
        else WidgetPalette(
            surface = 0xFFF0F2F8.toInt(), tile = 0xFFE3E7F1.toInt(), tileHigh = 0xFFDCE1EC.toInt(),
            text = 0xFF1D2029.toInt(), muted = 0xFF5D6171.toInt(), track = 0xFFD2D7E4.toInt(),
            primary = 0xFF4A5D8A.toInt(), onPrimary = 0xFFFFFFFF.toInt(), primaryContainer = 0xFFDBE4FF.toInt(), onPrimaryContainer = 0xFF263758.toInt(),
            tertiary = 0xFF74588C.toInt(), error = 0xFFB3261E.toInt(), good = 0xFF2E7D55.toInt(), warn = 0xFFB87A10.toInt(), dark = false)
        if (!config.dynamic || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return base
        fun c(id: Int, fallback: Int) = runCatching { ContextCompat.getColor(context, id) }.getOrNull() ?: fallback
        return if (dark) WidgetPalette(
            if (black) base.surface else c(android.R.color.system_neutral1_900, base.surface),
            if (black) base.tile else c(android.R.color.system_neutral1_800, base.tile),
            if (black) base.tileHigh else c(android.R.color.system_neutral2_800, base.tileHigh),
            c(android.R.color.system_neutral1_50, base.text), c(android.R.color.system_neutral2_200, base.muted),
            c(android.R.color.system_neutral2_700, base.track), c(android.R.color.system_accent1_200, base.primary),
            c(android.R.color.system_accent1_800, base.onPrimary), c(android.R.color.system_accent1_700, base.primaryContainer),
            c(android.R.color.system_accent1_100, base.onPrimaryContainer), c(android.R.color.system_accent3_200, base.tertiary),
            base.error, base.good, base.warn, true)
        else WidgetPalette(
            c(android.R.color.system_neutral1_50, base.surface), c(android.R.color.system_neutral2_100, base.tile),
            c(android.R.color.system_neutral2_200, base.tileHigh),
            c(android.R.color.system_neutral1_900, base.text), c(android.R.color.system_neutral2_700, base.muted),
            c(android.R.color.system_neutral2_200, base.track), c(android.R.color.system_accent1_600, base.primary),
            c(android.R.color.system_accent1_0, base.onPrimary), c(android.R.color.system_accent1_100, base.primaryContainer),
            c(android.R.color.system_accent1_900, base.onPrimaryContainer), c(android.R.color.system_accent3_600, base.tertiary),
            base.error, base.good, base.warn, false)
    }

    /** Brand colours, the same as in the app. */
    fun brand(provider: String, palette: WidgetPalette): Int = when (provider) {
        Providers.CODEX -> if (palette.dark) 0xFF4CC79F.toInt() else 0xFF0E8A6B.toInt()
        Providers.GEMINI -> if (palette.dark) 0xFF8AB4F8.toInt() else 0xFF3367D6.toInt()
        else -> if (palette.dark) 0xFFE59A7E.toInt() else 0xFFC0603F.toInt()
    }
}

/** The shapes of the app: the 5-hour window is a cookie, the week a flower, as on the playful home page. */
internal object WidgetShapes {
    val five: RoundedPolygon get() = MaterialShapes.Cookie9Sided
    val week: RoundedPolygon get() = MaterialShapes.Flower
    val badge: RoundedPolygon get() = MaterialShapes.Cookie7Sided
    val soft: RoundedPolygon get() = MaterialShapes.SoftBurst
    val pill: RoundedPolygon get() = MaterialShapes.Pill
    fun window(seconds: Long) = if (seconds == FIVE_HOURS) five else week
}

/**
 * Draws with density-independent numbers; [px] converts to pixels. Text sizes follow the widget's text size setting
 * and, up to 130 %, the system font size.
 */
internal class Painter(val context: Context, val canvas: Canvas, val density: Float, val fontScale: Float, val palette: WidgetPalette, val config: WidgetConfig) {
    fun px(dp: Float) = dp * density

    /** The same painter with everything [factor] times larger; coordinates are then given in the smaller space. */
    fun scaled(factor: Float) = if (factor == 1f) this else Painter(context, canvas, density * factor, fontScale, palette, config)

    val regular: Typeface = font(com.axiel7.anihyou.core.ui.R.font.usage_rounded_regular)
    val medium: Typeface = font(com.axiel7.anihyou.core.ui.R.font.usage_rounded_medium)
    val bold: Typeface = font(com.axiel7.anihyou.core.ui.R.font.usage_rounded_bold)
    private fun font(id: Int): Typeface = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: Typeface.DEFAULT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val scale = config.textScale / 100f * min(fontScale, 1.3f)
    /** How much larger text is drawn than its nominal size; lines below a number move by this much. */
    val textFactor get() = scale

    /** The longest of [options] that fits, at [size] or a step smaller before anything is cut off. */
    fun line(options: List<String>, x: Float, y: Float, maxWidth: Float, size: Float, face: Typeface, color: Int, align: Paint.Align = Paint.Align.LEFT) {
        val s = if (fits(options, maxWidth, size, face) || !fits(options, maxWidth, size - 1.5f, face)) size else size - 1.5f
        text(fit(options, maxWidth, s, face), x, y, s, face, color, align, maxWidth)
    }

    fun sp(size: Float) = size * scale * density

    /** Width of [text] in dp. */
    fun width(text: String, size: Float, face: Typeface): Float {
        textPaint.typeface = face; textPaint.textSize = sp(size)
        return textPaint.measureText(text) / density
    }

    /** Text with its baseline at [y]; [maxWidth] (dp) cuts it with an ellipsis. */
    fun text(text: String, x: Float, y: Float, size: Float, face: Typeface, color: Int, align: Paint.Align = Paint.Align.LEFT, maxWidth: Float? = null) {
        textPaint.typeface = face; textPaint.textSize = sp(size); textPaint.color = color; textPaint.textAlign = align
        val shown = if (maxWidth == null) text else TextUtils.ellipsize(text, textPaint, px(max(0f, maxWidth)), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, px(x), px(y), textPaint)
    }

    /** The largest of [sizes] at which [text] fits in [maxWidth] dp; the smallest otherwise. */
    fun fitSize(text: String, maxWidth: Float, face: Typeface, sizes: List<Float>): Float =
        sizes.firstOrNull { width(text, it, face) <= maxWidth } ?: sizes.last()

    /** The first of [options] that fits in [maxWidth] dp, or the last one. */
    fun fit(options: List<String>, maxWidth: Float, size: Float, face: Typeface): String =
        options.firstOrNull { width(it, size, face) <= maxWidth } ?: options.last()

    /** Whether one of [options] fits without an ellipsis. */
    fun fits(options: List<String>, maxWidth: Float, size: Float, face: Typeface) = options.any { width(it, size, face) <= maxWidth }

    fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        if (w <= 0f || h <= 0f) return
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawRoundRect(RectF(px(x), px(y), px(x + w), px(y + h)), px(radius), px(radius), paint)
    }

    fun circle(cx: Float, cy: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawCircle(px(cx), px(cy), px(radius), paint)
    }

    fun outline(x: Float, y: Float, w: Float, h: Float, radius: Float, stroke: Float, color: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = px(stroke); paint.color = color
        val half = stroke / 2
        canvas.drawRoundRect(RectF(px(x + half), px(y + half), px(x + w - half), px(y + h - half)), px(radius), px(radius), paint)
        paint.style = Paint.Style.FILL
    }

    /** A track with a rounded fill; a little progress still shows as a dot. */
    fun bar(x: Float, y: Float, w: Float, h: Float, progress: Float, color: Int, track: Int = palette.track) {
        roundRect(x, y, w, h, h / 2, track)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) roundRect(x, y, max(w * p, h), h, h / 2, color)
    }

    /** A ring that opens at the bottom, 270°, with round ends. */
    fun ring(cx: Float, cy: Float, radius: Float, stroke: Float, progress: Float, color: Int) {
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = px(stroke)
        val rect = RectF(px(cx - radius), px(cy - radius), px(cx + radius), px(cy + radius))
        paint.color = palette.track
        canvas.drawArc(rect, 135f, 270f, false, paint)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) { paint.color = color; canvas.drawArc(rect, 135f, max(270f * p, 1f), false, paint) }
        paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
    }

    private fun shapePath(shape: RoundedPolygon, x: Float, y: Float, size: Float, rotation: Float): Path {
        val path = shape.toPath()
        val matrix = Matrix()
        matrix.setScale(px(size), px(size))
        matrix.postRotate(rotation, px(size) / 2, px(size) / 2)
        matrix.postTranslate(px(x), px(y))
        path.transform(matrix)
        return path
    }

    /** A Material shape in a [size] dp square. */
    fun shape(shape: RoundedPolygon, x: Float, y: Float, size: Float, color: Int, rotation: Float = 0f) {
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawPath(shapePath(shape, x, y, size, rotation), paint)
    }

    /**
     * The shape filled from the bottom like a glass, with a still wave on its surface: the same picture as the playful
     * home page, frozen. A full window has a flat surface.
     */
    fun liquid(shape: RoundedPolygon, x: Float, y: Float, size: Float, level: Float, color: Int, track: Int, rotation: Float = 0f) {
        val path = shapePath(shape, x, y, size, rotation)
        paint.style = Paint.Style.FILL; paint.color = track
        canvas.drawPath(path, paint)
        val fill = level.coerceIn(0f, 1f)
        if (fill <= 0f) return
        canvas.save()
        canvas.clipPath(path)
        val top = y + size * (1f - fill)
        val amplitude = if (fill >= .995f) 0f else min(3.5f, size * .035f)
        val wave = Path().apply {
            moveTo(px(x), px(y + size))
            val steps = 28
            for (s in 0..steps) {
                val wx = x + size * s / steps
                val wy = top + amplitude * kotlin.math.sin(1.3f + s / steps.toFloat() * 2f * Math.PI.toFloat())
                lineTo(px(wx), px(wy))
            }
            lineTo(px(x + size), px(y + size)); close()
        }
        paint.color = color
        canvas.drawPath(wave, paint)
        canvas.restore()
    }

    fun icon(resource: Int, x: Float, y: Float, size: Float, color: Int) {
        val drawable = ContextCompat.getDrawable(context, resource)?.mutate() ?: return
        DrawableCompat.setTint(drawable, color)
        drawable.setBounds(px(x).toInt(), px(y).toInt(), px(x + size).toInt(), px(y + size).toInt())
        drawable.draw(canvas)
    }

    fun brand(provider: String, x: Float, y: Float, size: Float) = icon(
        when (provider) { Providers.CODEX -> R.drawable.ic_brand_codex; Providers.GEMINI -> R.drawable.ic_brand_gemini; else -> R.drawable.ic_brand_claude },
        x, y, size, if (provider == Providers.CODEX) palette.text else WidgetColorsKit.brand(provider, palette))

    /** The brand mark on a soft badge shape in the brand tone. */
    fun brandBadge(provider: String, x: Float, y: Float, size: Float) {
        val tone = WidgetColorsKit.brand(provider, palette)
        shape(WidgetShapes.badge, x, y, size, withAlpha(tone, if (palette.dark) .22f else .16f))
        brand(provider, x + size * .24f, y + size * .24f, size * .52f)
    }

    /** A rounded label; returns its width in dp. */
    fun chip(text: String, x: Float, y: Float, height: Float, size: Float, fill: Int, color: Int, align: Paint.Align = Paint.Align.LEFT): Float {
        val w = width(text, size, medium) + height * .9f
        val left = if (align == Paint.Align.RIGHT) x - w else x
        roundRect(left, y, w, height, height / 2, fill)
        text(text, left + w / 2, y + height / 2 + size * .36f, size, medium, color, Paint.Align.CENTER)
        return w
    }
}

internal fun withAlpha(color: Int, alpha: Float) = Color.argb((Color.alpha(color) * alpha.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

/** Mixes [color] into [base]; used for tonal fills that stay opaque on a translucent widget. */
internal fun blend(base: Int, color: Int, amount: Float): Int {
    val a = amount.coerceIn(0f, 1f)
    fun ch(b: Int, c: Int) = (b + (c - b) * a).toInt()
    return Color.argb(Color.alpha(base), ch(Color.red(base), Color.red(color)), ch(Color.green(base), Color.green(color)), ch(Color.blue(base), Color.blue(color)))
}

/**
 * The frame every widget shares: rounded body, an optional header with title, time of the last reading and the
 * refresh button. The refresh button sits where the launcher's touch target is (top end, 44 dp).
 */
internal class WidgetFrame(val left: Float, val top: Float, val width: Float, val height: Float, val pad: Float, val hasHeader: Boolean)

internal object WidgetChrome {
    const val HEADER = 36f
    /** Data older than this is marked as outdated. */
    const val STALE_SECONDS = 6 * 3600L

    fun header(p: Painter, title: String?, newest: Long?, now: Long, refreshing: Boolean, width: Float, badge: String? = null) {
        val c = p.palette
        val config = p.config
        var right = width - 10f
        if (config.showRefresh) {
            // 44 dp touch target at the top end; the visible button is a 30 dp tonal circle in its middle.
            p.circle(width - 22f, 22f, 15f, if (refreshing) c.primaryContainer else c.tile)
            p.icon(R.drawable.ic_widget_refresh, width - 31f, 13f, 18f, if (refreshing) c.onPrimaryContainer else c.muted)
            right = width - 44f
        }
        // The title comes first; the badge only where it fits next to it.
        val titleW = title?.let { p.width(it, 13f, p.bold) } ?: 0f
        if (badge != null) {
            val chipW = p.width(badge, 10.5f, p.medium) + 18f
            if (16f + (if (titleW > 0f) titleW + 8f else 0f) + chipW <= right) right -= p.chip(badge, right, 12f, 20f, 10.5f, c.primaryContainer, c.onPrimaryContainer, Paint.Align.RIGHT) + 6f
        }
        val stale = newest != null && now - newest > STALE_SECONDS
        val status = when {
            refreshing -> Texts.get(R.string.widget_refreshing)
            !config.showUpdated -> null
            newest == null -> Texts.get(R.string.usage_no_reading)
            else -> com.madebycli.aiusage.data.Fmt.clockOnly(Texts.resources, newest)
        }
        val x = 16f
        val room = right - x - 4f
        // A title that would be cut off is left out; the widget's content says what it is.
        if (title != null && p.width(title, 13f, p.bold) <= room) {
            p.text(title, x, 26.5f, 13f, p.bold, c.primary, maxWidth = room)
            if (status != null) {
                val sx = x + titleW + 8f
                val statusRoom = right - sx - 4f
                if (stale && !refreshing) {
                    if (statusRoom > 18f) { p.circle(sx + 3f, 22.5f, 3f, c.warn); p.text(status, sx + 10f, 26.5f, 11f, p.medium, c.warn, maxWidth = statusRoom - 10f) }
                } else if (statusRoom > 24f) p.text(status, sx, 26.5f, 11f, p.medium, c.muted, maxWidth = statusRoom)
            }
        } else if (status != null) {
            if (stale && !refreshing) { p.circle(x + 3f, 22.5f, 3f, c.warn); p.text(status, x + 10f, 26.5f, 11f, p.medium, c.warn, maxWidth = room - 10f) }
            else p.text(status, x, 26.5f, 11f, p.medium, c.muted, maxWidth = room)
        }
    }

    /** A centred note for empty states. */
    fun empty(p: Painter, text: String, x: Float, y: Float, w: Float, h: Float) {
        p.shape(WidgetShapes.soft, x + w / 2 - 14f, y + h / 2 - 26f, 28f, withAlpha(p.palette.primary, .22f))
        p.text(text, x + w / 2, y + h / 2 + 16f, 12f, p.medium, p.palette.muted, Paint.Align.CENTER, w - 8f)
    }
}
