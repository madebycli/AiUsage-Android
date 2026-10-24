package com.madebycli.aiusage.account

import android.app.Application
import android.util.Log
import com.madebycli.aiusage.AccountState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.background.ResetAlerts
import com.madebycli.aiusage.background.ResetTicks
import com.madebycli.aiusage.widget.WidgetUpdater
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import java.time.Instant

internal fun MutableStateFlow<UiState>.updateAccount(id: String, change: AccountState.() -> AccountState) =
    update { state -> state.copy(accounts = state.accounts.map { if (it.id == id) it.change() else it }) }

/**
 * Reads, stores and publishes the quota of one account. Every account has its own lock, queue and
 * lifetime, so a slow or failing account never holds up another one.
 */
abstract class AccountRuntime(
    protected val app: Application,
    scope: CoroutineScope,
    protected val state: MutableStateFlow<UiState>,
    val account: Account
) {
    protected val store = SecureStore(app, account.namespace)
    protected val lock = AccountAccess.lock(account.id)
    private val job = SupervisorJob(scope.coroutineContext[Job])
    protected val work = CoroutineScope(scope.coroutineContext + job)
    @Volatile protected var closed = false
    private val queue = RefreshQueue(work) { withContext(Dispatchers.IO) { read() } }
    protected val id get() = account.id
    protected fun update(change: AccountState.() -> AccountState) = state.updateAccount(id, change)
    protected val current get() = state.value.account(id)

    fun refresh() { if (!closed) queue.request() }

    fun refreshIfStale(maxAgeSeconds: Long) {
        val usage = current?.usage
        if (usage == null || Instant.now().epochSecond - usage.fetchedAt > maxAgeSeconds) refresh()
    }

    /** Takes over a measurement that background work stored while the app was open. */
    suspend fun reloadStoredRecord() = lock.withLock {
        if (closed) return@withLock
        runCatching { store.readRecord() }.getOrNull()?.let { record -> update { copy(usage = record.usage ?: usage, ledger = record.ledger) } }
        Unit
    }

    /** Publishes a reading the caller already fetched, for example while signing in. */
    suspend fun adopt(usage: UsageSnapshot) = lock.withLock {
        if (closed) return@withLock
        update { copy(usage = usage, error = null) }
        persist(usage)
    }

    protected abstract suspend fun fetch(): UsageSnapshot
    protected open suspend fun afterRead(usage: UsageSnapshot) {}
    protected abstract fun failure(error: Exception): String

    private suspend fun read(): Unit = lock.withLock {
        if (closed) return@withLock
        update { copy(refreshing = true) }
        try {
            val usage = fetch()
            currentCoroutineContext().ensureActive()
            if (closed) return@withLock
            update { copy(usage = usage, error = null, storageWarning = null) }
            persist(usage)
            afterRead(usage)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, diagnosticFailure("usage_${account.provider.take(5)}", e))
            if (!closed) update { copy(error = failure(e)) }
        } finally {
            if (!closed) update { copy(refreshing = false) }
        }
    }

    /** Stores the reading and everything derived from it. The caller holds the account lock. */
    protected suspend fun persist(usage: UsageSnapshot) {
        persistAccountRecord {
            val (record, events) = store.saveMeasurementEvents(usage, account.provider)
            update { copy(ledger = record.ledger) }
            runCatching { ResetAlerts.onLedgerEvents(app, account, events, usage) }
            ResetAlerts.schedule(app, account, usage)
            ResetTicks.schedule(app)
            WidgetUpdater.requestUpdate(app)
        }?.let { warning -> update { copy(storageWarning = warning) } }
    }

    /** Stops all work. The returned job completes when running work has ended. */
    fun shutdown(): Job {
        closed = true
        queue.cancel()
        job.cancel()
        return job
    }

    /** Deletes the stored credentials and measurements once nothing uses them any more. */
    suspend fun wipe() {
        job.join()
        lock.withLock { store.clear() }
    }

    protected companion object { const val TAG = "AiUsage" }
}

/** Codex: quota read plus the optional details of saved resets. */
class CodexRuntime(app: Application, scope: CoroutineScope, state: MutableStateFlow<UiState>, http: HttpTransport, account: Account) :
    AccountRuntime(app, scope, state, account) {
    private val repository = UsageRepository(CodexAuth(http, store))

    override suspend fun fetch(): UsageSnapshot = repository.fetch()
    override fun failure(error: Exception) = safeError(error)

    override suspend fun afterRead(usage: UsageSnapshot) {
        if ((usage.availableResets ?: 0) <= 0) return
        update { copy(creditsLoading = true) }
        // Details are a bonus: the quota is already on screen, so they are read after this read releases the lock.
        work.launch(Dispatchers.IO) {
            lock.withLock {
                try {
                    if (closed) return@withLock
                    val enriched = repository.fetchCredits(usage)
                    currentCoroutineContext().ensureActive()
                    val latest = current?.usage
                    if (latest != null && latest.fetchedAt != usage.fetchedAt) return@withLock
                    update { copy(usage = enriched) }
                    persist(enriched)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* the count stays; details are optional */ }
                finally { if (!closed) update { copy(creditsLoading = false) } }
            }
        }
    }
}

/** Claude Code: OAuth usage read; a phone sign-in rotates its own refresh token when it expires. */
class ClaudeRuntime(app: Application, scope: CoroutineScope, state: MutableStateFlow<UiState>, http: HttpTransport, account: Account) :
    AccountRuntime(app, scope, state, account) {
    private val auth = ClaudeAuth(http, store)
    private val repository = ClaudeRepository(http)
    private var operation = "claude_auth"

    override suspend fun fetch(): UsageSnapshot {
        operation = "claude_auth"
        val credential = auth.credential()
        operation = "claude_usage"
        val usage = try { repository.fetch(credential) } catch (e: HttpFailure) {
            if (e.status != 401 || !credential.phoneLogin || credential.refresh == null) throw e
            repository.fetch(auth.credential(forceRefresh = true))
        }
        if (usage.warnings.isNotEmpty()) Log.w(TAG, "claude_usage partial warnings=${usage.warnings.size}")
        return usage
    }

    override fun failure(error: Exception): String {
        val unreadableLogin = operation == "claude_auth" &&
            (error is SchemaException || error is kotlinx.serialization.SerializationException || error is IllegalArgumentException)
        return if (unreadableLogin) "Claude-Anmeldung konnte nicht gelesen oder erneuert werden. Bitte erneut verbinden." else safeError(error)
    }
}
