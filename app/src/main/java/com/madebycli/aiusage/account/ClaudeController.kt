package com.madebycli.aiusage.account

import android.app.Application
import android.util.Log
import com.madebycli.aiusage.LoginState
import com.madebycli.aiusage.ProviderState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.background.ResetAlerts
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** Claude Code sign-in (browser PKCE or imported credential) and quota reads. */
class ClaudeController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val http: HttpTransport,
    private val onSignedIn: () -> Unit
) {
    private val store = SecureStore(app, "claude")
    private val lock = AccountAccess.claude
    private val generation = SessionGeneration()
    private var auth = ClaudeAuth(http, store)
    private var repository = ClaudeRepository(http)
    private var queue = RefreshQueue(scope) { read() }
    private var loginJob: Job? = null
    private val current get() = state.value.claude

    fun load() = scope.launch(Dispatchers.IO) {
        lock.withLock {
            try {
                val credential = store.readClaude()
                val record = if (credential != null) runCatching { store.readRecord() }.getOrDefault(PersonalRecord()) else PersonalRecord()
                val pendingUrl = if (credential == null) auth.pendingUrl() else null
                state.updateClaude { copy(initializing = false, connected = credential != null, usage = record.usage, days = record.days,
                    login = if (pendingUrl != null) LoginState(open = true, url = pendingUrl) else login) }
                if (credential != null) queue.request()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { state.updateClaude { copy(initializing = false, error = "Claude-Anmeldung konnte nicht gelesen werden. Bitte neu verbinden.") } }
        }
    }

    fun refresh() { if (current.connected) queue.request() }

    fun refreshIfStale(maxAgeSeconds: Long) {
        val usage = current.usage
        if (usage == null || Instant.now().epochSecond - usage.fetchedAt > maxAgeSeconds) refresh()
    }

    suspend fun reloadStoredRecord() = lock.withLock {
        runCatching { store.readRecord() }.getOrNull()?.let { record -> state.updateClaude { if (connected) copy(usage = record.usage, days = record.days) else this } }
        Unit
    }

    private suspend fun read(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!current.connected) return@withLock
            val gen = generation.current()
            state.updateClaude { copy(refreshing = true) }
            var operation = "claude_auth"
            try {
                val credential = auth.credential()
                operation = "claude_usage"
                val usage = try { repository.fetch(credential) } catch (e: HttpFailure) {
                    if (e.status != 401 || !credential.phoneLogin || credential.refresh == null) throw e
                    repository.fetch(auth.credential(forceRefresh = true))
                }
                currentCoroutineContext().ensureActive()
                if (usage.warnings.isNotEmpty()) Log.w(TAG, "claude_usage partial warnings=${usage.warnings.size}")
                generation.commitIfCurrent(gen) {
                    state.updateClaude { copy(usage = usage, error = null) }
                    try {
                        val record = store.saveMeasurement(usage, Providers.CLAUDE)
                        state.updateClaude { copy(days = record.days) }
                        ResetAlerts.schedule(app, Providers.CLAUDE, usage)
                    } catch (_: Exception) { state.updateClaude { copy(error = "Live-Daten geladen; der Verlauf konnte nicht gespeichert werden.") } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, diagnosticFailure(operation, e))
                val unreadableLogin = operation == "claude_auth" &&
                    (e is SchemaException || e is kotlinx.serialization.SerializationException || e is IllegalArgumentException)
                val message = if (unreadableLogin) "Claude-Anmeldung konnte nicht gelesen oder erneuert werden. Bitte erneut verbinden." else safeError(e)
                state.updateClaude { if (generation.isCurrent(gen)) copy(error = message) else this }
            } finally {
                state.updateClaude { if (generation.isCurrent(gen)) copy(refreshing = false) else this }
            }
        }
    }

    fun beginLogin() {
        val s = current
        if (s.initializing || s.disconnecting || s.login.busy) return
        val gen = generation.advance()
        state.updateClaude { copy(login = LoginState(open = true, busy = true)) }
        val previous = loginJob
        loginJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            lock.withLock {
                try {
                    val url = auth.begin()
                    state.updateClaude { if (generation.isCurrent(gen)) copy(login = login.copy(url = url)) else this }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { state.updateClaude { if (generation.isCurrent(gen)) copy(login = login.copy(error = safeError(e))) else this } }
                finally { state.updateClaude { if (generation.isCurrent(gen)) copy(login = login.copy(busy = false)) else this } }
            }
        }
    }

    fun completeLogin(code: String) {
        val login = current.login
        if (!login.open || login.busy || login.url == null) return
        val gen = generation.current()
        state.updateClaude { copy(login = login.copy(busy = true, error = null)) }
        loginJob = scope.launch(Dispatchers.IO) {
            lock.withLock {
                try {
                    val issued = auth.complete(code)
                    val plan = try { withTimeoutOrNull(4_000) { repository.profilePlan(issued) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                    val credential = ClaudeCredential(issued.access, issued.expiresAt, plan ?: issued.plan, issued.refresh, issued.phoneLogin)
                    currentCoroutineContext().ensureActive()
                    generation.commitIfCurrent(gen) {
                        ResetAlerts.invalidate(app, Providers.CLAUDE)
                        store.writeRecord(PersonalRecord())
                        store.writeClaude(credential)
                        state.updateClaude { copy(connected = true, usage = null, days = emptyList(), error = null, login = LoginState(), loginResult = loginResult + 1) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val pending = runCatching { auth.pendingUrl() }.getOrNull()
                    val message = when {
                        e is ClaudeLoginFailure -> e.message.orEmpty()
                        e is HttpFailure && e.status in listOf(400, 401) -> "Browser-Freigabe konnte nicht übernommen werden. Bitte neu anmelden."
                        else -> safeError(e)
                    }
                    state.updateClaude { if (generation.isCurrent(gen)) copy(login = login.copy(error = message, url = pending)) else this }
                } finally { state.updateClaude { if (generation.isCurrent(gen)) copy(login = login.copy(busy = false)) else this } }
            }
            if (generation.isCurrent(gen) && !current.login.open && current.connected) { queue.request(); onSignedIn() }
        }
    }

    /** Uses a credential copied from Claude Code on a computer. It is only read, never refreshed. */
    fun importCredential(raw: String) {
        val s = current
        if (s.initializing || s.disconnecting || s.login.busy) return
        val gen = generation.advance()
        loginJob?.cancel()
        state.updateClaude { copy(login = login.copy(open = true, busy = true, error = null)) }
        val previous = loginJob
        loginJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            lock.withLock {
                try {
                    val credential = ClaudeParser.credential(raw)
                    val usage = repository.fetch(credential)
                    currentCoroutineContext().ensureActive()
                    if (!generation.isCurrent(gen)) return@withLock
                    ResetAlerts.invalidate(app, Providers.CLAUDE)
                    store.clearClaudeLogin()
                    store.writeRecord(PersonalRecord())
                    store.writeClaude(credential)
                    state.updateClaude { copy(connected = true, usage = usage, error = null, login = LoginState(), loginResult = loginResult + 1) }
                    persistAccountRecord {
                        val record = store.saveMeasurement(usage, Providers.CLAUDE)
                        state.updateClaude { copy(days = record.days) }
                        ResetAlerts.schedule(app, Providers.CLAUDE, usage)
                    }?.let { warning -> state.updateClaude { copy(error = warning) } }
                    onSignedIn()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    state.updateClaude { if (generation.isCurrent(gen)) copy(login = login.copy(error = "Import fehlgeschlagen. ${safeError(e)}")) else this }
                } finally { state.updateClaude { if (generation.isCurrent(gen)) copy(login = login.copy(busy = false)) else this } }
            }
        }
    }

    fun cancelLogin() {
        generation.advance()
        val previous = loginJob
        previous?.cancel()
        state.updateClaude { copy(login = LoginState(), refreshing = false) }
        loginJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            lock.withLock {
                try { auth.cancel() }
                catch (_: Exception) { state.updateClaude { copy(error = "Anmeldeversuch konnte nicht gelöscht werden. Bitte Claude-Daten zurücksetzen.") } }
            }
        }
    }

    fun disconnect() {
        if (current.disconnecting) return
        ResetAlerts.invalidate(app, Providers.CLAUDE)
        generation.advance()
        val previous = loginJob
        previous?.cancel()
        queue.cancel()
        state.update { it.copy(
            claude = ProviderState(initializing = false, disconnecting = true),
            community = it.community.filterKeys { key -> key.provider != Providers.CLAUDE }) }
        scope.launch(Dispatchers.IO) {
            previous?.join()
            lock.withLock {
                try { store.clear() }
                catch (_: Exception) { state.updateClaude { copy(error = "Claude-Daten konnten nicht gelöscht werden.") } }
                finally {
                    repository = ClaudeRepository(http)
                    auth = ClaudeAuth(http, store)
                    queue = RefreshQueue(scope) { read() }
                    state.updateClaude { copy(disconnecting = false) }
                }
            }
        }
    }

    private companion object { const val TAG = "AiUsage" }
}
