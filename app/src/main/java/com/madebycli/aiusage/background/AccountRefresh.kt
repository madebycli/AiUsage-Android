package com.madebycli.aiusage.background

import android.content.Context
import androidx.work.*
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object AccountRefresh {
    const val NAME = "account-refresh"
    const val INTERVAL_HOURS = 3L
    private val retiredNames = listOf("daily-account-measurement", "four-hour-account-refresh")

    fun request() = PeriodicWorkRequestBuilder<AccountRefreshWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
        .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()

    fun enabled(context: Context) = context.getSharedPreferences("alerts", 0).getBoolean("daily", true)

    fun sync(context: Context, connected: Boolean) {
        val work = WorkManager.getInstance(context)
        retiredNames.forEach(work::cancelUniqueWork)
        if (!connected || !enabled(context)) { work.cancelUniqueWork(NAME); return }
        // UPDATE applies a changed interval to an already scheduled job without restarting its period.
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request())
    }
}

class AccountRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!AccountRefresh.enabled(applicationContext)) return Result.success()
        val http = HttpTransport()
        var retry = false
        for (provider in Providers.all) {
            val lock = if (provider == Providers.CODEX) AccountAccess.codex else AccountAccess.claude
            try {
                lock.withLock {
                    val store = SecureStore(applicationContext, if (provider == Providers.CODEX) "account" else "claude")
                    val snapshot = UsageReader.read(applicationContext, http, provider, store) ?: return@withLock
                    store.saveMeasurement(snapshot, provider)
                    ResetAlerts.schedule(applicationContext, provider, snapshot)
                    AccountAccess.changes.tryEmit(provider)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A rejected login will not recover by retrying; everything else might.
                retry = retry || e !is HttpFailure || e.status !in listOf(400, 401, 403)
            }
        }
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
