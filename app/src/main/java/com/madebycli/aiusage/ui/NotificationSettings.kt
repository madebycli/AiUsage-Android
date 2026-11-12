package com.madebycli.aiusage.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.background.AccountRefresh
import com.madebycli.aiusage.background.ResetAlarms
import com.madebycli.aiusage.background.ResetAlerts
import com.madebycli.aiusage.background.ResetNotifier
import com.madebycli.aiusage.data.TimeText
import com.madebycli.aiusage.data.Words
import java.time.Instant

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
    fun openBatterySettings() {
        try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
    }
    val unrestricted = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    val allowed = ResetAlerts.permitted(context)
    val now = Instant.now().epochSecond
    val last = (values["bg.last"] as? Long)?.takeIf { it > 0 }
    val failures = values["bg.failures"] as? Int ?: 0
    val reads = values["bg.reads"] as? Int ?: 0

    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Hintergrund")
        SoftCard {
            SwitchRow("Im Hintergrund aktualisieren", "Limits, Reset-Termine, Widget und Statistik", checked("daily", true)) { update("daily", it) }
            if (checked("daily", true)) {
                Text("Intervall", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SegmentedTabGroup(AccountRefresh.choices, (values["hours"] as? Int)?.toLong()?.takeIf { it in AccountRefresh.choices } ?: AccountRefresh.DEFAULT_HOURS, { hours ->
                    prefs.edit().putInt("hours", hours.toInt()).apply(); revision++; model.syncBackground()
                }, { "$it h" }, fillEqually = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                StatusLine("Letzte Abfrage", when {
                    last == null -> "Noch keine im Hintergrund"
                    else -> "${Words.ago(now - last)} · $reads ${if (reads == 1) "Account" else "Accounts"} gelesen" + if (failures > 0) " · $failures Fehler" else ""
                })
                StatusLine("Zusätzlich", "Kurz vor und nach jedem Reset, damit der Höchstwert zählt")
                StatusLine("Akku", if (unrestricted) "Uneingeschränkt" else "Von Android optimiert. Abfragen können sich verspäten.")
                if (!unrestricted) TextButton(onClick = ::openBatterySettings, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Rounded.BatterySaver, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Akku-Einstellungen öffnen")
                }
            }
        }
        SectionTitle("Reset-Hinweise")
        SoftCard {
            SwitchRow("Reset-Hinweise", "Meldung, wenn ein Limit zurückgesetzt wird", checked("enabled", false)) { enabled ->
                if (enabled && !allowed) requestPermission() else update("enabled", enabled)
            }
            StatusLine("Android-Freigabe", if (allowed) "Erteilt" else "Fehlt")
            if (!allowed) FilledTonalButton(onClick = { requestPermission() }) { Text("Benachrichtigungen erlauben") }
            SwitchRow("Nur bei Nutzung", "Nicht für Zeitfenster ohne Verbrauch", checked("usedOnly", true)) { update("usedOnly", it) }
            SwitchRow("Früher Reset", "Meldung, wenn der Anbieter ein Limit vor dem Termin zurücksetzt, zum Beispiel bei einem globalen Reset", checked("spontaneous", true)) { update("spontaneous", it) }
            val exact = ResetAlarms.canScheduleExact(context)
            StatusLine("Genauigkeit", if (exact) "Exakte Alarme erlaubt: Hinweise kommen zur gemeldeten Minute" else "Android darf Hinweise im Ruhemodus um einige Minuten verschieben")
            if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FilledTonalButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            }) { Text("Exakte Alarme erlauben") }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { ResetNotifier.sendTest(context) }, enabled = allowed) { Text("Test senden") }
                TextButton(onClick = { notificationSettings() }) { Text("Android-Einstellungen") }
            }
        }
        state.connectedProviders.forEach { provider ->
            SectionTitle(provider)
            SoftCard {
                ResetAlerts.rules.filter { it.provider == provider }.forEachIndexed { index, rule ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    SwitchRow(rule.title, "Zum gemeldeten Reset-Zeitpunkt", checked("rule.${rule.key}", true)) { update("rule.${rule.key}", it) }
                    Text("Benachrichtigen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val lead = values["lead.${rule.key}"] as? Int ?: 0
                    SegmentedTabGroup(listOf(0, 5, 15, 30, 60), lead, { selected ->
                        prefs.edit().putInt("lead.${rule.key}", selected).apply(); revision++; model.rescheduleAlerts()
                    }, { if (it == 0) "Zum Reset" else "$it min vorher" })
                    state.accountsOf(provider).forEach { entry ->
                        val next = ResetAlerts.nextReminder(context, rule.slot(entry.account))
                        StatusLine(entry.title, next?.let { "${TimeText.clock(it, now)} Uhr · ${TimeText.remaining(it - now)}" } ?: "Kein Hinweis geplant")
                    }
                    TextButton(onClick = {
                        ResetAlerts.channels(context)
                        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, rule.channel))
                    }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Rounded.Notifications, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Ton, Vibration & Anzeige") }
                }
            }
        }
        Text("Hinweise beruhen auf dem Reset-Termin, den der Anbieter zuletzt gemeldet hat. Android kann sie verzögern.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusLine(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
