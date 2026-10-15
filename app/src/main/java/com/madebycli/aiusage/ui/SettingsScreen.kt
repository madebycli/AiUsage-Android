package com.madebycli.aiusage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.BuildConfig
import com.madebycli.aiusage.ThemeMode
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(state: UiState, model: UsageViewModel, accounts: () -> Unit, notifications: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuCard {
            MenuRow("Anmeldungen", Icons.Rounded.Key, accounts)
            MenuDivider()
            MenuRow("Benachrichtigungen & Aktualisierung", Icons.Rounded.Notifications, notifications)
        }
        SoftCard {
            Text("Darstellung", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Hell", ThemeMode.DARK to "Dunkel").forEach { (theme, label) ->
                    FilterChip(state.theme == theme, { model.setTheme(theme) }, label = { Text(label) })
                }
            }
            SwitchRow("Android-Farben", "Farbpalette aus dem Hintergrundbild", state.dynamicColor, model::setDynamic)
            SwitchRow("Tiefschwarz", "Für den dunklen Modus", state.amoled, model::setAmoled)
        }
        Disclosure("Über AI Usage · ${BuildConfig.VERSION_NAME}") {
            Text("Inoffizielle App, unabhängig von OpenAI und Anthropic. Basis: AniHyou (GPL-3.0), Design nach AniSync, Schrift Google Sans Flex (OFL-1.1).", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/madebycli/AiUsage-Android") }) { Text("Quellcode") }
            TextButton(onClick = { uri.openUri("https://github.com/Marco-9456/AniSync") }) { Text("AniSync") }
        }
    }
}
