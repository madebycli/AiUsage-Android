package com.madebycli.aiusage

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import com.madebycli.aiusage.data.*
import com.madebycli.aiusage.background.*
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
    val days: List<UsageDay> = emptyList(), val claudeDays: List<UsageDay> = emptyList(),
    val refreshing: Boolean = false, val creditsLoading: Boolean = false, val usageError: String? = null, val storageWarning: String? = null,
    val feed: ResetFeed? = null, val feedLoading: Boolean = false, val feedError: String? = null,
    val source: ResetSource = ResetSource.CODEX_RESET, val nextFeedSeconds: Long = 0,
    val deviceCode: DeviceCode? = null, val loginPhase: LoginPhase = LoginPhase.REQUEST_CODE, val loginLoading: Boolean = false, val loginError: String? = null, val loginHint: String? = null,
    val claudeConnected: Boolean = false, val claudeInitializing: Boolean = true,
    val claudeUsage: UsageSnapshot? = null, val claudeRefreshing: Boolean = false, val claudeError: String? = null,
    val claudeDisconnecting: Boolean = false, val claudeLoginOpen: Boolean = false,
    val claudeLoginBusy: Boolean = false, val claudeLoginUrl: String? = null, val claudeLoginError: String? = null,
    val claudeLoginResult: Long = 0,
    val community: Map<CommunitySource, CommunityResult> = emptyMap(),
    val forecasts: Map<ResetSource, ForecastResult> = emptyMap(), val forecastLoading: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = false, val amoled: Boolean = false
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
    private var claudeAuth = ClaudeAuth(http, claudeStore)
    private var claudeLoginJob: Job? = null
    private var claudeRepository = ClaudeRepository(http)
    private val claudeMutex = AccountAccess.claude
    private val claudeGeneration = SessionGeneration()
    private var claudeQueue = RefreshQueue(viewModelScope) { readClaude() }
    private val forecastQueue = RefreshQueue(viewModelScope) { readForecasts() }
    private var personalScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
    private var usageRepository = UsageRepository(auth)
    private var privateQueue = RefreshQueue(personalScope) { withContext(Dispatchers.IO) { readUsage() } }
    private var creditsQueue = RefreshQueue(personalScope) { withContext(Dispatchers.IO) { readCredits() } }
    private val session = SessionGeneration()
    private val usageRevision = AtomicLong()
    private val recordMutex = Mutex()
    private val quotaMutex = AccountAccess.codex
    private val publicQueue = RefreshQueue(viewModelScope) { readFeed() }
    private var loginJob: Job? = null
    private val mutable = MutableStateFlow(UiState(
        source = enumValueOr(prefs.getString("source", null), ResetSource.CODEX_RESET),
        theme = enumValueOr(prefs.getString("theme", null), ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic", false), amoled = prefs.getBoolean("amoled", false)
    ))
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            AccountAccess.changes.collect { provider -> withContext(Dispatchers.IO) {
                val lock = if (provider == "Codex") AccountAccess.codex else AccountAccess.claude
                lock.withLock {
                    runCatching {
                        val record = (if (provider == "Codex") store else claudeStore).readRecord()
                        mutable.update { if (provider == "Codex" && it.connected) it.copy(usage = record.usage, days = record.days)
                            else if (provider == "Claude Code" && it.claudeConnected) it.copy(claudeUsage = record.usage, claudeDays = record.days) else it }
                    }
                }
            } }
        }

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
                    connected = tokens != null, usage = record.usage, observations = record.observations, days = record.days, storageWarning = recordWarning) }
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
                    val pendingUrl = claudeAuth.pendingUrl()
                    mutable.update { it.copy(claudeInitializing = false, claudeConnected = credentials != null, claudeUsage = record.usage, claudeDays = record.days,
                        claudeLoginOpen = pendingUrl != null, claudeLoginUrl = pendingUrl) }
                    if (credentials != null) claudeQueue.request()
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutable.update { it.copy(claudeInitializing = false, claudeError = "Claude-Anmeldung konnte nicht gelesen werden. Bitte neu verbinden.") } }
            }
        }
    }
    fun refresh() {
        if (state.value.connected) privateQueue.request()
        if (state.value.claudeConnected) claudeQueue.request()
        if (state.value.connected) publicQueue.request()
        if (state.value.connected || state.value.claudeConnected) forecastQueue.request()
    }
    fun refreshClaude() { if (state.value.claudeConnected) claudeQueue.request() }
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
    private suspend fun readCredits() = quotaMutex.withLock { readCreditsLocked() }
    private suspend fun readCreditsLocked() {
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
                    latest.usage?.let { usage ->
                        val record = store.saveMeasurement(usage, "Codex", latest.observations)
                        mutable.update { it.copy(days = record.days) }
                        ResetAlerts.schedule(getApplication(), "Codex", usage)
                    }
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
        ResetAlerts.invalidate(getApplication(), "Codex")
        session.advance()
        usageRevision.incrementAndGet()
        loginJob?.cancel()
        mutable.update { it.copy(loginLoading = false, deviceCode = null, loginError = null) }
        privateQueue.cancel()
        creditsQueue.cancel()
        val oldPersonalJob = personalScope.coroutineContext[Job]
        personalScope.cancel()
        mutable.update { it.copy(connected = false, disconnecting = true, usage = null, previous = null,
            observations = emptyList(), days = emptyList(), refreshing = false, creditsLoading = false, usageError = null, storageWarning = null, forecasts = emptyMap(), feed = null, community = it.community.filterKeys { key -> key.provider != "Codex" }) }
        viewModelScope.launch(Dispatchers.IO) {
            loginJob?.join()
            oldPersonalJob?.join()
            try { quotaMutex.withLock { store.clear() } }
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
            var operation = "claude_auth"
            try {
                val credential = claudeAuth.credential()
                operation = "claude_usage"
                val usage = try { claudeRepository.fetch(credential) } catch (e: HttpFailure) {
                    if (e.status != 401 || !credential.phoneLogin || credential.refresh == null) throw e
                    claudeRepository.fetch(claudeAuth.credential(forceRefresh = true))
                }
                currentCoroutineContext().ensureActive()
                if (usage.warnings.isNotEmpty()) Log.w("AiUsage", "claude_usage partial warnings=${usage.warnings.size}")
                claudeGeneration.commitIfCurrent(generation) {
                    mutable.update { it.copy(claudeUsage = usage, claudeError = null) }
                    try {
                        val record = claudeStore.saveMeasurement(usage, "Claude Code")
                        mutable.update { it.copy(claudeDays = record.days) }
                        ResetAlerts.schedule(getApplication(), "Claude Code", usage)
                    }
                    catch (_: Exception) { mutable.update { it.copy(claudeError = "Live-Daten geladen; der Verlauf konnte nicht gespeichert werden.") } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w("AiUsage", diagnosticFailure(operation, e))
                val message = if (operation == "claude_auth" &&
                    (e is SchemaException || e is kotlinx.serialization.SerializationException || e is IllegalArgumentException))
                    "Claude-Anmeldung konnte nicht gelesen oder erneuert werden. Bitte erneut verbinden." else safeError(e)
                if (claudeGeneration.isCurrent(generation)) mutable.update { it.copy(claudeError = message) }
            } finally { mutable.update { if (!claudeGeneration.isCurrent(generation)) it else it.copy(claudeRefreshing = false) } }
        }
    }
    fun beginClaudeLogin() {
        if (state.value.claudeInitializing || state.value.claudeDisconnecting || state.value.claudeRefreshing || state.value.claudeLoginBusy) return
        val generation = claudeGeneration.advance()
        mutable.update { it.copy(claudeLoginOpen = true, claudeLoginBusy = true, claudeLoginUrl = null, claudeLoginError = null) }
        val previous = claudeLoginJob
        claudeLoginJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            claudeMutex.withLock {
                try {
                    val url = claudeAuth.begin()
                    mutable.update { if (!claudeGeneration.isCurrent(generation)) it else it.copy(claudeLoginUrl = url) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { if (!claudeGeneration.isCurrent(generation)) it else it.copy(claudeLoginError = safeError(e)) } }
                finally { mutable.update { if (!claudeGeneration.isCurrent(generation)) it else it.copy(claudeLoginBusy = false) } }
            }
        }
    }
    fun completeClaudeLogin(code: String) {
        if (!state.value.claudeLoginOpen || state.value.claudeLoginBusy || state.value.claudeLoginUrl == null) return
        val generation = claudeGeneration.current()
        mutable.update { it.copy(claudeLoginBusy = true, claudeLoginError = null) }
        claudeLoginJob = viewModelScope.launch(Dispatchers.IO) {
            claudeMutex.withLock {
                try {
                    val issued = claudeAuth.complete(code)
                    val plan = try { withTimeoutOrNull(4_000) { claudeRepository.profilePlan(issued) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                    val credentials = ClaudeCredential(issued.access, issued.expiresAt, plan ?: issued.plan, issued.refresh, issued.phoneLogin)
                    currentCoroutineContext().ensureActive()
                    claudeGeneration.commitIfCurrent(generation) {
                        // Clear old measurements before replacing the account.
                        ResetAlerts.invalidate(getApplication(), "Claude Code")
                        claudeStore.writeRecord(PersonalRecord())
                        claudeStore.writeClaude(credentials)
                        mutable.update { it.copy(claudeConnected = true, claudeUsage = null, claudeDays = emptyList(), claudeError = null,
                            claudeLoginOpen = false, claudeLoginUrl = null, claudeLoginResult = it.claudeLoginResult + 1) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val pending = runCatching { claudeAuth.pendingUrl() }.getOrNull()
                    val message = when {
                        e is ClaudeLoginFailure -> e.message!!
                        e is HttpFailure && e.status in listOf(400, 401) -> "Browser-Freigabe konnte nicht übernommen werden. Bitte neu anmelden."
                        else -> safeError(e)
                    }
                    mutable.update { if (!claudeGeneration.isCurrent(generation)) it else it.copy(claudeLoginError = message, claudeLoginUrl = pending) }
                } finally { mutable.update { if (!claudeGeneration.isCurrent(generation)) it else it.copy(claudeLoginBusy = false) } }
            }
            if (claudeGeneration.isCurrent(generation) && !state.value.claudeLoginOpen && state.value.claudeConnected) {
                claudeQueue.request()
                forecastQueue.request()
            }
        }
    }
    fun cancelClaudeLogin() {
        claudeGeneration.advance()
        val previous = claudeLoginJob
        previous?.cancel()
        mutable.update { it.copy(claudeLoginOpen = false, claudeLoginBusy = false, claudeLoginUrl = null, claudeLoginError = null, claudeRefreshing = false) }
        claudeLoginJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            claudeMutex.withLock {
                try { claudeAuth.cancel() }
                catch (_: Exception) { mutable.update { it.copy(claudeError = "Anmeldeversuch konnte nicht gelöscht werden. Bitte Claude-Daten zurücksetzen.") } }
            }
        }
    }
    fun importClaude(raw: String) {
        if (state.value.claudeRefreshing || state.value.claudeInitializing || state.value.claudeDisconnecting || state.value.claudeLoginOpen || state.value.claudeLoginBusy) return
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
                    ResetAlerts.invalidate(getApplication(), "Claude Code")
                    claudeStore.writeRecord(PersonalRecord())
                    claudeStore.writeClaude(credentials)
                    mutable.update { it.copy(claudeConnected = true, claudeUsage = usage, claudeError = null) }
                    val warning = persistAccountRecord {
                        val record = claudeStore.saveMeasurement(usage, "Claude Code")
                        mutable.update { it.copy(claudeDays = record.days) }
                        ResetAlerts.schedule(getApplication(), "Claude Code", usage)
                    }
                    if (warning != null) mutable.update { it.copy(claudeError = warning) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (claudeGeneration.isCurrent(generation)) mutable.update { it.copy(claudeError = "Claude verbinden: ${safeError(e)}") } }
                finally { mutable.update { it.copy(claudeRefreshing = false) } }
            }
        }
    }
    fun disconnectClaude() {
        if (state.value.claudeDisconnecting) return
        ResetAlerts.invalidate(getApplication(), "Claude Code")
        claudeGeneration.advance()
        val previous = claudeLoginJob
        previous?.cancel()
        claudeQueue.cancel()
        mutable.update { it.copy(claudeConnected = false, claudeUsage = null, claudeDays = emptyList(), claudeError = null, claudeRefreshing = false,
            claudeDisconnecting = true, claudeLoginOpen = false, claudeLoginBusy = false, claudeLoginUrl = null, claudeLoginError = null,
            community = it.community.filterKeys { key -> key.provider != "Claude Code" }) }
        viewModelScope.launch(Dispatchers.IO) { previous?.join(); claudeMutex.withLock {
            try { claudeStore.clear() }
            catch (_: Exception) { mutable.update { it.copy(claudeError = "Claude-Daten konnten nicht gelöscht werden.") } }
            finally {
                claudeRepository = ClaudeRepository(http)
                claudeAuth = ClaudeAuth(http, claudeStore)
                claudeQueue = RefreshQueue(viewModelScope) { readClaude() }
                mutable.update { it.copy(claudeDisconnecting = false) }
            }
        } }
    }
    fun syncBackground() {
        if (!state.value.initializing && !state.value.claudeInitializing)
            DailyRefresh.sync(getApplication(), state.value.connected || state.value.claudeConnected)
    }
    fun rescheduleAlerts() {
        viewModelScope.launch(Dispatchers.IO) {
            quotaMutex.withLock { state.value.usage?.takeIf { state.value.connected }?.let { ResetAlerts.schedule(getApplication(), "Codex", it) } }
            claudeMutex.withLock { state.value.claudeUsage?.takeIf { state.value.claudeConnected }?.let { ResetAlerts.schedule(getApplication(), "Claude Code", it) } }
        }
    }
    fun foregroundRefresh() { if (!state.value.initializing && !state.value.claudeInitializing) refresh() }
    fun setTheme(theme: ThemeMode) { prefs.edit { putString("theme", theme.name) }; mutable.update { it.copy(theme = theme) } }
    fun setDynamic(enabled: Boolean) { prefs.edit { putBoolean("dynamic", enabled) }; mutable.update { it.copy(dynamicColor = enabled) } }
    fun setAmoled(enabled: Boolean) { prefs.edit { putBoolean("amoled", enabled) }; mutable.update { it.copy(amoled = enabled) } }
    private inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
