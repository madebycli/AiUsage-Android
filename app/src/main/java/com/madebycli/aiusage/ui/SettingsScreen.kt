package com.madebycli.aiusage.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.AppTab
import com.madebycli.aiusage.BuildConfig
import com.madebycli.aiusage.ThemeMode
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.widget.UsageWidgetProvider

internal val AppTab.icon: ImageVector get() = when (this) {
    AppTab.USAGE -> Icons.Rounded.DonutLarge
    AppTab.STATS -> Icons.Rounded.BarChart
    AppTab.RESETS -> Icons.Rounded.History
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(state: UiState, model: UsageViewModel, accounts: () -> Unit, notifications: () -> Unit, navigation: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MenuCard {
            MenuRow("Anmeldungen", Icons.Rounded.Key, when (state.accounts.size) { 0 -> "Kein Account verbunden"; 1 -> "1 Account"; else -> "${state.accounts.size} Accounts" }, accounts)
            MenuDivider()
            MenuRow("Navigation & Start", Icons.Rounded.Tune, "Untere Leiste, Startseite, Standardansichten", navigation)
            MenuDivider()
            MenuRow("Benachrichtigungen & Aktualisierung", Icons.Rounded.Notifications, open = notifications)
        }
        WidgetCard()
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

@Composable
private fun WidgetCard() {
    val context = LocalContext.current
    SoftCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Widgets, null, tint = MaterialTheme.colorScheme.primary)
            Text("Homescreen-Widget", style = MaterialTheme.typography.titleMedium)
        }
        Text("Zeigt Verbrauch und Reset-Zeiten deiner Accounts auf dem Startbildschirm. Accounts, Reihenfolge, Größe, Darstellung und Farben stellst du pro Widget ein.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = {
            val manager = AppWidgetManager.getInstance(context)
            if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, UsageWidgetProvider::class.java), null, null)
            else Toast.makeText(context, "Startbildschirm lange antippen, dann Widgets → AI Usage.", Toast.LENGTH_LONG).show()
        }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Widget hinzufügen") }
        Text("Ein bestehendes Widget änderst du, indem du es lange antippst und „Anpassen“ wählst.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
