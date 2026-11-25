package com.madebycli.aiusage.widget

import android.content.Context
import com.madebycli.aiusage.R
import com.madebycli.aiusage.data.appJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

enum class WidgetStyle(@androidx.annotation.StringRes val label: Int, @androidx.annotation.StringRes val hint: Int) {
    BARS(R.string.widget_style_bars, R.string.widget_style_bars_hint),
    COMPACT(R.string.widget_style_compact, R.string.widget_style_compact_hint),
    PERCENT(R.string.widget_style_percent, R.string.widget_style_percent_hint),
    RINGS(R.string.widget_style_rings, R.string.widget_style_rings_hint)
}

enum class WidgetArrangement(@androidx.annotation.StringRes val label: Int) { AUTO(R.string.widget_auto), STACK(R.string.widget_stack), GRID(R.string.widget_grid) }
enum class WidgetWindowLayout(@androidx.annotation.StringRes val label: Int) { SIDE_BY_SIDE(R.string.widget_side_by_side), STACKED(R.string.widget_stack) }
enum class WidgetPreset(@androidx.annotation.StringRes val label: Int) {
    CUSTOM(R.string.widget_preset_custom), DETAILED(R.string.widget_preset_detailed), COMPACT(R.string.widget_style_compact), RINGS(R.string.widget_style_rings);
    fun applyTo(config: WidgetConfig): WidgetConfig = when (this) {
        CUSTOM -> config.copy(preset = this)
        DETAILED -> config.copy(preset = this, style = WidgetStyle.BARS, arrangement = WidgetArrangement.STACK, windowLayout = WidgetWindowLayout.STACKED, windows = WidgetWindows.BOTH, showPlan = true, reset = ResetFormat.BOTH)
        COMPACT -> config.copy(preset = this, style = WidgetStyle.COMPACT, arrangement = WidgetArrangement.STACK, windows = WidgetWindows.BOTH, textScale = 100)
        RINGS -> config.copy(preset = this, style = WidgetStyle.RINGS, arrangement = WidgetArrangement.STACK, windowLayout = WidgetWindowLayout.SIDE_BY_SIDE, windows = WidgetWindows.BOTH)
    }
}

enum class WidgetWindows(@androidx.annotation.StringRes val label: Int) { FIVE(R.string.window_five), WEEK(R.string.range_week), BOTH(R.string.widget_both) }
enum class ResetFormat(@androidx.annotation.StringRes val label: Int) { CLOCK(R.string.widget_reset_clock), RELATIVE(R.string.widget_reset_relative), BOTH(R.string.widget_both) }
enum class WidgetTheme(@androidx.annotation.StringRes val label: Int) { SYSTEM(R.string.theme_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark), BLACK(R.string.widget_black) }
enum class WidgetColors(@androidx.annotation.StringRes val label: Int) { THEME(R.string.widget_colors_app), TRAFFIC(R.string.widget_colors_traffic) }

@Serializable
data class WidgetConfig(
    /** Accounts in the order shown. Empty means every account in the order of the home screen. */
    val accounts: List<String> = emptyList(),
    val style: WidgetStyle = WidgetStyle.BARS,
    val preset: WidgetPreset = WidgetPreset.CUSTOM,
    val arrangement: WidgetArrangement = WidgetArrangement.AUTO,
    val windowLayout: WidgetWindowLayout = WidgetWindowLayout.SIDE_BY_SIDE,
    val windows: WidgetWindows = WidgetWindows.BOTH,
    val reset: ResetFormat = ResetFormat.BOTH,
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
    val showPlan: Boolean = true
) {
    fun sanitized() = copy(opacity = opacity.coerceIn(20, 100), corner = corner.coerceIn(0, 40), textScale = textScale.coerceIn(80, 140))
}

/** Per-widget settings. Plain preferences: they only name accounts and styles, never hold credentials. */
object WidgetConfigStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("widgets", 0)

    fun read(context: Context, widgetId: Int): WidgetConfig =
        prefs(context).getString("config.$widgetId", null)?.let { runCatching { appJson.decodeFromString<WidgetConfig>(it) }.getOrNull() }?.sanitized()
            ?: WidgetConfig()

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
