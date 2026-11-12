/* UI adapted from Orion Store (RookieEnough and contributors), GPL-3.0, e561e91.
 * Settings.kt Page/SRow/Toggle, About.kt hero; adapted to AI Usage data and navigation.
 * Iris Build 102 supplies the attributed Orion primitives. See docs/ORION_SCREEN_MAP.md.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.*
import com.madebycli.aiusage.R
import com.madebycli.aiusage.widget.*

enum class DesignPage(val title: String, val description: String, val icon: ImageVector) {
    SETTINGS("Einstellungen", "Dein Überblick, dein Stil.", Icons.Rounded.Settings),
    ACCOUNTS("Anmeldungen", "Deine Accounts an einem Ort.", Icons.Rounded.Key),
    APPEARANCE("Erscheinungsbild", "Farben und Kontraste für deinen Alltag.", Icons.Rounded.Palette),
    NOTIFICATIONS("Benachrichtigungen", "Resets und Aktualisierung im Blick.", Icons.Rounded.Notifications),
    NAVIGATION("Navigation & Start", "Deine Reihenfolge, deine Startseite.", Icons.Rounded.Tune),
    WIDGETS("Widgets", "Dein Verbrauch auf dem Homescreen.", Icons.Rounded.Widgets),
    ABOUT("Über AI Usage", "Unabhängig. Offen. Für deinen Überblick.", Icons.Rounded.Info),
    USAGE("Dein Verbrauch", "Alle Accounts und ihre Zeitfenster.", Icons.Rounded.DonutLarge),
    STATS("Statistiken", "Verläufe, Vergleiche und Aktivität.", Icons.Rounded.BarChart),
    RESETS("Resets", "Prognosen und Meldungen der Quellen.", Icons.Rounded.Update),
    HISTORY("Verlauf", "Gemeldete Resets im zeitlichen Überblick.", Icons.Rounded.History),
    SOURCES("Quellen", "Herkunft und Aktualität jeder Einschätzung.", Icons.Rounded.QueryStats),
    LOGIN("Verbinden", "Sicher über den Browser anmelden.", Icons.Rounded.Login)
}

/** Same category hues as Orion Settings.CATS; native tonal conversion handles light and dark. */
val DesignPage.accent: Color get() = when (this) {
    DesignPage.ACCOUNTS -> Color(0xFFEC4899)
    DesignPage.APPEARANCE -> Color(0xFFA855F7)
    DesignPage.NOTIFICATIONS -> Color(0xFFF97316)
    DesignPage.NAVIGATION -> Color(0xFF14B8A6)
    DesignPage.WIDGETS -> Color(0xFF38BDF8)
    DesignPage.RESETS, DesignPage.HISTORY -> Color(0xFF22C55E)
    DesignPage.ABOUT -> Color(0xFF6366F1)
    else -> Color(0xFF3B82F6)
}

/** Category polygons follow Orion Settings.CATS and match between tile and destination. */
val DesignPage.shape: androidx.graphics.shapes.RoundedPolygon get() = when (this) {
    DesignPage.ACCOUNTS -> MaterialShapes.Cookie6Sided
    DesignPage.APPEARANCE -> MaterialShapes.Flower
    DesignPage.NOTIFICATIONS -> MaterialShapes.Sunny
    DesignPage.NAVIGATION -> MaterialShapes.Pentagon
    DesignPage.WIDGETS -> MaterialShapes.Clover4Leaf
    DesignPage.ABOUT -> MaterialShapes.SoftBurst
    DesignPage.RESETS, DesignPage.HISTORY -> MaterialShapes.Gem
    else -> MaterialShapes.Cookie9Sided
}

@Composable
internal fun AppearanceScreen(state: UiState, model: UsageViewModel) {
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Farbschema")
        SoftCard {
            SegmentedTabGroup(ThemeMode.entries, state.theme, model::setTheme, { when(it) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Hell"; ThemeMode.DARK -> "Dunkel" } }, fillEqually = true)
        }
        SoftCard {
            Text("Google Sans Flex", style = MaterialTheme.typography.titleMedium)
            Text("Schriftart der App", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MenuCard {
            SwitchRow("Android-Farben", "Farbpalette aus dem Hintergrundbild", state.dynamicColor, model::setDynamic)
            SwitchRow("Tiefschwarz", "Für den dunklen Modus", state.amoled, model::setAmoled)
        }
    }
}

@Composable
internal fun AboutScreen() {
    val uri = LocalUriHandler.current
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
                OrionSpinner(MaterialShapes.SoftBurst, MaterialTheme.colorScheme.primary.copy(.20f), 160.dp, periodMs = 30000)
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 6.dp) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher), "AI Usage App-Logo", Modifier.padding(18.dp).size(56.dp))
                }
            }
            Text("AI Usage", style = MaterialTheme.typography.displaySmallEmphasized)
            Text("Version ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("Inoffizielle App, unabhängig von OpenAI und Anthropic.", style = MaterialTheme.typography.bodyMedium)
        }
        SectionTitle("Quellcode & Lizenzen")
        MenuCard {
            MenuRow("AI Usage", Icons.Rounded.Code, "madebycli · GPL-3.0", index = 0, count = 4) { uri.openUri("https://github.com/madebycli/AiUsage-Android") }
            MenuRow("Orion Store · UI-Quellen & Lizenz", Icons.Rounded.AutoAwesome, "RookieEnough und Mitwirkende · GPL-3.0", index = 1, count = 4) { uri.openUri("https://github.com/RookieEnough/Orion-Store/tree/e561e91e9409d9d9b437ab7acf919cd115769d7e") }
            MenuRow("Iris · Designreferenz", Icons.Rounded.Palette, "madebycli · GPL-3.0", index = 2, count = 4) { uri.openUri("https://github.com/madebycli/Iris/tree/60bbb256e588103ad18f9776ed8acfdde7c6f156") }
            MenuRow("AniHyou · Basis", Icons.Rounded.Layers, "axiel7 · GPL-3.0", index = 3, count = 4) { uri.openUri("https://github.com/axiel7/AniHyou") }
        }
        Notice("Weitere Grundlagen: AniSync (GPL-3.0), CodexBar (MIT). Google Sans Flex (OFL-1.1). Lizenztexte und Herkunft sind im Repository dokumentiert.")
    }
}

@Composable
internal fun WidgetsScreen() {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    var ids by remember { mutableStateOf(manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java)).toList()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { ids = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java)).toList() }
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (ids.isEmpty()) Notice("Zum Hinzufügen den Homescreen lange drücken, Widgets öffnen und AI Usage auswählen. Größe und Platzierung legst du im Launcher fest.")
        ids.forEach { id ->
            val config = WidgetConfigStore.read(context, id)
            MenuRow("Widget $id", Icons.Rounded.Widgets, "${config.style.title} · ${config.arrangement.title}") {
                context.startActivity(Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            }
        }
    }
}
