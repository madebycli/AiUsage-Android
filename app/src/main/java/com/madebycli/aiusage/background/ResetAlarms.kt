package com.madebycli.aiusage.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.madebycli.aiusage.data.AccountRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Reset reminders are driven by AlarmManager, which wakes a dozing device. WorkManager jobs with a long
 * delay are held back by Doze and can run hours late, so they only serve as a fallback.
 */
object ResetAlarms {
    private const val ACTION = "com.madebycli.aiusage.RESET_ALARM"

    private fun manager(context: Context) = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager(context).canScheduleExactAlarms()

    private fun intent(context: Context, slot: String) =
        Intent(context, ResetAlarmReceiver::class.java).setAction(ACTION).setData(Uri.parse("aiusage://alarm/$slot"))

    fun isArmed(context: Context, slot: String): Boolean =
        PendingIntent.getBroadcast(context, 0, intent(context, slot), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) != null

    /** Returns whether the alarm fires at the exact time (true) or inside Android's idle window (false). */
    fun arm(context: Context, slot: String, atEpochSeconds: Long, delivery: Delivery): Boolean {
        val pending = PendingIntent.getBroadcast(context, 0,
            intent(context, slot).putExtra("account", delivery.account.id).putExtra("rule", delivery.rule.key).putExtra("reset", delivery.target)
                .putExtra("generation", delivery.generation).putExtra("scheduled", delivery.planned),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = atEpochSeconds * 1000
        val alarms = manager(context)
        if (canScheduleExact(context)) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); return true }
            catch (_: SecurityException) { /* permission revoked meanwhile */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        return false
    }

    fun cancel(context: Context, slot: String) {
        PendingIntent.getBroadcast(context, 0, intent(context, slot), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            manager(context).cancel(it)
            it.cancel()
        }
    }
}

class ResetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val rule = ResetAlerts.rules.firstOrNull { it.key == intent.getStringExtra("rule") } ?: return
        val account = AccountRegistry(app).find(intent.getStringExtra("account").orEmpty()) ?: return
        val delivery = Delivery(account, rule, intent.getLongExtra("reset", 0), intent.getStringExtra("generation") ?: return,
            intent.getStringExtra("scheduled"), late = false)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(RECEIVER_BUDGET_MS) { ResetNotifier.deliver(app, delivery) } }
            catch (_: Exception) { ResetAlerts.enqueueFallback(app, delivery) }
            finally { pending.finish() }
        }
    }

    private companion object { const val RECEIVER_BUDGET_MS = 25_000L }
}

/** Alarms disappear on reboot and on app update; this restores them from the stored plan. */
class ResetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ResetAlerts.rearm(context.applicationContext)
    }
}
