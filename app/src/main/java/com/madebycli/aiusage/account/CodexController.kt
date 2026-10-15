package com.madebycli.aiusage.account

import android.app.Application
import android.util.Log
import com.madebycli.aiusage.LoginState
import com.madebycli.aiusage.ProviderState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.background.ResetAlerts
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.madebycli.aiusage.UiState
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

private const val STATS_MAX_AGE_SECONDS = 4 * 3600L
const val CODEX_DEVICE_PAGE = "https://auth.openai.com/codex/device"

/** Codex sign-in (device code), quota reads and reset-credit details. */
class CodexController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    http: HttpTransport,
    private val onSignedIn: () -> Unit
) {
    private val store = SecureStore(app)
    private val auth = CodexAuth(http, store)
    private val lock = AccountAccess.codex
    private val generation = SessionGeneration()
    private val usageRevision = AtomicLong()
    private val recordLock = Mutex()
    private var usageRepository = UsageRepository(auth)
    private var work = newWorkScope()
    private var usageQueue = newUsageQueue()
    private var creditsQueue = newCreditsQueue()
    private var loginJob: Job? = null
    private var statsJob: Job? = null

    private fun newWorkScope() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private fun newUsageQueue() = RefreshQueue(work) { withContext(Dispatchers.IO) { readUsage() } }
    private fun newCreditsQueue() = RefreshQueue(work) { withContext(Dispatchers.IO) { readCredits() } }
    private val current get() = state.value.codex

    fun load() = scope.launch(Dispatchers.IO) {
        val gen = generation.current()
        try {
            val tokens = store.readTokens()
            var warning: String? = null
            val record = if (tokens == null) PersonalRecord() else try { store.readRecord() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { warning = "Gespeicherte Messung konnte nicht gelesen werden. Jetzt aktualisieren."; PersonalRecord() }
            val pending = if (tokens == null) store.readDeviceLogin()?.takeIf { it.expiresAt > Instant.now().epochSecond } else null
            if (tokens == null && pending == null) runCatching { store.clearDeviceLogin() }
            val stats = if (tokens != null) runCatching { store.readStats() }.getOrDefault(StatsBundle()) else StatsBundle()
            state.update {
                if (!generation.isCurrent(gen)) it
                else it.copy(codex = it.codex.copy(initializing = false, connected = tokens != null, usage = record.usage, days = record.days, stats = stats), storageWarning = warning)
            }
            if (tokens != null && generation.isCurrent(gen)) usageQueue.request()
            else if (pending != null && generation.isCurrent(gen)) startLogin(pending)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            state.updateCodex { copy(initializing = false, error = "Gespeicherte Anmeldung konnte nicht gelesen werden. Lokale Daten zurücksetzen und erneut verbinden.") }
        }
    }

    fun refresh() { if (current.connected) usageQueue.request() }

    suspend fun reloadStoredRecord() = lock.withLock {
        runCatching { store.readRecord() }.getOrNull()?.let { record -> state.updateCodex { if (connected) copy(usage = record.usage, days = record.days) else this } }
        Unit
    }

    fun refreshIfStale(maxAgeSeconds: Long) {
        val usage = current.usage
        if (usage == null || Instant.now().epochSecond - usage.fetchedAt > maxAgeSeconds) refresh()
    }

    fun connect() {
        if (loginJob?.isActive == true || current.disconnecting || current.initializing || current.connected) return
        startLogin(null)
    }

    private fun startLogin(resume: PendingDeviceLogin?) {
        val gen = generation.advance()
        state.updateCodex { copy(login = LoginState(open = true, busy = true), usage = null) }
        val previous = loginJob
        loginJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            runLogin(gen, resume)
        }
    }

    private suspend fun runLogin(gen: Long, resume: PendingDeviceLogin?) {
        fun edit(change: LoginState.() -> LoginState) = state.updateCodex { if (generation.isCurrent(gen)) copy(login = login.change()) else this }
        try {
            val pending = resume ?: PendingDeviceLogin(auth.begin(), Instant.now().epochSecond + DEVICE_CODE_LIFETIME_SECONDS)
                .also { store.writeDeviceLogin(it) }
            edit { copy(phase = LoginPhase.WAITING, url = CODEX_DEVICE_PAGE, deviceCode = pending.device.code) }
            auth.complete(pending.device,
                onRetry = { error ->
                    Log.w(TAG, diagnosticFailure("login_poll_retry", error))
                    edit { copy(hint = "Verbindung kurz unterbrochen. Die App versucht es mit demselben Code erneut.") }
                },
                onPhase = { phase -> edit { copy(phase = phase, hint = null, deviceCode = if (phase == LoginPhase.WAITING) deviceCode else null) } },
                timeoutMillis = ((pending.expiresAt - Instant.now().epochSecond) * 1000).coerceAtLeast(1_000))
            runCatching { store.clearDeviceLogin() }
            currentCoroutineContext().ensureActive()
            if (!generation.isCurrent(gen)) return
            state.updateCodex { copy(connected = true, error = null, login = login.copy(phase = LoginPhase.LOADING_USAGE)) }
            readUsage()
            currentCoroutineContext().ensureActive()
            state.updateCodex { if (generation.isCurrent(gen)) copy(login = LoginState(), loginResult = loginResult + 1) else this }
            onSignedIn()
        } catch (e: TimeoutCancellationException) {
            runCatching { store.clearDeviceLogin() }
            edit { copy(busy = false, url = null, deviceCode = null, error = "Der Anmeldecode ist abgelaufen. Bitte einen neuen anfordern.") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val phase = current.login.phase
            Log.w(TAG, diagnosticFailure("login_${phase.name}", e))
            runCatching { store.clearDeviceLogin() }
            edit { copy(busy = false, url = null, deviceCode = null, error = loginFailure(phase, e)) }
        } finally {
            state.updateCodex { if (generation.isCurrent(gen)) copy(login = login.copy(busy = false)) else this }
        }
    }

    fun cancelLogin() {
        generation.advance()
        loginJob?.cancel()
        state.updateCodex { copy(login = LoginState()) }
        scope.launch(Dispatchers.IO) { runCatching { store.clearDeviceLogin() } }
    }

    fun disconnect() {
        if (current.disconnecting) return
        ResetAlerts.invalidate(app, Providers.CODEX)
        generation.advance()
        usageRevision.incrementAndGet()
        loginJob?.cancel()
        statsJob?.cancel()
        usageQueue.cancel()
        creditsQueue.cancel()
        val oldWork = work.coroutineContext[Job]
        work.cancel()
        state.update { it.copy(
            codex = ProviderState(initializing = false, disconnecting = true),
            creditsLoading = false, storageWarning = null, forecasts = emptyMap(), feed = null,
            community = it.community.filterKeys { key -> key.provider != Providers.CODEX }) }
        scope.launch(Dispatchers.IO) {
            loginJob?.join()
            oldWork?.join()
            try { lock.withLock { store.clear() } }
            catch (_: Exception) { state.updateCodex { copy(error = "Lokale Anmeldung konnte nicht vollständig gelöscht werden.") } }
            usageRepository = UsageRepository(auth)
            work = newWorkScope()
            usageQueue = newUsageQueue()
            creditsQueue = newCreditsQueue()
            state.updateCodex { copy(disconnecting = false) }
        }
    }

    /** Reads the account reports OpenAI offers to this login. Unavailable reports stay empty, never zero. */
    fun refreshStats(force: Boolean = false) {
        val s = current
        if (!state.value.statsEnabled || !s.connected || statsJob?.isActive == true) return
        val prefs = app.getSharedPreferences("stats-refresh", 0)
        val now = Instant.now().epochSecond
        if (prefs.getLong("retry.${Providers.CODEX}", 0) > now) {
            state.updateCodex { copy(statsError = "OpenAI hat eine Wartezeit für Statistiken gesetzt. Später erneut versuchen.") }
            return
        }
        if (!force && s.stats.reports.any { now - it.fetchedAt < STATS_MAX_AGE_SECONDS && it.error == null }) return
        val gen = generation.current()
        fun valid() = generation.isCurrent(gen) && current.connected
        state.updateCodex { copy(statsLoading = true, statsError = null) }
        statsJob = scope.launch(Dispatchers.IO) {
            try {
                val repository = CodexStatsRepository(auth)
                for (kind in CodexReport.entries) {
                    var rateLimited = false
                    lock.withLock {
                        if (!valid()) return@withLock
                        val previous = store.readStats()
                        val report = try { withTimeout(10_000) { repository.fetch(kind, Instant.now().epochSecond) } }
                        catch (e: TimeoutCancellationException) { failed(previous, kind, "Abruf hat zu lange gedauert.") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            if (e is HttpFailure && e.status == 429) {
                                rateLimited = true
                                prefs.edit().putLong("retry.${Providers.CODEX}", Instant.now().epochSecond + (e.retrySeconds ?: 1800).coerceIn(1, 604800)).apply()
                            }
                            failed(previous, kind, if (e is HttpFailure && e.status in listOf(403, 404)) "Für diesen Account nicht verfügbar." else safeError(e))
                        }
                        currentCoroutineContext().ensureActive()
                        if (valid()) {
                            val next = previous.merge(report)
                            store.writeStats(next)
                            state.updateCodex { copy(stats = next) }
                        }
                    }
                    if (rateLimited) break
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.updateCodex { if (generation.isCurrent(gen)) copy(statsError = safeError(e)) else this } }
            finally { state.updateCodex { if (generation.isCurrent(gen)) copy(statsLoading = false) else this } }
        }
    }

    fun cancelStats() {
        statsJob?.cancel()
        state.updateCodex { copy(statsLoading = false, statsError = null) }
    }

    private fun failed(previous: StatsBundle, kind: CodexReport, message: String) =
        (previous.reports.firstOrNull { it.id == kind.id } ?: StatsReport(kind.id, kind.title, "Account · UTC", 0)).copy(error = message)

    private suspend fun readUsage(): Unit = lock.withLock {
        if (!current.connected) return@withLock
        val gen = generation.current()
        state.updateCodex { copy(refreshing = true) }
        try {
            val usage = usageRepository.fetch()
            currentCoroutineContext().ensureActive()
            if (!generation.isCurrent(gen)) return@withLock
            val revision = usageRevision.incrementAndGet()
            state.update { if (!generation.isCurrent(gen)) it else it.copy(
                codex = it.codex.copy(usage = usage, error = null), storageWarning = null,
                creditsLoading = (usage.availableResets ?: 0) > 0) }
            if ((usage.availableResets ?: 0) > 0) creditsQueue.request()
            val warning = persist(gen, revision)
            state.update { if (!generation.isCurrent(gen) || usageRevision.get() != revision) it else it.copy(storageWarning = warning) }
            scope.launch { if (generation.isCurrent(gen)) refreshStats() }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, diagnosticFailure("usage", e))
            state.updateCodex { if (generation.isCurrent(gen)) copy(error = safeError(e)) else this }
        } finally {
            state.updateCodex { if (generation.isCurrent(gen)) copy(refreshing = false) else this }
        }
    }

    private suspend fun readCredits(): Unit = lock.withLock {
        val gen = generation.current()
        val revision = usageRevision.get()
        val reading = current.usage ?: return@withLock
        if (!current.connected || (reading.availableResets ?: 0) == 0) return@withLock
        try {
            val enriched = usageRepository.fetchCredits(reading)
            currentCoroutineContext().ensureActive()
            state.updateCodex { if (generation.isCurrent(gen) && usageRevision.get() == revision) copy(usage = enriched) else this }
            val warning = persist(gen, revision)
            state.update { if (!generation.isCurrent(gen) || usageRevision.get() != revision) it else it.copy(storageWarning = warning) }
        } finally {
            state.update { if (!generation.isCurrent(gen) || usageRevision.get() != revision) it else it.copy(creditsLoading = false) }
        }
    }

    private suspend fun persist(gen: Long, revision: Long): String? = persistAccountRecord {
        recordLock.withLock {
            currentCoroutineContext().ensureActive()
            if (generation.isCurrent(gen) && usageRevision.get() == revision) {
                current.usage?.let { usage ->
                    val record = store.saveMeasurement(usage, Providers.CODEX)
                    state.updateCodex { copy(days = record.days) }
                    ResetAlerts.schedule(app, Providers.CODEX, usage)
                }
            }
        }
    }

    private companion object { const val TAG = "AiUsage" }
}
