package com.madebycli.aiusage.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.data.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class AlertRule(val provider: String, val seconds: Long, val title: String) {
    val key get() = "${if (provider == Providers.CODEX) "codex" else "claude"}.$seconds"
    val channel get() = "reset.$key"
}

/** What to do when a reminder fires, given the provider's newest report. */
enum class Decision {
    /** Ahead of the reset and the provider still reports this deadline: send the heads-up. */
    ANNOUNCE,
    /** The provider reports a different deadline than planned: plan again instead of sending. */
    RESCHEDULE,
    /** The window is gone or reports a later deadline: the reset happened. */
    RESET,
    /** The reset time passed but the provider still reports the old deadline: look again shortly. */
    RECHECK,
    /** The provider kept reporting the old deadline for too long: send the reminder anyway. */
    OVERDUE,
    /** No fresh report is available: send what the provider announced earlier. */
    UNVERIFIED
}

object ResetAlerts {
    /** Providers report reset times with sub-second jitter; closer times count as the same reset. */
    const val TOLERANCE_SECONDS = 120L
    /** A reminder is still useful for this long after the reset it announces. */
    const val GRACE_SECONDS = 6 * 3600L
    /** How long to wait for a provider that still reports the old deadline after the reset time. */
    const val RECHECK_SECONDS = 15 * 60L
    const val RECHECK_DELAY_SECONDS = 120L

    internal val deliveryLock = Any()
    val rules = Providers.all.flatMap { provider ->
        listOf(AlertRule(provider, FIVE_HOURS, "5 Stunden"), AlertRule(provider, ONE_WEEK, "Wöchentlich"))
    }

    fun prefs(context: Context) = context.getSharedPreferences("alerts", 0)
    fun permitted(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        rules.forEach { manager.createNotificationChannel(NotificationChannel(it.channel, "${it.provider} · ${it.title}", NotificationManager.IMPORTANCE_HIGH)) }
    }

    fun invalidate(context: Context, provider: String): Unit = synchronized(deliveryLock) {
        prefs(context).edit().putString("generation.$provider", UUID.randomUUID().toString()).commit()
        rules.filter { it.provider == provider }.forEach {
            WorkManager.getInstance(context).cancelUniqueWork(it.channel)
            WorkManager.getInstance(context).cancelUniqueWork(lateName(it))
            ResetAlarms.cancel(context, it)
            NotificationManagerCompat.from(context).cancel(it.channel.hashCode())
            prefs(context).edit().remove("scheduled.${it.key}").remove("target.${it.key}").remove("exact.${it.key}").apply()
        }
    }

    internal fun lateName(rule: AlertRule) = rule.channel + ".late"

    /** Pure decision for a firing reminder; see [Decision]. */
    fun decide(rule: AlertRule, snapshot: UsageSnapshot?, fresh: Boolean, target: Long, now: Long): Decision {
        val reported = snapshot?.windows(rule.provider)?.firstOrNull { it.seconds == rule.seconds }?.resetAt
        val sameDeadline = reported != null && abs(reported - target) <= TOLERANCE_SECONDS
        if (now < target - 60) return if (!fresh || sameDeadline) Decision.ANNOUNCE else Decision.RESCHEDULE
        if (!fresh) return Decision.UNVERIFIED
        val window = snapshot?.windows(rule.provider)?.firstOrNull { it.seconds == rule.seconds }
        if (window == null || reported == null || reported > target + TOLERANCE_SECONDS) return Decision.RESET
        return if (now - target < RECHECK_SECONDS) Decision.RECHECK else Decision.OVERDUE
    }

    /** Epoch second at which the next reminder for [rule] is due, from the stored plan. */
    fun nextReminder(context: Context, rule: AlertRule): Long? {
        val plan = prefs(context).getString("scheduled.${rule.key}", null)?.split(':')?.takeIf { it.size == 3 } ?: return null
        val reset = plan[1].toLongOrNull() ?: return null
        val at = reset - (plan[2].toLongOrNull() ?: 0) * 60
        return at.takeIf { it > Instant.now().epochSecond }
    }

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
            val sameReset = previous > 0 && reset != null && abs(reset - previous) <= TOLERANCE_SECONDS
            val undelivered = prefs.getString("delivered.${rule.key}", null) != "$generation:$previous"
            // The announced reset passed while its reminder was still pending: deliver it now instead of dropping it.
            if (previous in (now - GRACE_SECONDS)..now && !sameReset && undelivered) enqueueFallback(context,
                Delivery(rule, previous, generation, null, late = true))
            val eligible = prefs.getBoolean("enabled", false) && prefs.getBoolean("rule.${rule.key}", true) && permitted(context) &&
                reset != null && reset > now && (!prefs.getBoolean("usedOnly", true) || (window?.usedPercent ?: 0.0) > 0)
            if (!eligible || reset == null) {
                val waiting = previous in (now - GRACE_SECONDS)..now && undelivered
                if (!waiting) { work.cancelUniqueWork(rule.channel); ResetAlarms.cancel(context, rule) }
                prefs.edit().remove("scheduled.${rule.key}").also { if (previous < now) it.remove("target.${rule.key}") }.apply()
                return@forEach
            }
            val exactNow = ResetAlarms.canScheduleExact(context)
            val scheduled = prefs.getString("scheduled.${rule.key}", null)?.split(':')
            val unchanged = scheduled?.size == 3 && scheduled[0] == generation && scheduled[2] == lead.toString() &&
                scheduled[1].toLongOrNull()?.let { abs(it - reset) <= TOLERANCE_SECONDS } == true
            if (unchanged && ResetAlarms.isArmed(context, rule) && prefs.getBoolean("exact.${rule.key}", false) == exactNow) return@forEach
            val signature = if (unchanged) scheduled!!.joinToString(":") else "$generation:$reset:$lead"
            val delivery = Delivery(rule, reset, generation, signature, late = false)
            val at = reset - lead * 60L
            val exact = ResetAlarms.arm(context, rule, at, delivery)
            work.enqueueUniqueWork(rule.channel, ExistingWorkPolicy.REPLACE, workRequest(delivery, (at - now).coerceAtLeast(0)))
            prefs.edit().putString("scheduled.${rule.key}", signature).putLong("target.${rule.key}", reset).putBoolean("exact.${rule.key}", exact).apply()
        }
    }

    /** Restores alarms from the stored plan after a reboot or an app update. */
    fun rearm(context: Context) {
        val prefs = prefs(context)
        val now = Instant.now().epochSecond
        rules.forEach { rule ->
            val generation = prefs.getString("generation.${rule.provider}", null) ?: return@forEach
            val plan = prefs.getString("scheduled.${rule.key}", null)?.split(':')?.takeIf { it.size == 3 } ?: return@forEach
            val reset = plan[1].toLongOrNull() ?: return@forEach
            val at = reset - (plan[2].toLongOrNull() ?: 0) * 60
            val delivery = Delivery(rule, reset, generation, plan.joinToString(":"), late = false)
            if (at > now) prefs.edit().putBoolean("exact.${rule.key}", ResetAlarms.arm(context, rule, at, delivery)).apply()
            else if (now - reset <= GRACE_SECONDS && prefs.getString("delivered.${rule.key}", null) != "$generation:$reset")
                enqueueFallback(context, delivery.copy(late = true))
        }
    }

    internal fun workRequest(delivery: Delivery, delaySeconds: Long): OneTimeWorkRequest = OneTimeWorkRequestBuilder<ResetAlertWorker>()
        .setInputData(workDataOf("rule" to delivery.rule.key, "reset" to delivery.target, "generation" to delivery.generation,
            "scheduled" to delivery.planned, "late" to delivery.late))
        .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        .setConstraints(Constraints.NONE).build()

    /** Runs the delivery through WorkManager, for when the alarm path could not finish. */
    fun enqueueFallback(context: Context, delivery: Delivery) {
        WorkManager.getInstance(context).enqueueUniqueWork(lateName(delivery.rule), ExistingWorkPolicy.KEEP, workRequest(delivery, 0))
    }
}
