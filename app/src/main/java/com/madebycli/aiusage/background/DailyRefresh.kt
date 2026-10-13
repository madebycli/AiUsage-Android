package com.madebycli.aiusage.background

import android.content.Context
import androidx.work.*
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object DailyRefresh {
    const val NAME = "daily-account-measurement"
    fun sync(context: Context, connected: Boolean) {
        val work = WorkManager.getInstance(context)
        val enabled = context.getSharedPreferences("alerts", 0).getBoolean("daily", true)
        if (!connected || !enabled) { work.cancelUniqueWork(NAME); return }
        val request = PeriodicWorkRequestBuilder<DailyUsageWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class DailyUsageWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!applicationContext.getSharedPreferences("alerts", 0).getBoolean("daily", true)) return Result.success()
        val http = HttpTransport()
        var retry = false
        suspend fun measure(provider: String, action: suspend (SecureStore) -> UsageSnapshot?) {
            val lock = if (provider == "Codex") AccountAccess.codex else AccountAccess.claude
            try {
                lock.withLock {
                    val prefs = applicationContext.getSharedPreferences("alerts", 0)
                    if (!prefs.getBoolean("daily", true)) return@withLock
                    if (prefs.getLong("retry.$provider", 0) > java.time.Instant.now().epochSecond) { retry = true; return@withLock }
                    val store = SecureStore(applicationContext, if (provider == "Codex") "account" else "claude")
                    val snapshot = action(store) ?: return@withLock
                    store.saveMeasurement(snapshot, provider)
                    ResetAlerts.schedule(applicationContext, provider, snapshot)
                    AccountAccess.changes.tryEmit(provider)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is HttpFailure && e.status == 429) applicationContext.getSharedPreferences("alerts", 0).edit()
                    .putLong("retry.$provider", java.time.Instant.now().epochSecond + (e.retrySeconds ?: 1800).coerceIn(1, 604800)).apply()
                // Failed/stale data never produce a measurement or a fake zero.
                retry = retry || e !is HttpFailure || e.status !in listOf(400, 401, 403)
            }
        }
        measure("Codex") { store ->
            if (store.readTokens() == null) null else UsageRepository(CodexAuth(http, store)).fetch()
        }
        measure("Claude Code") { store ->
            if (store.readClaude() == null) null else {
                val auth = ClaudeAuth(http, store)
                val repository = ClaudeRepository(http)
                val credential = auth.credential()
                try { repository.fetch(credential) } catch (e: HttpFailure) {
                    if (e.status != 401 || !credential.phoneLogin || credential.refresh == null) throw e
                    repository.fetch(auth.credential(forceRefresh = true))
                }
            }
        }
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
