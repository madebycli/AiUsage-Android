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
    val connected: Boolean = false, val disconnecting: Boolean = false, val initializing: Boolean = true,
    val usage: UsageSnapshot? = null, val previous: UsageSnapshot? = null,
    val observations: List<ResetObservation> = emptyList(),
    val refreshing: Boolean = false, val creditsLoading: Boolean = false, val usageError: String? = null, val storageWarning: String? = null,
    val feed: ResetFeed? = null, val feedLoading: Boolean = false, val feedError: String? = null,
    val source: ResetSource = ResetSource.CODEX_RESET, val nextFeedSeconds: Long = 0,
    val deviceCode: DeviceCode? = null, val loginPhase: LoginPhase = LoginPhase.REQUEST_CODE, val loginLoading: Boolean = false, val loginError: String? = null, val loginHint: String? = null,
    val claudeConnected: Boolean = false, val claudeInitializing: Boolean = true,
    val claudeUsage: UsageSnapshot? = null, val claudeRefreshing: Boolean = false, val claudeError: String? = null,
    val community: Map<CommunitySource, CommunityResult> = emptyMap(),
    val forecasts: Map<ResetSource, ForecastResult> = emptyMap(), val forecastLoading: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = true, val amoled: Boolean = false
)

class UsageViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("appearance", 0)
    private val store = SecureStore(application)
    private val http = HttpTransport()
    private val auth = CodexAuth(http, store)
    private val public = ResetRepository(http)
    private val forecastRepository = ForecastRepository(http)
    private val communityRepository = CommunityRepository(http)
    private val claudeStore = SecureStore(application, "claude")
    private var claudeRepository = ClaudeRepository(http)
    private val claudeMutex = Mutex()
    private val claudeGeneration = SessionGeneration()
    private val claudeQueue = RefreshQueue(viewModelScope) { readClaude() }
    private val forecastQueue = RefreshQueue(viewModelScope) { readForecasts() }
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
        viewModelScope.launch(Dispatchers.IO) {
            claudeMutex.withLock {
                try {
                    val credentials = claudeStore.readClaude()
                    val record = if (credentials != null) runCatching { claudeStore.readRecord() }.getOrDefault(PersonalRecord()) else PersonalRecord()
                    mutable.update { it.copy(claudeInitializing = false, claudeConnected = credentials != null, claudeUsage = record.usage) }
                    if (credentials != null) claudeQueue.request()
                } catch (_: Exception) { mutable.update { it.copy(claudeInitializing = false, claudeError = "Claude-Anmeldung konnte nicht gelesen werden. Bitte neu importieren.") } }
            }
        }
    }
    fun refresh() {
        if (state.value.connected) privateQueue.request()
        if (state.value.claudeConnected) claudeQueue.request()
        if (state.value.connected) publicQueue.request()
        if (state.value.connected || state.value.claudeConnected) forecastQueue.request()
    }
    private suspend fun readUsage() = quotaMutex.withLock { readUsageLocked() }
    private suspend fun readUsageLocked() {
        if (!state.value.connected) return
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
        if (!state.value.connected || (reading.availableResets ?: 0) == 0) return
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
        if (!state.value.connected) return
        val source = state.value.source
        mutable.update { it.copy(feedLoading = true) }
        try {
            val result = public.fetch(source)
            if (state.value.source == source) mutable.update {
                it.copy(feed = result.feed, feedError = result.error ?: if (result.feed == null) it.feedError else null,
                    nextFeedSeconds = result.nextFetchSeconds)
            }
        } finally { mutable.update { it.copy(feedLoading = false) } }
    }
    fun connect() {
        if (loginJob?.isActive == true || state.value.disconnecting || state.value.initializing || state.value.connected) return
        val generation = session.advance()
        mutable.update { it.copy(loginLoading = true, loginPhase = LoginPhase.REQUEST_CODE, loginError = null, loginHint = null, usage = null, previous = null, feed = null, storageWarning = null) }
        publicQueue.request()
        val previousLogin = loginJob
        loginJob = viewModelScope.launch(Dispatchers.IO) {
            previousLogin?.join()
            try {
                Log.i("AiUsage", "login phase=REQUEST_CODE")
                val code = auth.begin()
                Log.i("AiUsage", "login phase=WAITING")
                mutable.update { if (!session.isCurrent(generation)) it else it.copy(deviceCode = code, loginPhase = LoginPhase.WAITING) }
                auth.complete(code, onRetry = { error ->
                    Log.w("AiUsage", diagnosticFailure("login_poll_retry", error))
                    mutable.update { if (!session.isCurrent(generation)) it else it.copy(loginHint =
                        "Verbindung zur Freigabe kurz unterbrochen. Die App versucht es mit diesem Code erneut.") }
                }) { phase ->
                    Log.i("AiUsage", "login phase=${phase.name}")
                    mutable.update { if (!session.isCurrent(generation)) it else it.copy(loginPhase = phase, loginHint = null) }
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
                forecastQueue.request()
            } catch (e: TimeoutCancellationException) {
                Log.w("AiUsage", diagnosticFailure("login_${state.value.loginPhase.name}", e))
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
        mutable.update { it.copy(connected = false, disconnecting = true, usage = null, previous = null,
            observations = emptyList(), refreshing = false, creditsLoading = false, usageError = null, storageWarning = null, forecasts = emptyMap(), feed = null, community = it.community.filterKeys { key -> key.provider != "Codex" }) }
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
    fun setSource(source: ResetSource) {
        prefs.edit { putString("source", source.name) }
        mutable.update { it.copy(source = source, feed = null, feedError = null, nextFeedSeconds = 0) }
        if (state.value.connected) publicQueue.request()
    }
    fun loadCommunity() { if (state.value.connected) publicQueue.request()
        if (state.value.connected || state.value.claudeConnected) forecastQueue.request() }
    private suspend fun readForecasts() {
        if (!state.value.connected && !state.value.claudeConnected) return
        val generation = session.current()
        val claudeSession = claudeGeneration.current()
        mutable.update { it.copy(forecastLoading = true) }
        try {
            // Sources are independent: a slow or failed source never removes the other.
            coroutineScope {
                val codexJobs = if (state.value.connected) ResetSource.entries.map { source -> launch {
                    val result = forecastRepository.fetch(source)
                    mutable.update { if (!it.connected || !session.isCurrent(generation)) it else it.copy(forecasts = it.forecasts + (source to result)) }
                } } else emptyList()
                val communityJobs = CommunitySource.entries.filter { if (it.provider == "Codex") state.value.connected else state.value.claudeConnected }.map { source -> launch {
                    val result = communityRepository.fetch(source)
                    mutable.update {
                        val valid = if (source.provider == "Codex") it.connected && session.isCurrent(generation) else it.claudeConnected && claudeGeneration.isCurrent(claudeSession)
                        if (!valid) it else it.copy(community = it.community + (source to result))
                    }
                } }
                (codexJobs + communityJobs).joinAll()
            }
        } finally { mutable.update { it.copy(forecastLoading = false) } }
    }
    private suspend fun readClaude() = withContext(Dispatchers.IO) {
        claudeMutex.withLock {
            if (!state.value.claudeConnected) return@withLock
            val generation = claudeGeneration.current()
            mutable.update { it.copy(claudeRefreshing = true) }
            try {
                val credential = claudeStore.readClaude() ?: throw HttpFailure(401)
                val usage = claudeRepository.fetch(credential)
                currentCoroutineContext().ensureActive()
                if (claudeGeneration.isCurrent(generation)) {
                    mutable.update { it.copy(claudeUsage = usage, claudeError = null) }
                    val warning = persistAccountRecord { claudeStore.writeRecord(PersonalRecord(usage)) }
                    if (warning != null) mutable.update { it.copy(claudeError = warning) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (claudeGeneration.isCurrent(generation)) mutable.update { it.copy(claudeError = safeError(e)) }
            } finally { mutable.update { it.copy(claudeRefreshing = false) } }
        }
    }
    fun importClaude(raw: String) {
        if (state.value.claudeRefreshing || state.value.claudeInitializing) return
        val generation = claudeGeneration.advance()
        mutable.update { it.copy(claudeRefreshing = true, claudeError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            claudeMutex.withLock {
                try {
                    val credentials = ClaudeParser.credential(raw)
                    // Validate live access before saving or replacing an existing account.
                    val usage = claudeRepository.fetch(credentials)
                    currentCoroutineContext().ensureActive()
                    if (!claudeGeneration.isCurrent(generation)) return@withLock
                    claudeStore.writeClaude(credentials)
                    mutable.update { it.copy(claudeConnected = true, claudeUsage = usage, claudeError = null) }
                    val warning = persistAccountRecord { claudeStore.writeRecord(PersonalRecord(usage)) }
                    if (warning != null) mutable.update { it.copy(claudeError = warning) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (claudeGeneration.isCurrent(generation)) mutable.update { it.copy(claudeError = "Claude verbinden: ${safeError(e)}") } }
                finally { mutable.update { it.copy(claudeRefreshing = false) } }
            }
        }
    }
    fun disconnectClaude() {
        claudeGeneration.advance()
        mutable.update { it.copy(claudeConnected = false, claudeUsage = null, claudeError = null, community = it.community.filterKeys { key -> key.provider != "Claude Code" }) }
        viewModelScope.launch(Dispatchers.IO) { claudeMutex.withLock {
            try { claudeStore.clear(); claudeRepository = ClaudeRepository(http) }
            catch (_: Exception) { mutable.update { it.copy(claudeError = "Claude-Daten konnten nicht gelöscht werden.") } }
        } }
    }
    fun setTheme(theme: ThemeMode) { prefs.edit { putString("theme", theme.name) }; mutable.update { it.copy(theme = theme) } }
    fun setDynamic(enabled: Boolean) { prefs.edit { putBoolean("dynamic", enabled) }; mutable.update { it.copy(dynamicColor = enabled) } }
    fun setAmoled(enabled: Boolean) { prefs.edit { putBoolean("amoled", enabled) }; mutable.update { it.copy(amoled = enabled) } }
    private inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
