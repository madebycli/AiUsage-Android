package com.madebycli.aiusage.background

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.madebycli.aiusage.MainActivity
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A planned reminder: which window, which reset, and the plan it belongs to. */
data class Delivery(val rule: AlertRule, val target: Long, val generation: String, val planned: String?, val late: Boolean)

object ResetNotifier {
    private const val FETCH_BUDGET_MS = 8_000L

    /**
     * Sends the reminder for [delivery] once. Before announcing a reset it asks the provider for fresh
     * numbers, so the text matches what the app shows when it is opened. Returns whether it was handled.
     */
    suspend fun deliver(context: Context, delivery: Delivery): Boolean {
        val rule = delivery.rule
        val prefs = ResetAlerts.prefs(context)
        val lock = if (rule.provider == Providers.CODEX) AccountAccess.codex else AccountAccess.claude
        return lock.withLock {
            if (!stillPlanned(context, delivery)) return@withLock false
            if (prefs.getString("delivered.${rule.key}", null) == "${delivery.generation}:${delivery.target}") return@withLock true
            val store = SecureStore(context, if (rule.provider == Providers.CODEX) "account" else "claude")
            val connected = if (rule.provider == Providers.CODEX) store.readTokens() != null else store.readClaude() != null
            if (!connected) return@withLock false
            val now = Instant.now().epochSecond
            if (now - delivery.target > ResetAlerts.GRACE_SECONDS) return@withLock false

            val fresh = withTimeoutOrNull(FETCH_BUDGET_MS) { runCatching { UsageReader.read(context, HttpTransport(), rule.provider, store) }.getOrNull() }
            fresh?.let {
                runCatching { store.saveMeasurement(it, rule.provider) }
                AccountAccess.changes.tryEmit(rule.provider)
            }
            val snapshot = fresh ?: runCatching { store.readRecord().usage }.getOrNull()
            val after = Instant.now().epochSecond
            when (ResetAlerts.decide(rule, snapshot, fresh != null, delivery.target, after)) {
                Decision.RESCHEDULE -> { fresh?.let { ResetAlerts.schedule(context, rule.provider, it) }; false }
                Decision.RECHECK -> {
                    ResetAlarms.arm(context, rule, after + ResetAlerts.RECHECK_DELAY_SECONDS, delivery)
                    false
                }
                Decision.ANNOUNCE -> post(context, delivery, "Laut Anbieter Reset in ${(delivery.target - after + 59) / 60} Minuten.", fresh)
                Decision.RESET -> post(context, delivery, resetText(rule, snapshot), fresh)
                Decision.OVERDUE -> post(context, delivery, "Der Reset-Zeitpunkt ist erreicht. Der Anbieter meldet noch den alten Termin.", fresh)
                Decision.UNVERIFIED -> post(context, delivery, "Der gemeldete Reset-Zeitpunkt ist erreicht. Verbrauch in der App prüfen.", null)
            }
        }
    }

    private fun stillPlanned(context: Context, delivery: Delivery): Boolean {
        val prefs = ResetAlerts.prefs(context)
        val rule = delivery.rule
        if (!delivery.late && prefs.getString("scheduled.${rule.key}", null) != delivery.planned) return false
        return prefs.getBoolean("enabled", false) && prefs.getBoolean("rule.${rule.key}", true) &&
            prefs.getString("generation.${rule.provider}", null) == delivery.generation && ResetAlerts.permitted(context)
    }

    private fun resetText(rule: AlertRule, snapshot: UsageSnapshot?): String {
        val next = snapshot?.windows(rule.provider)?.firstOrNull { it.seconds == rule.seconds }?.resetAt
        val pattern = if (rule.seconds >= ONE_WEEK) "dd.MM. HH:mm" else "HH:mm"
        val until = next?.let { DateTimeFormatter.ofPattern(pattern, Locale.GERMAN).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it)) }
        return "Das ${rule.title}-Limit ist zurückgesetzt." + if (until != null) " Das neue Fenster läuft bis $until." else " Das neue Fenster startet mit der nächsten Nutzung."
    }

    @SuppressLint("MissingPermission") // The runtime permission is checked in stillPlanned.
    private fun post(context: Context, delivery: Delivery, message: String, fresh: UsageSnapshot?): Boolean {
        val rule = delivery.rule
        val prefs = ResetAlerts.prefs(context)
        var delivered = false
        synchronized(ResetAlerts.deliveryLock) {
            if (!stillPlanned(context, delivery)) return@synchronized
            ResetAlerts.channels(context)
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("provider", rule.provider)
            val content = PendingIntent.getActivity(context, rule.key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationManagerCompat.from(context).notify(rule.channel.hashCode(), NotificationCompat.Builder(context, rule.channel)
                .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("${rule.provider} · ${rule.title}")
                .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
            prefs.edit().putString("delivered.${rule.key}", "${delivery.generation}:${delivery.target}").apply()
            delivered = true
        }
        // With the delivery recorded, plan the next window from the fresh numbers.
        if (delivered && fresh != null) ResetAlerts.schedule(context, rule.provider, fresh)
        return delivered
    }

    @SuppressLint("MissingPermission")
    fun sendTest(context: Context) {
        if (!ResetAlerts.permitted(context)) return
        val rule = ResetAlerts.rules.first()
        ResetAlerts.channels(context)
        NotificationManagerCompat.from(context).notify("test".hashCode(), NotificationCompat.Builder(context, rule.channel)
            .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("AI Usage · Test")
            .setContentText("So sehen Reset-Hinweise aus.").setAutoCancel(true).build())
    }
}

/** Fallback delivery path: runs when the alarm could not finish, and for reminders found overdue. */
class ResetAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val rule = ResetAlerts.rules.firstOrNull { it.key == inputData.getString("rule") } ?: return Result.success()
        val generation = inputData.getString("generation") ?: return Result.success()
        val late = inputData.getBoolean("late", false)
        val planned = inputData.getString("scheduled")
        if (!late && planned == null) return Result.success()
        ResetNotifier.deliver(applicationContext, Delivery(rule, inputData.getLong("reset", 0), generation, planned, late))
        return Result.success()
    }
}
