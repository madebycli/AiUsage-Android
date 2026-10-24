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
                Decision.ANNOUNCE -> post(context, delivery, "Das ${rule.title}-Limit wird um ${TimeText.clock(delivery.target, after)} Uhr zurückgesetzt (${TimeText.remaining(delivery.target - after)}).", fresh)
                Decision.RESET -> post(context, delivery, resetText(rule, snapshot), fresh)
                Decision.OVERDUE -> post(context, delivery, "Der Reset-Zeitpunkt ist erreicht. Der Anbieter meldet noch den alten Termin.", fresh)
                Decision.UNVERIFIED -> post(context, delivery, "Der gemeldete Reset-Zeitpunkt ist erreicht. Verbrauch in der App prüfen.", null)
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
        val until = window?.resetAt?.takeIf { it > now }?.let { " Das neue Fenster läuft bis ${TimeText.clock(it, now)} Uhr." }.orEmpty()
        val used = "Zuvor waren ${formatPercent(event.peak)} % verbraucht."
        val message = if (recent.size == 1) "Das ${rule.title}-Limit wurde früher als geplant zurückgesetzt. $used$until"
            else "Das ${rule.title}-Limit wurde bei ${recent.joinToString(", ")} früher als geplant zurückgesetzt.$until"
        synchronized(ResetAlerts.deliveryLock) {
            ResetAlerts.channels(context)
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("account", account.id)
            val content = PendingIntent.getActivity(context, "spont.${rule.key}".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            NotificationManagerCompat.from(context).notify("spont.${rule.key}".hashCode(), NotificationCompat.Builder(context, rule.channel)
                .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("${rule.provider} · Reset erkannt")
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
            .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("AI Usage · Test")
            .setContentText("So sehen Reset-Hinweise aus.").setAutoCancel(true).build())
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
