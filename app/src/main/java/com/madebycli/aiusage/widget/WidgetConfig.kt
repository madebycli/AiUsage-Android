package com.madebycli.aiusage.widget

import android.content.Context
import com.madebycli.aiusage.data.appJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

enum class WidgetStyle(val title: String, val hint: String) {
    BARS("Balken", "Name, Balken und Reset pro Fenster"),
    COMPACT("Kompakt", "Eine Zeile pro Account, viele auf einmal"),
    PERCENT("Prozent", "Große Zahlen, ohne Balken"),
    RINGS("Ringe", "Runde Anzeige pro Account")
}

enum class WidgetWindows(val title: String) { FIVE("5 Stunden"), WEEK("Woche"), BOTH("Beide") }
enum class ResetFormat(val title: String) { CLOCK("Uhrzeit"), RELATIVE("Restzeit"), BOTH("Beides") }
enum class WidgetTheme(val title: String) { SYSTEM("System"), LIGHT("Hell"), DARK("Dunkel"), BLACK("Schwarz") }
enum class WidgetColors(val title: String) { THEME("Passend zur App"), TRAFFIC("Ampel") }

@Serializable
data class WidgetConfig(
    /** Accounts in the order shown. Empty means every account in the order of the home screen. */
    val accounts: List<String> = emptyList(),
    val style: WidgetStyle = WidgetStyle.BARS,
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
