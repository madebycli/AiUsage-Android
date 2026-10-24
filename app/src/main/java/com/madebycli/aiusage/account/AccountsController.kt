package com.madebycli.aiusage.account

import android.app.Application
import com.madebycli.aiusage.AccountState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.background.ResetAlerts
import com.madebycli.aiusage.background.ResetTicks
import com.madebycli.aiusage.widget.WidgetUpdater
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Owns the list of connected accounts, their runtimes and the order shown on the home screen. */
class AccountsController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val http: HttpTransport,
    private val onChanged: () -> Unit
) {
    val registry = AccountRegistry(app)
    private val runtimes = ConcurrentHashMap<String, AccountRuntime>()

    fun load(): Job = scope.launch(Dispatchers.IO) {
        val accounts = registry.list()
        val loaded = accounts.map { account ->
            val read = runCatching { SecureStore(app, account.namespace).readRecord() }
            AccountState(account, displayName(account, accounts), read.getOrNull()?.usage, read.getOrNull()?.ledger ?: UsageLedger(),
                error = if (read.isFailure) "Gespeicherte Messung konnte nicht gelesen werden. Jetzt aktualisieren." else null)
        }
        state.update { it.copy(accounts = loaded, loaded = true) }
        accounts.forEach(::start)
        onChanged()
    }

    private fun start(account: Account) {
        val runtime = if (account.provider == Providers.CODEX) CodexRuntime(app, scope, state, http, account) else ClaudeRuntime(app, scope, state, http, account)
        runtimes.put(account.id, runtime)?.shutdown()
        runtime.refresh()
    }

    fun refreshAll() = runtimes.values.forEach { it.refresh() }
    fun refresh(id: String) { runtimes[id]?.refresh() }
    fun refreshIfStale(maxAgeSeconds: Long) = runtimes.values.forEach { it.refreshIfStale(maxAgeSeconds) }
    suspend fun reload(id: String) { runtimes[id]?.reloadStoredRecord() }

    private fun withTitles(states: List<AccountState>): List<AccountState> {
        val all = states.map { it.account }
        return states.map { it.copy(title = displayName(it.account, all)) }
    }

    /** Called by a finished sign-in: stores the credentials in an account and starts reading it. */
    suspend fun attach(provider: String, staging: SecureStore, identity: String?, email: String?, replaceId: String?, first: UsageSnapshot? = null): Account {
        val existing = registry.find(replaceId.orEmpty())?.takeIf { it.provider == provider } ?: registry.byIdentity(provider, identity)
        val account = existing?.copy(detail = email ?: existing.detail, identity = identity ?: existing.identity)
            ?: AccountRegistry.newAccount(provider, detail = email, identity = identity)
        runtimes.remove(account.id)?.shutdown()?.join()
        AccountAccess.lock(account.id).withLock {
            val target = SecureStore(app, account.namespace)
            staging.moveCredentialsTo(target, provider)
            if (existing == null) target.writeRecord(PersonalRecord())
            ResetAlerts.invalidate(app, account)
        }
        val list = if (existing == null) registry.add(account) else registry.update(account.id) { account }
        state.update { current ->
            val kept = current.accounts.firstOrNull { it.id == account.id }
            val fresh = kept?.copy(account = account, error = null, refreshing = false) ?: AccountState(account, "")
            val merged = if (kept != null) current.accounts.map { if (it.id == account.id) fresh else it } else current.accounts + fresh
            current.copy(accounts = withTitles(merged.sortedBy { s -> list.indexOfFirst { it.id == s.id } }))
        }
        start(account)
        if (first != null) runtimes[account.id]?.adopt(first)
        onChanged()
        return account
    }

    fun rename(id: String, label: String?) {
        val clean = label?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        registry.update(id) { it.copy(label = clean) }
        state.update { current -> current.copy(accounts = withTitles(current.accounts.map {
            if (it.id == id) it.copy(account = it.account.copy(label = clean)) else it })) }
        WidgetUpdater.requestUpdate(app)
    }

    fun reorder(ids: List<String>) {
        registry.reorder(ids)
        state.update { current ->
            val ranked = ids.mapNotNull { id -> current.accounts.firstOrNull { it.id == id } }
            current.copy(accounts = withTitles(ranked + current.accounts.filter { it !in ranked }))
        }
        WidgetUpdater.requestUpdate(app)
    }

    /** Signs an account out: it disappears at once, its stored data is deleted in the background. */
    fun remove(id: String) {
        val account = registry.find(id) ?: state.value.account(id)?.account ?: return
        val runtime = runtimes.remove(id)
        runtime?.shutdown()
        ResetAlerts.invalidate(app, account)
        registry.remove(id)
        state.update { current ->
            current.copy(accounts = withTitles(current.accounts.filterNot { it.id == id }),
                community = current.community.filterKeys { key -> current.accounts.any { it.id != id && it.provider == key.provider } },
                forecasts = if (current.accounts.none { it.id != id && it.provider == Providers.CODEX }) emptyMap() else current.forecasts)
        }
        WidgetUpdater.requestUpdate(app)
        onChanged()
        scope.launch(Dispatchers.IO) {
            try {
                if (runtime != null) runtime.wipe()
                else AccountAccess.lock(id).withLock { SecureStore(app, account.namespace).clear() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                state.update { it.copy(storageWarning = "Lokale Daten von ${account.label ?: account.provider} konnten nicht vollständig gelöscht werden.") }
            }
            ResetTicks.schedule(app)
        }
    }
}
