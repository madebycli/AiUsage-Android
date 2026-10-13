package com.madebycli.aiusage.ui

import android.Manifest
import android.content.Intent
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++; model.rescheduleAlerts(); model.syncBackground() }
    fun notificationSettings() { ResetAlerts.channels(context); context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
    fun requestPermission() {
        val runtimeGranted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
        if (!runtimeGranted && (!prefs.getBoolean("permissionAsked", false) || activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)) {
            prefs.edit().putBoolean("permissionAsked", true).apply()
            ResetAlerts.channels(context)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (ResetAlerts.permitted(context)) update("enabled", true)
        else { update("enabled", true); notificationSettings() }
    }
    val battery = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val unrestricted = battery.isIgnoringBatteryOptimizations(context.packageName)
    val allowed = ResetAlerts.permitted(context)
    var batteryHelp by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Automatisch aktualisieren")
        SoftCard {
            SwitchRow("Alle 4 Stunden", "Codex und Claude getrennt, nur verbundene Accounts", checked("daily", true)) { update("daily", it) }
            Text("Aktuelle Limits und Reset-Termine; bei Codex zusätzlich der kleine Tagesbericht. Forecasts und API-Abrechnung werden hier nicht abgerufen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { batteryHelp = true }) { Icon(Icons.Rounded.BatterySaver, null); Spacer(Modifier.width(8.dp)); Text(if (unrestricted) "Akku: uneingeschränkt" else "Hintergrundbetrieb erlauben") }
        }
        SectionTitle("Reset-Benachrichtigungen")
        SoftCard {
            SwitchRow("Reset-Hinweise", "Gemeldete Account-Termine, kein Forecast", checked("enabled", false)) { enabled ->
                if (enabled && !allowed) requestPermission()
                else update("enabled", enabled)
            }
            Text(if (allowed) "Android-Freigabe erteilt" else "Android-Freigabe fehlt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!allowed) FilledTonalButton(onClick = { requestPermission() }) { Text("Benachrichtigungen erlauben") }
            SwitchRow("Nur bei Nutzung", "Keine Meldung für ungenutzte Zeitfenster", checked("usedOnly", true)) { update("usedOnly", it) }
            TextButton(onClick = { notificationSettings() }) { Text("Android-Freigabe & Kanäle") }
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
    if (batteryHelp) AlertDialog(onDismissRequest = { batteryHelp = false }, title = { Text("Im Hintergrund aktualisieren") },
        text = { Text("Die App braucht keinen dauerhaft laufenden Dienst. Android plant kurze Abrufe etwa alle vier Stunden. Wenn dein Gerät sie stark einschränkt, stelle AI Usage in den Akku-Einstellungen auf uneingeschränkt. Auch dann sind Abrufe und Reset-Hinweise nicht sekundengenau garantiert.") },
        confirmButton = { TextButton(onClick = {
            batteryHelp = false
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        }) { Text("Akku-Einstellungen öffnen") } },
        dismissButton = { TextButton(onClick = { batteryHelp = false }) { Text("Später") } })
}
