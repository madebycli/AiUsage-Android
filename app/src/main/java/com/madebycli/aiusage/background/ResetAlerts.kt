package com.madebycli.aiusage.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.madebycli.aiusage.MainActivity
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AlertRule(val provider: String, val seconds: Long, val title: String) {
    val key get() = "${if (provider == "Codex") "codex" else "claude"}.$seconds"
    val channel get() = "reset.$key"
}
object ResetAlerts {
    internal val deliveryLock = Any()
    val rules = listOf("Codex", "Claude Code").flatMap { provider ->
        listOf(AlertRule(provider, 18000, "5 Stunden"), AlertRule(provider, 604800, "Wöchentlich"))
    }
    fun prefs(context: Context) = context.getSharedPreferences("alerts", 0)
    fun permitted(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        rules.forEach { manager.createNotificationChannel(NotificationChannel(it.channel, "${it.provider} · ${it.title}", NotificationManager.IMPORTANCE_DEFAULT)) }
    }
    fun invalidate(context: Context, provider: String): Unit = synchronized(deliveryLock) {
        prefs(context).edit().putString("generation.$provider", UUID.randomUUID().toString()).commit()
        rules.filter { it.provider == provider }.forEach {
            WorkManager.getInstance(context).cancelUniqueWork(it.channel)
            NotificationManagerCompat.from(context).cancel(it.channel.hashCode())
        }
    }
    fun schedule(context: Context, provider: String, usage: UsageSnapshot) {
        val prefs = prefs(context)
        var generation = prefs.getString("generation.$provider", null)
        if (generation == null) { generation = UUID.randomUUID().toString(); prefs.edit().putString("generation.$provider", generation).commit() }
        rules.filter { it.provider == provider }.forEach { rule ->
            val window = usage.groups.firstOrNull { it.name == provider }?.windows?.firstOrNull { it.seconds == rule.seconds }
            val now = Instant.now().epochSecond
            val target = window?.resetAt
            val lead = prefs.getInt("lead.${rule.key}", 0).coerceIn(0, 1440)
            val eligible = prefs.getBoolean("enabled", false) && prefs.getBoolean("rule.${rule.key}", true) && permitted(context) &&
                target != null && target > now && (!prefs.getBoolean("usedOnly", true) || (window?.usedPercent ?: 0.0) > 0)
            val work = WorkManager.getInstance(context)
            if (!eligible) { work.cancelUniqueWork(rule.channel); prefs.edit().remove("scheduled.${rule.key}").apply(); return@forEach }
            val reset = target ?: return@forEach
            val signature = "$generation:$reset:$lead"
            // Refreshing an unchanged deadline must not reset an already-running job.
            if (prefs.getString("scheduled.${rule.key}", null) == signature) return@forEach
            val request = OneTimeWorkRequestBuilder<ResetAlertWorker>()
                .setInputData(workDataOf("rule" to rule.key, "reset" to reset, "generation" to generation!!, "scheduled" to signature))
                .setInitialDelay((reset - lead * 60L - now).coerceAtLeast(0), TimeUnit.SECONDS).build()
            work.enqueueUniqueWork(rule.channel, ExistingWorkPolicy.REPLACE, request)
            prefs.edit().putString("scheduled.${rule.key}", signature).apply()
        }
    }
    fun valid(rule: AlertRule, snapshot: UsageSnapshot?, target: Long, now: Long): Boolean =
        now - target <= 21600 && snapshot?.groups?.firstOrNull { it.name == rule.provider }?.windows
            ?.any { it.seconds == rule.seconds && it.resetAt == target } == true
}
class ResetAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @android.annotation.SuppressLint("MissingPermission") // Runtime permission/settings are checked before posting.
    override suspend fun doWork(): Result {
        val rule = ResetAlerts.rules.firstOrNull { it.key == inputData.getString("rule") } ?: return Result.success()
        val prefs = ResetAlerts.prefs(applicationContext)
        val target = inputData.getLong("reset", 0)
        val generation = inputData.getString("generation") ?: return Result.success()
        val planned = inputData.getString("scheduled") ?: return Result.success()
        var delivered = false
        val lock = if (rule.provider == "Codex") AccountAccess.codex else AccountAccess.claude
        try { lock.withLock {
            if (prefs.getString("scheduled.${rule.key}", null) != planned) return@withLock
            if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean("rule.${rule.key}", true) ||
                prefs.getString("generation.${rule.provider}", null) != generation || !ResetAlerts.permitted(applicationContext)) return@withLock
            val store = SecureStore(applicationContext, if (rule.provider == "Codex") "account" else "claude")
            val connected = if (rule.provider == "Codex") store.readTokens() != null else store.readClaude() != null
            val usage = if (connected) store.readRecord().usage else null
            val now = Instant.now().epochSecond
            if (prefs.getBoolean("usedOnly", true) && usage?.groups?.firstOrNull { it.name == rule.provider }?.windows?.firstOrNull { it.seconds == rule.seconds }?.usedPercent?.let { it > 0 } != true) return@withLock
            if (!ResetAlerts.valid(rule, usage, target, now)) return@withLock
            if (prefs.getString("delivered.${rule.key}", null) == "$generation:$target") { delivered = true; return@withLock }
            ResetAlerts.channels(applicationContext)
            val intent = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("provider", rule.provider)
            val content = PendingIntent.getActivity(applicationContext, rule.key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val remaining = target - now
            val message = if (remaining > 0) "Laut Anbieter Reset in ${(remaining + 59) / 60} Minuten." else "Gemeldeter Reset-Zeitpunkt erreicht. Verbrauch aktualisieren."
            synchronized(ResetAlerts.deliveryLock) {
                if (prefs.getString("generation.${rule.provider}", null) != generation || !prefs.getBoolean("enabled", false) || prefs.getString("scheduled.${rule.key}", null) != planned) return@synchronized
                NotificationManagerCompat.from(applicationContext).notify(rule.channel.hashCode(), NotificationCompat.Builder(applicationContext, rule.channel)
                .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("${rule.provider} · ${rule.title}")
                .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(content)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
                prefs.edit().putString("delivered.${rule.key}", "$generation:$target").apply()
                delivered = true
            }
        } } finally {
            synchronized(ResetAlerts.deliveryLock) {
                if (!delivered && prefs.getString("scheduled.${rule.key}", null) == planned) prefs.edit().remove("scheduled.${rule.key}").apply()
            }
        }
        return Result.success()
    }
}
