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
data class Delivery(val account: Account, val rule: AlertRule, val target: Long, val generation: String, val planned: String?, val late: Boolean) {
    val slot get() = rule.slot(account)
}

object ResetNotifier {
    private const val FETCH_BUDGET_MS = 8_000L
    private const val MIN_GAP_SECONDS = 10 * 60L

    /**
     * Sends the reminder for [delivery] once. Before announcing a reset it asks the provider for fresh
     * numbers, so the text matches what the app shows when it is opened. Returns whether it was handled.
     */
    suspend fun deliver(context: Context, delivery: Delivery): Boolean {
        val rule = delivery.rule
        val account = delivery.account
        val prefs = ResetAlerts.prefs(context)
        return AccountAccess.lock(account.id).withLock {
            if (!stillPlanned(context, delivery)) return@withLock false
            if (prefs.getString("delivered.${delivery.slot}", null) == "${delivery.generation}:${delivery.target}") return@withLock true
            val store = SecureStore(context, account.namespace)
            if (!store.hasCredentials()) return@withLock false
            val now = Instant.now().epochSecond
            if (now - delivery.target > ResetAlerts.GRACE_SECONDS) return@withLock false

            val fresh = withTimeoutOrNull(FETCH_BUDGET_MS) { runCatching { UsageReader.read(context, HttpTransport(), account, store) }.getOrNull() }
            fresh?.let {
                runCatching { store.saveMeasurementEvents(it, rule.provider) }.getOrNull()?.let { (_, events) -> ResetAlerts.onLedgerEvents(context, account, events, it) }
                AccountAccess.changes.tryEmit(account.id)
            }
            val snapshot = fresh ?: runCatching { store.readRecord().usage }.getOrNull()
            val after = Instant.now().epochSecond
            when (ResetAlerts.decide(rule, snapshot, fresh != null, delivery.target, after)) {
                Decision.RESCHEDULE -> { fresh?.let { ResetAlerts.schedule(context, account, it) }; false }
                Decision.RECHECK -> {
                    ResetAlarms.arm(context, delivery.slot, after + ResetAlerts.RECHECK_DELAY_SECONDS, delivery)
                    false
                }
                Decision.ANNOUNCE -> post(context, delivery, com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_announce, rule.adjective, Fmt.clock(context.resources, delivery.target, after)) + " (" + Fmt.remaining(context.resources, delivery.target - after) + ")", fresh)
                Decision.RESET -> post(context, delivery, resetText(context, rule, snapshot), fresh)
                Decision.OVERDUE -> post(context, delivery, com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_overdue), fresh)
                Decision.UNVERIFIED -> post(context, delivery, com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_unverified), null)
            }
        }
    }

    private fun stillPlanned(context: Context, delivery: Delivery): Boolean {
        val prefs = ResetAlerts.prefs(context)
        val rule = delivery.rule
        if (!delivery.late && prefs.getString("scheduled.${delivery.slot}", null) != delivery.planned) return false
        return prefs.getBoolean("enabled", false) && prefs.getBoolean("rule.${rule.key}", true) &&
            prefs.getString("generation.${delivery.account.id}", null) == delivery.generation && ResetAlerts.permitted(context)
    }

    private fun resetText(context: Context, rule: AlertRule, snapshot: UsageSnapshot?): String {
        val next = snapshot?.windows(rule.provider)?.firstOrNull { it.seconds == rule.seconds }?.resetAt
        val now = Instant.now().epochSecond
        return com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_reset, rule.adjective) + (next?.let { " " + com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_until, Fmt.clock(context.resources, it, now)) } ?: "")
    }

    @SuppressLint("MissingPermission") // The runtime permission is checked in stillPlanned.
    private fun post(context: Context, delivery: Delivery, message: String, fresh: UsageSnapshot?): Boolean {
        val rule = delivery.rule
        val prefs = ResetAlerts.prefs(context)
        var delivered = false
        synchronized(ResetAlerts.deliveryLock) {
            if (!stillPlanned(context, delivery)) return@synchronized
            // A second notification for the same window within minutes is noise, whatever path asked for it.
            val sinceLast = Instant.now().epochSecond - prefs.getLong("lastPost.${delivery.slot}", 0)
            if (sinceLast in 0 until MIN_GAP_SECONDS) return@synchronized
            ResetAlerts.channels(context)
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("account", delivery.account.id)
            val content = PendingIntent.getActivity(context, delivery.slot.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val everyone = AccountRegistry(context).list()
            NotificationManagerCompat.from(context).notify(delivery.slot.hashCode(), NotificationCompat.Builder(context, rule.channel)
                .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("${displayName(delivery.account, everyone)} · ${rule.title}")
                .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
            prefs.edit().putString("delivered.${delivery.slot}", "${delivery.generation}:${delivery.target}").putLong("lastPost.${delivery.slot}", Instant.now().epochSecond).apply()
            delivered = true
        }
        // With the delivery recorded, plan the next window from the fresh numbers.
        if (delivered && fresh != null) ResetAlerts.schedule(context, delivery.account, fresh)
        return delivered
    }

    /**
     * "Your limit was reset earlier than planned." Several accounts of one provider reset together in a global reset, so they share
     * one notification that names all of them instead of one per account.
     */
    @SuppressLint("MissingPermission")
    fun postEarlyReset(context: Context, account: Account, rule: AlertRule, event: LedgerEvent, window: UsageWindow?) {
        val prefs = ResetAlerts.prefs(context)
        val now = Instant.now().epochSecond
        val everyone = AccountRegistry(context).list()
        val name = displayName(account, everyone)
        val key = "spontNames.${rule.key}"
        val recent = (prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { entry ->
            entry.substringBefore('|').toLongOrNull()?.takeIf { now - it < SPONTANEOUS_JOIN_SECONDS }?.let { entry.substringAfter('|') }
        } + name).distinct()
        prefs.edit().putStringSet(key, recent.map { "$now|$it" }.toSet()).apply()
        val until = window?.resetAt?.takeIf { it > now }?.let { " " + com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_until, Fmt.clock(context.resources, it, now)) }.orEmpty()
        val used = com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_used_before, Fmt.percent(event.peak))
        val message = if (recent.size == 1) com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_early_one, rule.adjective) + " " + used + until
            else com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_early_many, rule.adjective, recent.joinToString(", ")) + until
        synchronized(ResetAlerts.deliveryLock) {
            ResetAlerts.channels(context)
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("account", account.id)
            val content = PendingIntent.getActivity(context, "spont.${rule.key}".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationManagerCompat.from(context).notify("spont.${rule.key}".hashCode(), NotificationCompat.Builder(context, rule.channel)
                .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_early_title, rule.provider))
                .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message)).setContentIntent(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
        }
    }

    private const val SPONTANEOUS_JOIN_SECONDS = 30 * 60L

    @SuppressLint("MissingPermission")
    fun sendTest(context: Context) {
        if (!ResetAlerts.permitted(context)) return
        val rule = ResetAlerts.rules.first()
        ResetAlerts.channels(context)
        NotificationManagerCompat.from(context).notify("test".hashCode(), NotificationCompat.Builder(context, rule.channel)
            .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle(com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_test_title))
            .setContentText(com.madebycli.aiusage.Texts.get(com.madebycli.aiusage.R.string.notify_test_text)).setAutoCancel(true).build())
    }
}

/** Fallback delivery path: runs when the alarm could not finish, and for reminders found overdue. */
class ResetAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val rule = ResetAlerts.rules.firstOrNull { it.key == inputData.getString("rule") } ?: return Result.success()
        val account = AccountRegistry(applicationContext).find(inputData.getString("account").orEmpty()) ?: return Result.success()
        val generation = inputData.getString("generation") ?: return Result.success()
        val late = inputData.getBoolean("late", false)
        val planned = inputData.getString("scheduled")
        if (!late && planned == null) return Result.success()
        ResetNotifier.deliver(applicationContext, Delivery(account, rule, inputData.getLong("reset", 0), generation, planned, late))
        return Result.success()
    }
}
