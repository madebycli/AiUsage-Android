package com.madebycli.aiusage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.AppTab
import com.madebycli.aiusage.BuildConfig
import com.madebycli.aiusage.ThemeMode
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

internal val AppTab.icon: ImageVector get() = when (this) {
    AppTab.USAGE -> Icons.Rounded.DonutLarge
    AppTab.STATS -> Icons.Rounded.BarChart
    AppTab.RESETS -> Icons.Rounded.History
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsScreen(state: UiState, model: UsageViewModel, accounts: () -> Unit, notifications: () -> Unit, navigation: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("App & Konten")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 320.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
            val tiles: @Composable (Modifier) -> Unit = { tile ->
                OrionBento("Anmeldungen", when (state.accounts.size) { 0 -> "Kein Account verbunden"; 1 -> "1 Account"; else -> "${state.accounts.size} Accounts" },
                    Icons.Rounded.Key, MaterialTheme.colorScheme.primary, MaterialShapes.Cookie9Sided, accounts,
                    tile.height(if (stacked) 168.dp else 180.dp).staggeredEntrance(0))
                OrionBento("Navigation & Start", "Startseite und Standardansichten", Icons.Rounded.Tune,
                    MaterialTheme.colorScheme.tertiary, MaterialShapes.Sunny, navigation,
                    tile.height(if (stacked) 168.dp else 180.dp).staggeredEntrance(1))
            }
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { tiles(Modifier.fillMaxWidth()) }
            else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { tiles(Modifier.weight(1f)) }
        }

        MenuCard {
            MenuRow("Benachrichtigungen & Aktualisierung", Icons.Rounded.Notifications, open = notifications)
        }
        SectionTitle("Erscheinungsbild")
        SoftCard {
            SegmentedTabGroup(listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK), state.theme, model::setTheme,
                { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Hell"; ThemeMode.DARK -> "Dunkel" } }, fillEqually = true)
            SwitchRow("Android-Farben", "Farbpalette aus dem Hintergrundbild", state.dynamicColor, model::setDynamic)
            SwitchRow("Tiefschwarz", "Für den dunklen Modus", state.amoled, model::setAmoled)
        }
        Disclosure("Über AI Usage · ${BuildConfig.VERSION_NAME}") {
            Text("Inoffizielle App, unabhängig von OpenAI und Anthropic. Basis: AniHyou (GPL-3.0). UI-Bausteine aus Iris und Orion Store (GPL-3.0). Schrift Google Sans Flex (OFL-1.1).", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/madebycli/AiUsage-Android") }) { Text("Quellcode") }
            Text("Iris · madebycli · GPL-3.0\nOrion Store · RookieEnough und Mitwirkende · GPL-3.0", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/madebycli/Iris/tree/60bbb256e588103ad18f9776ed8acfdde7c6f156") }) { Text("Iris · Designreferenz") }
            TextButton(onClick = { uri.openUri("https://github.com/RookieEnough/Orion-Store/tree/e561e91e9409d9d9b437ab7acf919cd115769d7e") }) { Text("Orion Store · UI-Quellen & Lizenz") }
        }
    }
}
