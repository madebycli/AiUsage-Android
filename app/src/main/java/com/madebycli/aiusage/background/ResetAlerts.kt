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
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AlertRule(val provider: String, val seconds: Long, val title: String) {
    val key get() = "${if (provider == Providers.CODEX) "codex" else "claude"}.$seconds"
    val channel get() = "reset.$key"
}

object ResetAlerts {
    /** Providers report reset times with sub-second jitter; closer times count as the same reset. */
    const val TOLERANCE_SECONDS = 120L
    /** A reminder is still useful for this long after the reset it announces. */
    const val GRACE_SECONDS = 6 * 3600L

    internal val deliveryLock = Any()
    val rules = Providers.all.flatMap { provider ->
        listOf(AlertRule(provider, FIVE_HOURS, "5 Stunden"), AlertRule(provider, ONE_WEEK, "Wöchentlich"))
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
            WorkManager.getInstance(context).cancelUniqueWork(lateName(it))
            NotificationManagerCompat.from(context).cancel(it.channel.hashCode())
            prefs(context).edit().remove("scheduled.${it.key}").remove("target.${it.key}").apply()
        }
    }

    private fun lateName(rule: AlertRule) = rule.channel + ".late"

    fun schedule(context: Context, provider: String, usage: UsageSnapshot) {
        val prefs = prefs(context)
        var generation = prefs.getString("generation.$provider", null)
        if (generation == null) { generation = UUID.randomUUID().toString(); prefs.edit().putString("generation.$provider", generation).commit() }
        val work = WorkManager.getInstance(context)
        val now = Instant.now().epochSecond
        rules.filter { it.provider == provider }.forEach { rule ->
            val window = usage.windows(provider).firstOrNull { it.seconds == rule.seconds }
            val reset = window?.resetAt
            val lead = prefs.getInt("lead.${rule.key}", 0).coerceIn(0, 1440)
            val previous = prefs.getLong("target.${rule.key}", 0)
            val sameReset = previous > 0 && reset != null && kotlin.math.abs(reset - previous) <= TOLERANCE_SECONDS
            // The announced reset passed while the job was still pending: deliver it now instead of dropping it.
            if (previous in (now - GRACE_SECONDS)..now && !sameReset) {
                val data = workDataOf("rule" to rule.key, "reset" to previous, "generation" to generation, "late" to true)
                work.enqueueUniqueWork(lateName(rule), ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ResetAlertWorker>().setInputData(data).build())
            }
            val eligible = prefs.getBoolean("enabled", false) && prefs.getBoolean("rule.${rule.key}", true) && permitted(context) &&
                reset != null && reset > now && (!prefs.getBoolean("usedOnly", true) || (window?.usedPercent ?: 0.0) > 0)
            if (!eligible || reset == null) {
                if (previous !in (now - GRACE_SECONDS)..now) work.cancelUniqueWork(rule.channel)
                prefs.edit().remove("scheduled.${rule.key}").also { if (previous < now) it.remove("target.${rule.key}") }.apply()
                return@forEach
            }
            val scheduled = prefs.getString("scheduled.${rule.key}", null)?.split(':')
            if (scheduled?.size == 3 && scheduled[0] == generation && scheduled[2] == lead.toString() &&
                scheduled[1].toLongOrNull()?.let { kotlin.math.abs(it - reset) <= TOLERANCE_SECONDS } == true) return@forEach
            val signature = "$generation:$reset:$lead"
            val request = OneTimeWorkRequestBuilder<ResetAlertWorker>()
                .setInputData(workDataOf("rule" to rule.key, "reset" to reset, "generation" to generation, "scheduled" to signature))
                .setInitialDelay((reset - lead * 60L - now).coerceAtLeast(0), TimeUnit.SECONDS).build()
            work.enqueueUniqueWork(rule.channel, ExistingWorkPolicy.REPLACE, request)
            prefs.edit().putString("scheduled.${rule.key}", signature).putLong("target.${rule.key}", reset).apply()
        }
    }

    /** The stored measurement still reports this reset, or already a later one because the window rolled over. */
    fun valid(rule: AlertRule, snapshot: UsageSnapshot?, target: Long, now: Long): Boolean {
        if (now - target > GRACE_SECONDS) return false
        val reported = snapshot?.windows(rule.provider)?.firstOrNull { it.seconds == rule.seconds }?.resetAt ?: return false
        return reported >= target - TOLERANCE_SECONDS
    }
}

class ResetAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @android.annotation.SuppressLint("MissingPermission") // The runtime permission is checked before posting.
    override suspend fun doWork(): Result {
        val rule = ResetAlerts.rules.firstOrNull { it.key == inputData.getString("rule") } ?: return Result.success()
        val prefs = ResetAlerts.prefs(applicationContext)
        val target = inputData.getLong("reset", 0)
        val generation = inputData.getString("generation") ?: return Result.success()
        val late = inputData.getBoolean("late", false)
        val planned = inputData.getString("scheduled")
        if (!late && planned == null) return Result.success()
        var delivered = false
        val lock = if (rule.provider == Providers.CODEX) AccountAccess.codex else AccountAccess.claude
        try { lock.withLock {
            if (!late && prefs.getString("scheduled.${rule.key}", null) != planned) return@withLock
            if (!prefs.getBoolean("enabled", false) || !prefs.getBoolean("rule.${rule.key}", true) ||
                prefs.getString("generation.${rule.provider}", null) != generation || !ResetAlerts.permitted(applicationContext)) return@withLock
            val store = SecureStore(applicationContext, if (rule.provider == Providers.CODEX) "account" else "claude")
            val connected = if (rule.provider == Providers.CODEX) store.readTokens() != null else store.readClaude() != null
            val usage = if (connected) store.readRecord().usage else null
            val now = Instant.now().epochSecond
            if (!ResetAlerts.valid(rule, usage, target, now)) return@withLock
            if (prefs.getString("delivered.${rule.key}", null) == "$generation:$target") { delivered = true; return@withLock }
            ResetAlerts.channels(applicationContext)
            val intent = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("provider", rule.provider)
            val content = PendingIntent.getActivity(applicationContext, rule.key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val remaining = target - now
            val message = if (remaining > 60) "Laut Anbieter Reset in ${(remaining + 59) / 60} Minuten." else "Das ${rule.title}-Limit wurde zurückgesetzt."
            synchronized(ResetAlerts.deliveryLock) {
                if (prefs.getString("generation.${rule.provider}", null) != generation || !prefs.getBoolean("enabled", false)) return@synchronized
                if (!late && prefs.getString("scheduled.${rule.key}", null) != planned) return@synchronized
                NotificationManagerCompat.from(applicationContext).notify(rule.channel.hashCode(), NotificationCompat.Builder(applicationContext, rule.channel)
                    .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("${rule.provider} · ${rule.title}")
                    .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(content)
                    .setAutoCancel(true).setOnlyAlertOnce(true).build())
                prefs.edit().putString("delivered.${rule.key}", "$generation:$target").apply()
                delivered = true
            }
        } } finally {
            synchronized(ResetAlerts.deliveryLock) {
                if (!late && !delivered && prefs.getString("scheduled.${rule.key}", null) == planned) prefs.edit().remove("scheduled.${rule.key}").apply()
            }
        }
        return Result.success()
    }
}
