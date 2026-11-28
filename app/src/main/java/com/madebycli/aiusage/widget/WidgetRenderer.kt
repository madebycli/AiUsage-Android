package com.madebycli.aiusage.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.madebycli.aiusage.R
import com.madebycli.aiusage.Texts
import java.time.Instant
import kotlin.math.max

/**
 * Draws every widget into a bitmap. Shared frame here (body, header, refresh button, empty states); the content of
 * each kind lives in its own file.
 */
object WidgetRenderer {
    const val STALE_SECONDS = WidgetChrome.STALE_SECONDS

    fun palette(context: Context, config: WidgetConfig): WidgetPalette = WidgetColorsKit.palette(context, config)

    /** The header (title, time, refresh) needs this much height. Small widgets show only their content. */
    fun hasHeader(config: WidgetConfig, widthDp: Int, heightDp: Int): Boolean = widthDp >= 110 && when (config.kind) {
        // The usage widget gives its height to the shapes first; a standard 4×2 widget has no room for a header.
        WidgetKind.USAGE -> heightDp >= 150 && (config.showRefresh || config.showUpdated)
        else -> heightDp >= 100
    }

    fun refreshVisible(config: WidgetConfig, widthDp: Int, heightDp: Int) = config.showRefresh && hasHeader(config, widthDp, heightDp)

    private fun padding(widthDp: Int, heightDp: Int) = if (heightDp < 90 || widthDp < 120) 10f else 14f

    private fun content(config: WidgetConfig, widthDp: Int, heightDp: Int): Area {
        val pad = padding(widthDp, heightDp)
        val top = if (hasHeader(config, widthDp, heightDp)) WidgetChrome.HEADER + 6f else pad
        return Area(pad, top, widthDp - pad * 2, heightDp - top - pad)
    }

    /** The usage style drawn at this size; the settings show a hint when it differs from the chosen one. */
    fun effectiveStyle(config: WidgetConfig, widthDp: Int, heightDp: Int): WidgetStyle =
        UsageWidgetDraw.effectiveStyle(config, content(config, max(40, widthDp), max(40, heightDp)).h)

    fun render(context: Context, config: WidgetConfig, all: List<WidgetAccount>, widthDp: Int, heightDp: Int, now: Long = Instant.now().epochSecond,
        refreshing: Boolean = false, density: Float = context.resources.displayMetrics.density): Bitmap {
        val w = max(40, widthDp)
        val h = max(40, heightDp)
        val bitmap = Bitmap.createBitmap((w * density).toInt().coerceAtLeast(1), (h * density).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = palette(context, config)
        val p = Painter(context, canvas, density, context.resources.configuration.fontScale, palette, config)
        val alpha = config.opacity / 100f
        // Containers stay opaque against the body so that text on them keeps its contrast on any wallpaper.
        val surface = withAlpha(palette.surface, alpha)
        val tile = if (alpha >= 1f) palette.tile else withAlpha(palette.tile, max(alpha, .72f))
        p.roundRect(0f, 0f, w.toFloat(), h.toFloat(), config.corner.toFloat(), surface)

        val accounts = WidgetData.select(all, config)
        val area = content(config, w, h)
        val header = hasHeader(config, w, h)
        val newest = accounts.mapNotNull { it.fetchedAt }.maxOrNull()
        if (header) {
            val badge = when (config.kind) {
                WidgetKind.USAGE -> (accounts.size - UsageWidgetDraw.capacity(config, area, accounts.size)).takeIf { it > 0 }?.let { "+$it" }
                WidgetKind.RESETS -> ResetsWidgetDraw.banked(accounts).takeIf { it > 0 }?.let { Texts.plural(R.plurals.widget_banked, it, it) }
                else -> null
            }
            WidgetChrome.header(p, Texts.get(config.kind.label), newest, now, refreshing, w.toFloat(), badge)
        }
        if (accounts.isEmpty()) {
            WidgetChrome.empty(p, Texts.get(if (all.isEmpty()) R.string.widget_no_account else R.string.widget_none_chosen), area.x, area.y, area.w, area.h)
            return bitmap
        }
        // Tiles take the body colour when the body is see-through, so translucent widgets do not turn patchy.
        val back = if (alpha >= 1f) palette.surface else palette.tile
        when (config.kind) {
            WidgetKind.USAGE -> {
                // Without a header, accounts that do not fit are counted in the first tile's title row.
                val hidden = (accounts.size - UsageWidgetDraw.capacity(config, area, accounts.size)).takeIf { it > 0 && !header }
                UsageWidgetDraw.draw(p, config, accounts, area, now, back, tile, hidden)
            }
            WidgetKind.STATS -> if (accounts.all { it.ledger.fiveCycles.isEmpty() && it.ledger.weekCycles.isEmpty() && it.ledger.days.isEmpty() })
                WidgetChrome.empty(p, Texts.get(R.string.widget_no_history), area.x, area.y, area.w, area.h)
                else StatsWidgetDraw.draw(p, config, accounts, area, now, tile)
            WidgetKind.PLAN -> PlanWidgetDraw.draw(p, config, accounts, area, now, tile)
            WidgetKind.RESETS -> ResetsWidgetDraw.draw(p, config, accounts, area, now, tile)
        }
        return bitmap
    }
}
