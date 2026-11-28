package com.madebycli.aiusage.widget

import android.content.Context
import androidx.annotation.StringRes
import com.madebycli.aiusage.R
import com.madebycli.aiusage.data.StatsMetric
import com.madebycli.aiusage.data.appJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** The four widgets in the launcher's list. Each has its own provider; the kind decides what the renderer draws. */
enum class WidgetKind(@StringRes val label: Int, @StringRes val description: Int) {
    USAGE(R.string.widget_kind_usage, R.string.widget_kind_usage_desc),
    STATS(R.string.widget_kind_stats, R.string.widget_kind_stats_desc),
    PLAN(R.string.widget_kind_plan, R.string.widget_kind_plan_desc),
    RESETS(R.string.widget_kind_resets, R.string.widget_kind_resets_desc)
}

/** How the usage widget shows a window. Names are stored, so existing ones keep their spelling. */
enum class WidgetStyle(@StringRes val label: Int) {
    LIQUID(R.string.widget_style_liquid),
    BARS(R.string.widget_style_bars),
    RINGS(R.string.widget_style_rings),
    PERCENT(R.string.widget_style_percent),
    COMPACT(R.string.widget_style_compact)
}

enum class WidgetArrangement(@StringRes val label: Int) { AUTO(R.string.widget_auto), STACK(R.string.widget_stack), GRID(R.string.widget_grid) }
enum class WidgetWindows(@StringRes val label: Int) { FIVE(R.string.window_five), WEEK(R.string.range_week), BOTH(R.string.widget_both) }
enum class ResetFormat(@StringRes val label: Int) { CLOCK(R.string.widget_reset_clock), RELATIVE(R.string.widget_reset_relative), BOTH(R.string.widget_both) }
enum class WidgetTheme(@StringRes val label: Int) { SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark), BLACK(R.string.widget_black) }
enum class WidgetColors(@StringRes val label: Int) { THEME(R.string.widget_colors_app), TRAFFIC(R.string.widget_colors_traffic) }
/** Days the statistics widget looks back. */
enum class StatsSpan(val days: Int, @StringRes val label: Int) { WEEK(7, R.string.widget_span_week), TWO_WEEKS(14, R.string.widget_span_two_weeks), MONTH(28, R.string.widget_span_month) }

@Serializable
data class WidgetConfig(
    val kind: WidgetKind = WidgetKind.USAGE,
    /** Accounts in the order shown. Empty means every account in the order of the home screen. */
    val accounts: List<String> = emptyList(),
    val style: WidgetStyle = WidgetStyle.LIQUID,
    val arrangement: WidgetArrangement = WidgetArrangement.AUTO,
    val windows: WidgetWindows = WidgetWindows.BOTH,
    val reset: ResetFormat = ResetFormat.CLOCK,
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val colors: WidgetColors = WidgetColors.THEME,
    /** Android colours (Material You) instead of the app palette; Android 12 or newer. */
    val dynamic: Boolean = false,
    /** Background opacity in percent. */
    val opacity: Int = 100,
    val corner: Int = 28,
    /** Text size in percent. */
    val textScale: Int = 100,
    val showRefresh: Boolean = true,
    val showUpdated: Boolean = true,
    val showPlan: Boolean = true,
    val metric: StatsMetric = StatsMetric.FIVE,
    val span: StatsSpan = StatsSpan.WEEK
) {
    fun sanitized() = copy(opacity = opacity.coerceIn(0, 100), corner = corner.coerceIn(0, 40), textScale = textScale.coerceIn(80, 140))
}

/** Per-widget settings. Plain preferences: they only name accounts and styles, never hold credentials. */
object WidgetConfigStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("widgets", 0)

    fun read(context: Context, widgetId: Int, kind: WidgetKind? = null): WidgetConfig {
        val stored = prefs(context).getString("config.$widgetId", null)?.let { runCatching { appJson.decodeFromString<WidgetConfig>(it) }.getOrNull() }?.sanitized()
        val config = stored ?: WidgetConfig(kind = kind ?: WidgetKind.USAGE, reset = if (kind == WidgetKind.RESETS) ResetFormat.BOTH else ResetFormat.CLOCK)
        // The provider decides the kind; a stored value from an older version or another provider is corrected.
        return if (kind != null && config.kind != kind) config.copy(kind = kind) else config
    }

    fun has(context: Context, widgetId: Int) = prefs(context).contains("config.$widgetId")

    fun write(context: Context, widgetId: Int, config: WidgetConfig) {
        prefs(context).edit().putString("config.$widgetId", appJson.encodeToString(config.sanitized())).commit()
    }

    fun delete(context: Context, widgetId: Int) {
        prefs(context).edit().remove("config.$widgetId").remove("refreshing.$widgetId").apply()
    }

    /** Until when a widget shows that it is refreshing. */
    fun refreshingUntil(context: Context, widgetId: Int) = prefs(context).getLong("refreshing.$widgetId", 0)
    fun markRefreshing(context: Context, widgetId: Int, until: Long) { prefs(context).edit().putLong("refreshing.$widgetId", until).apply() }
}
