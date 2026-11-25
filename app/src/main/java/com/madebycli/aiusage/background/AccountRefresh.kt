package com.madebycli.aiusage.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import com.madebycli.aiusage.data.*
import com.madebycli.aiusage.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.TimeUnit

/** What started a read. Shown in the background log. */
enum class SyncTrigger { PERIODIC, CYCLE_END, AFTER_RESET, APP, WIDGET, BOOT, RETRY }

/** Reads accounts in the background and stores what they report. Shared by every background path. */
object AccountSync {
    /** Reads [ids] (every account when null) one after another. Returns whether trying again later could help. */
    suspend fun run(context: Context, ids: Set<String>? = null, trigger: SyncTrigger = SyncTrigger.PERIODIC): Boolean {
        val started = System.currentTimeMillis()
        val http = HttpTransport()
        var retry = false
        val results = mutableListOf<BackgroundLog.Result>()
        for (account in AccountRegistry(context).list()) {
            if (ids != null && account.id !in ids) continue
            try {
                AccountAccess.lock(account.id).withLock {
                    val store = SecureStore(context, account.namespace)
                    val snapshot = UsageReader.read(context, http, account, store) ?: run {
                        results += BackgroundLog.Result(account.id, false, "no-login"); return@withLock
                    }
                    val (_, events) = store.saveMeasurementEvents(snapshot, account.provider)
                    ResetAlerts.onLedgerEvents(context, account, events, snapshot)
                    ResetAlerts.schedule(context, account, snapshot)
                    AccountAccess.changes.tryEmit(account.id)
                    results += BackgroundLog.Result(account.id, true, null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                results += BackgroundLog.Result(account.id, false, BackgroundLog.reason(e))
                // A rejected login will not recover by retrying; everything else might.
                retry = retry || e !is HttpFailure || e.status !in listOf(400, 401, 403)
            }
        }
        BackgroundLog.add(context, BackgroundLog.Entry(Instant.now().epochSecond, trigger, System.currentTimeMillis() - started, results))
        ResetTicks.schedule(context)
        WidgetUpdater.updateAll(context)
        return retry
    }
}

object AccountRefresh {
    const val NAME = "account-refresh"
    const val DEFAULT_MINUTES = 60L
    /** At most five hours apart, so every 5-hour cycle is seen at least once. */
    val choices = listOf(15L, 30L, 60L, 120L, 180L, 300L)
    private val retiredNames = listOf("daily-account-measurement", "four-hour-account-refresh")

    fun minutes(context: Context): Long {
        val prefs = context.getSharedPreferences("alerts", 0)
        if (prefs.contains("minutes")) return prefs.getInt("minutes", DEFAULT_MINUTES.toInt()).toLong().takeIf { it in choices } ?: DEFAULT_MINUTES
        // 0.9 stored hours; keep a deliberate choice, the old default becomes the new one.
        val hours = prefs.getInt("hours", 0).toLong()
        return (hours * 60).takeIf { hours in 1..2 } ?: DEFAULT_MINUTES
    }

    fun setMinutes(context: Context, minutes: Long) {
        context.getSharedPreferences("alerts", 0).edit().putInt("minutes", minutes.toInt()).apply()
    }

    fun request(minutes: Long = DEFAULT_MINUTES) = PeriodicWorkRequestBuilder<AccountRefreshWorker>(minutes, TimeUnit.MINUTES)
        .setInitialDelay(minOf(minutes, 15L), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()

    fun enabled(context: Context) = context.getSharedPreferences("alerts", 0).getBoolean("daily", true)

    fun sync(context: Context, connected: Boolean) {
        val work = WorkManager.getInstance(context)
        retiredNames.forEach(work::cancelUniqueWork)
        if (!connected || !enabled(context)) { work.cancelUniqueWork(NAME); ResetTicks.cancel(context); return }
        // UPDATE applies a changed interval to an already scheduled job without restarting its period.
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request(minutes(context)))
    }
}

class AccountRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AccountRefresh.enabled(applicationContext)) return Result.success()
        val retry = AccountSync.run(applicationContext, trigger = if (runAttemptCount > 0) SyncTrigger.RETRY else SyncTrigger.PERIODIC)
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

/**
 * Reads the accounts whose window ends next just before and just after that moment. The read before the end
 * keeps the final value of the cycle, which the statistics would otherwise miss. The read after it shows the
 * new window on widgets. Both go through AlarmManager: delayed WorkManager jobs are held back in Doze and often
 * ran only after the reset, when the final value was already gone.
 */
object ResetTicks {
    /** Lead time with an exact alarm, and with an inexact one that Android may move a little. */
    const val LEAD_SECONDS = 180L
    const val INEXACT_LEAD_SECONDS = 600L
    const val LAG_SECONDS = 60L
    private const val ACTION = "com.madebycli.aiusage.MEASURE"

    private fun intent(context: Context, slot: String) =
        Intent(context, MeasureAlarmReceiver::class.java).setAction(ACTION).setData(Uri.parse("aiusage://measure/$slot"))

    /** The next window end of every account, nearest first. */
    fun upcoming(context: Context, now: Long = Instant.now().epochSecond): List<Pair<String, Long>> =
        AccountRegistry(context).list().mapNotNull { account ->
            val usage = runCatching { SecureStore(context, account.namespace).readRecord().usage }.getOrNull() ?: return@mapNotNull null
            usage.windows(account.provider).filter { it.usedPercent > 0 }.mapNotNull { it.resetAt }.filter { it > now + 10 }.minOrNull()?.let { account.id to it }
        }.sortedBy { it.second }

    fun schedule(context: Context) {
        val now = Instant.now().epochSecond
        val upcoming = upcoming(context, now)
        val prefs = context.getSharedPreferences("alerts", 0)
        if (!AccountRefresh.enabled(context)) { cancel(context); return }
        val next = upcoming.firstOrNull()?.second
        if (next == null) { cancel(context); return }
        val ids = upcoming.filter { it.second - next <= 90 }.joinToString(",") { it.first }
        val exact = ResetAlarms.canScheduleExact(context)
        val before = next - if (exact) LEAD_SECONDS else INEXACT_LEAD_SECONDS
        if (before > now + 5) arm(context, "before", before, ids, SyncTrigger.CYCLE_END, exact) else ResetAlarms.cancelIntent(context, intent(context, "before"))
        arm(context, "after", next + LAG_SECONDS, ids, SyncTrigger.AFTER_RESET, exact)
        prefs.edit().putLong("tick.target", next).putLong("tick.before", before).putBoolean("tick.exact", exact).apply()
    }

    private fun arm(context: Context, slot: String, at: Long, ids: String, trigger: SyncTrigger, exact: Boolean) {
        val pending = PendingIntent.getBroadcast(context, 1, intent(context, slot).putExtra("ids", ids).putExtra("trigger", trigger.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (exact) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pending); return }
            catch (_: SecurityException) { /* permission revoked meanwhile */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pending)
    }

    fun cancel(context: Context) {
        listOf("before", "after").forEach { ResetAlarms.cancelIntent(context, intent(context, it)) }
        context.getSharedPreferences("alerts", 0).edit().remove("tick.target").remove("tick.before").apply()
    }
}

/** Runs the read right inside the alarm, while Android lets the app use the network; falls back to expedited work. */
class MeasureAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val ids = intent.getStringExtra("ids")?.split(',')?.filter { it.isNotBlank() }?.toSet()
        val trigger = runCatching { SyncTrigger.valueOf(intent.getStringExtra("trigger").orEmpty()) }.getOrDefault(SyncTrigger.CYCLE_END)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(BUDGET_MS) { AccountSync.run(app, ids, trigger) } }
            catch (e: CancellationException) { MeasureWork.enqueue(app, ids, trigger) }
            catch (_: Exception) { MeasureWork.enqueue(app, ids, trigger) }
            finally { pending.finish() }
        }
    }

    private companion object { const val BUDGET_MS = 25_000L }
}

object MeasureWork {
    fun enqueue(context: Context, ids: Set<String>?, trigger: SyncTrigger) {
        val request = OneTimeWorkRequestBuilder<MeasureWorker>()
            .setInputData(workDataOf("ids" to ids?.toTypedArray(), "trigger" to trigger.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("measure-${trigger.name}", ExistingWorkPolicy.REPLACE, request)
    }
}

class MeasureWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val trigger = runCatching { SyncTrigger.valueOf(inputData.getString("trigger").orEmpty()) }.getOrDefault(SyncTrigger.CYCLE_END)
        AccountSync.run(applicationContext, inputData.getStringArray("ids")?.toSet(), trigger)
        return Result.success()
    }
}

/** 0.9 scheduled ticks as delayed work; ones still pending after the update run once as a normal read. */
class ResetTickWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result { AccountSync.run(applicationContext, inputData.getStringArray("ids")?.toSet(), SyncTrigger.CYCLE_END); return Result.success() }
}
