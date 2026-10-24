package com.madebycli.aiusage.background

import android.content.Context
import androidx.work.*
import com.madebycli.aiusage.data.*
import com.madebycli.aiusage.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Reads accounts in the background and stores what they report. Shared by every background path. */
object AccountSync {
    /** Reads [ids] (every account when null) one after another. Returns whether trying again later could help. */
    suspend fun run(context: Context, ids: Set<String>? = null): Boolean {
        val http = HttpTransport()
        var retry = false
        var failures = 0
        var reads = 0
        for (account in AccountRegistry(context).list()) {
            if (ids != null && account.id !in ids) continue
            try {
                AccountAccess.lock(account.id).withLock {
                    val store = SecureStore(context, account.namespace)
                    val snapshot = UsageReader.read(context, http, account, store) ?: return@withLock
                    reads++
                    val (_, events) = store.saveMeasurementEvents(snapshot, account.provider)
                    ResetAlerts.onLedgerEvents(context, account, events, snapshot)
                    ResetAlerts.schedule(context, account, snapshot)
                    AccountAccess.changes.tryEmit(account.id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failures++
                // A rejected login will not recover by retrying; everything else might.
                retry = retry || e !is HttpFailure || e.status !in listOf(400, 401, 403)
            }
        }
        // What the settings page shows as the state of the background reads.
        context.getSharedPreferences("alerts", 0).edit().putLong("bg.last", Instant.now().epochSecond).putInt("bg.reads", reads).putInt("bg.failures", failures).apply()
        ResetTicks.schedule(context)
        WidgetUpdater.updateAll(context)
        return retry
    }
}

object AccountRefresh {
    const val NAME = "account-refresh"
    const val DEFAULT_HOURS = 3L
    val choices = listOf(1L, 2L, 3L, 6L, 12L)
    private val retiredNames = listOf("daily-account-measurement", "four-hour-account-refresh")

    fun hours(context: Context): Long = context.getSharedPreferences("alerts", 0).getInt("hours", DEFAULT_HOURS.toInt()).toLong().takeIf { it in choices } ?: DEFAULT_HOURS

    fun request(hours: Long = DEFAULT_HOURS) = PeriodicWorkRequestBuilder<AccountRefreshWorker>(hours, TimeUnit.HOURS)
        .setInitialDelay(hours, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()

    fun enabled(context: Context) = context.getSharedPreferences("alerts", 0).getBoolean("daily", true)

    fun sync(context: Context, connected: Boolean) {
        val work = WorkManager.getInstance(context)
        retiredNames.forEach(work::cancelUniqueWork)
        if (!connected || !enabled(context)) { work.cancelUniqueWork(NAME); return }
        // UPDATE applies a changed interval to an already scheduled job without restarting its period.
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request(hours(context)))
    }
}

class AccountRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AccountRefresh.enabled(applicationContext)) return Result.success()
        val retry = AccountSync.run(applicationContext)
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

/**
 * Reads the account whose limit resets next just before and just after that moment. The read before the
 * reset catches the final value of the run, which the usage tracker would otherwise miss when nobody
 * opened the app in the last hours. The read after it shows the new window on widgets.
 */
object ResetTicks {
    const val LEAD_SECONDS = 150L
    const val LAG_SECONDS = 40L
    private fun before(target: Long) = "reset-tick-before-$target"
    private fun after(target: Long) = "reset-tick-after-$target"

    fun schedule(context: Context) {
        val now = Instant.now().epochSecond
        val prefs = context.getSharedPreferences("alerts", 0)
        val upcoming = AccountRegistry(context).list().mapNotNull { account ->
            val usage = runCatching { SecureStore(context, account.namespace).readRecord().usage }.getOrNull() ?: return@mapNotNull null
            usage.windows(account.provider).mapNotNull { it.resetAt }.filter { it > now + 10 }.minOrNull()?.let { account.id to it }
        }
        val work = WorkManager.getInstance(context)
        val old = prefs.getLong("tick.target", 0)
        val next = upcoming.minOfOrNull { it.second }
        // A tick that already fired may be the running worker itself; only pending ones are cancelled.
        if (old > now && old != next) { work.cancelUniqueWork(before(old)); work.cancelUniqueWork(after(old)) }
        if (next == null) { prefs.edit().remove("tick.target").apply(); return }
        val ids = upcoming.filter { it.second - next <= 90 }.map { it.first }.toTypedArray()
        fun request(delay: Long) = OneTimeWorkRequestBuilder<ResetTickWorker>().setInputData(workDataOf("ids" to ids))
            .setInitialDelay(delay.coerceAtLeast(5), TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        work.enqueueUniqueWork(before(next), ExistingWorkPolicy.KEEP, request(next - LEAD_SECONDS - now))
        work.enqueueUniqueWork(after(next), ExistingWorkPolicy.KEEP, request(next + LAG_SECONDS - now))
        prefs.edit().putLong("tick.target", next).apply()
    }
}

class ResetTickWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AccountSync.run(applicationContext, inputData.getStringArray("ids")?.toSet())
        return Result.success()
    }
}
