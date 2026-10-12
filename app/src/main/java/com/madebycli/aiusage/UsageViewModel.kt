package com.madebycli.aiusage

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class UiState(
    val connected: Boolean = false, val demo: Boolean = false, val disconnecting: Boolean = false, val initializing: Boolean = true,
    val usage: UsageSnapshot? = null, val previous: UsageSnapshot? = null,
    val observations: List<ResetObservation> = emptyList(),
    val refreshing: Boolean = false, val creditsLoading: Boolean = false, val usageError: String? = null, val storageWarning: String? = null,
    val feed: ResetFeed? = null, val feedLoading: Boolean = false, val feedError: String? = null,
    val source: ResetSource = ResetSource.CODEX_RESET, val nextFeedSeconds: Long = 0,
    val deviceCode: DeviceCode? = null, val loginPhase: LoginPhase = LoginPhase.REQUEST_CODE, val loginLoading: Boolean = false, val loginError: String? = null,
    val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true, val amoled: Boolean = false
)

class UsageViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("appearance", 0)
    private val store = SecureStore(application)
    private val http = HttpTransport()
    private val auth = CodexAuth(http, store)
    private val public = ResetRepository(http)
    private var personalScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
    private var usageRepository = UsageRepository(auth)
    private var privateQueue = RefreshQueue(personalScope) { withContext(Dispatchers.IO) { readUsage() } }
    private var creditsQueue = RefreshQueue(personalScope) { withContext(Dispatchers.IO) { readCredits() } }
    private val session = SessionGeneration()
    private val usageRevision = AtomicLong()
    private val recordMutex = Mutex()
    private val quotaMutex = Mutex()
    private val publicQueue = RefreshQueue(viewModelScope) { readFeed() }
    private var loginJob: Job? = null
    private val mutable = MutableStateFlow(UiState(
        source = enumValueOr(prefs.getString("source", null), ResetSource.CODEX_RESET),
        theme = enumValueOr(prefs.getString("theme", null), ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic", true), amoled = prefs.getBoolean("amoled", false)
    ))
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val generation = session.current()
            try {
                val tokens = store.readTokens()
                var recordWarning: String? = null
                val record = if (tokens != null) try { store.readRecord() } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    recordWarning = "Gespeicherte Messung konnte nicht gelesen werden. Jetzt aktualisieren."
                    PersonalRecord()
                } else PersonalRecord()
                mutable.update { if (!session.isCurrent(generation)) it else it.copy(initializing = false,
                    connected = tokens != null, usage = record.usage, observations = record.observations, storageWarning = recordWarning) }
                if (tokens != null && session.isCurrent(generation)) privateQueue.request()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.update { if (!session.isCurrent(generation)) it else it.copy(initializing = false,
                    usageError = "Gespeicherte Anmeldung konnte nicht gelesen werden. Lokale Daten zurücksetzen und erneut verbinden.") }
            }
        }
        publicQueue.request()
    }
    fun refresh() {
        if (state.value.demo) {
            mutable.update { it.copy(previous = it.usage, usage = demoUsage(), usageError = null) }
            return
        }
        if (state.value.connected) privateQueue.request()
        publicQueue.request()
    }
    private suspend fun readUsage() = quotaMutex.withLock { readUsageLocked() }
    private suspend fun readUsageLocked() {
        if (!state.value.connected || state.value.demo) return
        val generation = session.current()
        mutable.update { it.copy(refreshing = true) }
        try {
            val usage = usageRepository.fetch()
            currentCoroutineContext().ensureActive()
            if (!session.isCurrent(generation)) return
            val old = state.value.usage
            val observations = (observeResets(old, usage) + state.value.observations).take(100)
            val revision = usageRevision.incrementAndGet()
            // Publish quota immediately. Neither disk persistence nor optional detail requests delay it.
            mutable.update { if (!session.isCurrent(generation)) it else it.copy(usage = usage, previous = old,
                observations = observations, usageError = null, storageWarning = null, creditsLoading = (usage.availableResets ?: 0) > 0) }
            if ((usage.availableResets ?: 0) > 0) creditsQueue.request()
            val warning = persist(generation, revision)
            mutable.update { if (!session.isCurrent(generation) || usageRevision.get() != revision) it else it.copy(storageWarning = warning) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w("AiUsage", diagnosticFailure("usage", e))
            mutable.update { if (!session.isCurrent(generation)) it else it.copy(usageError = safeError(e)) }
        }
        finally { mutable.update { if (!session.isCurrent(generation)) it else it.copy(refreshing = false) } }
    }
    private suspend fun readCredits() {
        val generation = session.current()
        val revision = usageRevision.get()
        val reading = state.value.usage ?: return
        if (!state.value.connected || state.value.demo || (reading.availableResets ?: 0) == 0) return
        try {
            val enriched = usageRepository.fetchCredits(reading)
            currentCoroutineContext().ensureActive()
            mutable.update { if (!session.isCurrent(generation) || usageRevision.get() != revision) it else it.copy(usage = enriched) }
            val warning = persist(generation, revision)
            mutable.update { if (!session.isCurrent(generation) || usageRevision.get() != revision) it else it.copy(storageWarning = warning) }
        } finally {
            mutable.update { if (!session.isCurrent(generation) || usageRevision.get() != revision) it else it.copy(creditsLoading = false) }
        }
    }
    private suspend fun persist(generation: Long, revision: Long): String? =
        persistAccountRecord {
            recordMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (session.isCurrent(generation) && usageRevision.get() == revision) {
                    val latest = state.value
                    store.writeRecord(PersonalRecord(latest.usage, latest.observations))
                }
            }
        }
    private suspend fun readFeed() {
        if (state.value.demo) return
        val source = state.value.source
        mutable.update { it.copy(feedLoading = true) }
        try {
            val result = public.fetch(source)
            if (state.value.source == source && !state.value.demo) mutable.update {
                it.copy(feed = result.feed, feedError = result.error ?: if (result.feed == null) it.feedError else null,
                    nextFeedSeconds = result.nextFetchSeconds)
            }
        } finally { mutable.update { it.copy(feedLoading = false) } }
    }
    fun connect() {
        if (loginJob?.isActive == true || state.value.disconnecting || state.value.initializing || state.value.connected) return
        val generation = session.advance()
        mutable.update { it.copy(loginLoading = true, loginPhase = LoginPhase.REQUEST_CODE, loginError = null, demo = false, usage = null, previous = null, feed = null, storageWarning = null) }
        publicQueue.request()
        val previousLogin = loginJob
        loginJob = viewModelScope.launch(Dispatchers.IO) {
            previousLogin?.join()
            try {
                val code = auth.begin()
                mutable.update { if (!session.isCurrent(generation)) it else it.copy(deviceCode = code, loginPhase = LoginPhase.WAITING) }
                auth.complete(code) { phase ->
                    Log.i("AiUsage", "login phase=${phase.name}")
                    mutable.update { if (!session.isCurrent(generation)) it else it.copy(loginPhase = phase) }
                }
                currentCoroutineContext().ensureActive()
                if (!session.isCurrent(generation)) return@launch
                Log.i("AiUsage", "login phase=LOADING_USAGE")
                mutable.update { if (!session.isCurrent(generation)) it else it.copy(connected = true,
                    loginPhase = LoginPhase.LOADING_USAGE, deviceCode = null, usageError = null) }
                readUsage()
                currentCoroutineContext().ensureActive()
                mutable.update { if (!session.isCurrent(generation)) it else it.copy(loginError =
                    it.usageError?.let { error -> "Anmeldung bestätigt, Verbrauch konnte nicht geladen werden. $error" }) }
                publicQueue.request()
            } catch (e: TimeoutCancellationException) {
                mutable.update { if (!session.isCurrent(generation)) it else it.copy(loginError = "Anmeldecode abgelaufen. Bitte neu verbinden.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val phase = state.value.loginPhase
                Log.w("AiUsage", diagnosticFailure("login_${phase.name}", e))
                mutable.update { if (!session.isCurrent(generation)) it else it.copy(loginError = loginFailure(phase, e)) }
            }
            finally { mutable.update { if (!session.isCurrent(generation)) it else it.copy(loginLoading = false, deviceCode = null) } }
        }
    }
    fun cancelLogin() {
        if (state.value.connected) { mutable.update { it.copy(loginLoading = false) }; return }
        if (state.value.loginLoading) session.advance()
        loginJob?.cancel(); mutable.update { it.copy(loginLoading = false, deviceCode = null) } }
    fun clearLoginError() { mutable.update { it.copy(loginError = null) } }
    fun disconnect() {
        if (state.value.disconnecting) return
        session.advance()
        usageRevision.incrementAndGet()
        loginJob?.cancel()
        mutable.update { it.copy(loginLoading = false, deviceCode = null, loginError = null) }
        privateQueue.cancel()
        creditsQueue.cancel()
        val oldPersonalJob = personalScope.coroutineContext[Job]
        personalScope.cancel()
        mutable.update { it.copy(connected = false, disconnecting = true, demo = false, usage = null, previous = null,
            observations = emptyList(), refreshing = false, creditsLoading = false, usageError = null, storageWarning = null) }
        viewModelScope.launch(Dispatchers.IO) {
            loginJob?.join()
            oldPersonalJob?.join()
            try { store.clear() }
            catch (_: Exception) { mutable.update { it.copy(usageError = "Lokale Anmeldung konnte nicht vollständig gelöscht werden.") } }
            usageRepository = UsageRepository(auth)
            personalScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
            privateQueue = RefreshQueue(personalScope) { withContext(Dispatchers.IO) { readUsage() } }
            creditsQueue = RefreshQueue(personalScope) { withContext(Dispatchers.IO) { readCredits() } }
            mutable.update { it.copy(disconnecting = false) }
        }
    }
    fun demo() {
        if (state.value.connected || state.value.disconnecting || state.value.initializing) return
        session.advance()
        cancelLogin()
        mutable.update { it.copy(demo = true, usage = demoUsage(), previous = null, observations = emptyList(), usageError = null, storageWarning = null,
            feed = demoFeed(it.source), feedError = null, loginError = null) }
    }
    fun exitDemo() {
        session.advance()
        mutable.update { it.copy(demo = false, usage = null, previous = null, feed = null) }
        publicQueue.request()
    }
    fun setSource(source: ResetSource) {
        prefs.edit { putString("source", source.name) }
        mutable.update { it.copy(source = source, feed = if (it.demo) demoFeed(source) else null, feedError = null, nextFeedSeconds = 0) }
        if (!state.value.demo) publicQueue.request()
    }
    fun setTheme(theme: ThemeMode) { prefs.edit { putString("theme", theme.name) }; mutable.update { it.copy(theme = theme) } }
    fun setDynamic(enabled: Boolean) { prefs.edit { putBoolean("dynamic", enabled) }; mutable.update { it.copy(dynamicColor = enabled) } }
    fun setAmoled(enabled: Boolean) { prefs.edit { putBoolean("amoled", enabled) }; mutable.update { it.copy(amoled = enabled) } }
    private inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

fun demoUsage(): UsageSnapshot {
    val now = Instant.now().epochSecond
    return UsageSnapshot("Plus · Demo", listOf(QuotaGroup("Codex", listOf(
        UsageWindow(24.0, 18000, now + 9890), UsageWindow(61.0, 604800, now + 246600)
    ))), now, 1, listOf(ResetCredit("Vollständiger Reset", "available", now - 172800, now + 1_209_600)))
}
fun demoFeed(source: ResetSource): ResetFeed {
    val now = Instant.now().epochSecond
    return ResetFeed(source, listOf(
        ResetEvent("demo:1", now - 7200, if (source == ResetSource.CODEX_RESET) "Globaler Reset" else "Ledger-Ereignis", "Beispiel: Globale Limits wurden zurückgesetzt.", null),
        ResetEvent("demo:2", now - 172800, "Banked · available", "Beispiel: Ein gespeicherter Reset wurde vergeben.", null)
    ), now, now)
}
