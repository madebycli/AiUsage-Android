package com.madebycli.aiusage.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.background.ResetAlerts

@Composable
internal fun NotificationSettings(state: UiState, model: UsageViewModel) {
    val context = LocalContext.current
    val prefs = remember { ResetAlerts.prefs(context) }
    var revision by remember { mutableIntStateOf(0) }
    val values = remember(revision) { prefs.all }
    fun checked(key: String, default: Boolean) = values[key] as? Boolean ?: default
    fun update(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); revision++; model.rescheduleAlerts(); model.syncBackground() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> update("enabled", granted) }
    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Automatisch aktualisieren")
        SoftCard {
            SwitchRow("Tägliche Messung", "Etwa alle 24 Stunden bei Internetverbindung", checked("daily", true)) { update("daily", it) }
            Text("Beim Öffnen wird zusätzlich aktualisiert. Android kann Hintergrundarbeit bei Energiesparen oder erzwungenem Stopp verschieben.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SectionTitle("Reset-Benachrichtigungen")
        SoftCard {
            SwitchRow("Benachrichtigungen", "Gemeldete Account-Termine, kein Forecast", checked("enabled", false)) { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33 && !ResetAlerts.permitted(context)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else update("enabled", enabled)
            }
            SwitchRow("Nur bei Nutzung", "Keine Meldung für ungenutzte Zeitfenster", checked("usedOnly", true)) { update("usedOnly", it) }
            TextButton(onClick = { ResetAlerts.channels(context); context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Android-Freigabe & Kanäle") }
        }
        val providers = buildList { if (state.connected) add("Codex"); if (state.claudeConnected) add("Claude Code") }
        providers.forEach { provider ->
            SectionTitle(provider)
            SoftCard {
                ResetAlerts.rules.filter { it.provider == provider }.forEachIndexed { index, rule ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    SwitchRow(rule.title, "Nach dem Reset-Zeitpunkt des Anbieters", checked("rule.${rule.key}", true)) { update("rule.${rule.key}", it) }
                    Text("Benachrichtigen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val lead = values["lead.${rule.key}"] as? Int ?: 0
                    SegmentedTabGroup(listOf(0, 5, 15, 30, 60), lead, { selected ->
                        prefs.edit().putInt("lead.${rule.key}", selected).apply(); revision++; model.rescheduleAlerts()
                    }, { if (it == 0) "Zum Reset" else "$it min vorher" })
                    TextButton(onClick = {
                        ResetAlerts.channels(context)
                        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, rule.channel))
                    }) { Icon(Icons.Rounded.Notifications, null); Spacer(Modifier.width(8.dp)); Text("Ton, Vibration & Anzeige") }
                }
            }
        }
        SoftCard {
            Text("So werden Termine geplant", style = MaterialTheme.typography.titleSmall)
            Text("5-Stunden- und Wochenreset werden getrennt geplant. Neue Anbieterwerte ersetzen alte Termine. Ohne gemeldeten Zeitpunkt wird nichts erfunden. Abmelden entfernt die geplanten Hinweise dieses Anbieters.", style = MaterialTheme.typography.bodySmall)
            Text("Android kann auch Hinweise verzögern. Die Meldung bestätigt einen gemeldeten Termin, nicht den tatsächlichen Reset. Zum Prüfen den Verbrauch aktualisieren.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
