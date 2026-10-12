package com.madebycli.aiusage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class UiState(
    val connected: Boolean = false, val demo: Boolean = false, val disconnecting: Boolean = false,
    val usage: UsageSnapshot? = null, val previous: UsageSnapshot? = null,
    val observations: List<ResetObservation> = emptyList(),
    val refreshing: Boolean = false, val usageError: String? = null,
    val feed: ResetFeed? = null, val feedLoading: Boolean = false, val feedError: String? = null,
    val source: ResetSource = ResetSource.CODEX_RESET, val nextFeedSeconds: Long = 0,
    val deviceCode: DeviceCode? = null, val loginLoading: Boolean = false, val loginError: String? = null,
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
            try {
                val tokens = store.readTokens()
                val record = if (tokens != null) store.readRecord() else PersonalRecord()
                mutable.update { it.copy(connected = tokens != null, usage = record.usage, observations = record.observations) }
                if (tokens != null) privateQueue.request()
            } catch (_: Exception) {
                mutable.update { it.copy(usageError = "Gespeicherte Anmeldung konnte nicht gelesen werden. Bitte trennen und erneut verbinden.") }
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
    private suspend fun readUsage() {
        if (!state.value.connected || state.value.demo) return
        mutable.update { it.copy(refreshing = true) }
        try {
            val usage = usageRepository.fetch()
            val old = state.value.usage
            val observations = (observeResets(old, usage) + state.value.observations).take(100)
            var persistenceWarning: String? = null
            try { withContext(Dispatchers.IO) { store.writeRecord(PersonalRecord(usage, observations)) } }
            catch (_: Exception) { persistenceWarning = "Live-Daten geladen; der Verlauf konnte nicht gespeichert werden." }
            mutable.update { it.copy(usage = usage, previous = old, observations = observations, usageError = persistenceWarning) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(usageError = safeError(e)) } }
        finally { mutable.update { it.copy(refreshing = false) } }
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
        if (loginJob?.isActive == true || state.value.disconnecting) return
        mutable.update { it.copy(loginLoading = true, loginError = null, demo = false, usage = null, previous = null, feed = null) }
        publicQueue.request()
        loginJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val code = auth.begin()
                mutable.update { it.copy(deviceCode = code) }
                auth.complete(code)
                mutable.update { it.copy(connected = true, loginLoading = false, deviceCode = null, usageError = null) }
                privateQueue.request()
                publicQueue.request()
            } catch (e: TimeoutCancellationException) {
                mutable.update { it.copy(loginError = "Anmeldecode abgelaufen. Bitte neu verbinden.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(loginError = "Anmeldung nicht möglich. Gerätecode-Anmeldung in den Codex-Sicherheitseinstellungen aktivieren. ${safeError(e)}") } }
            finally { mutable.update { it.copy(loginLoading = false, deviceCode = null) } }
        }
    }
    fun cancelLogin() { loginJob?.cancel(); mutable.update { it.copy(loginLoading = false, deviceCode = null) } }
    fun disconnect() {
        cancelLogin()
        privateQueue.cancel()
        val oldPersonalJob = personalScope.coroutineContext[Job]
        personalScope.cancel()
        mutable.update { it.copy(connected = false, disconnecting = true, demo = false, usage = null, previous = null, observations = emptyList(), refreshing = false, usageError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            loginJob?.join()
            oldPersonalJob?.join()
            try { store.clear() }
            catch (_: Exception) { mutable.update { it.copy(usageError = "Lokale Anmeldung konnte nicht vollständig gelöscht werden.") } }
            usageRepository = UsageRepository(auth)
            personalScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
            privateQueue = RefreshQueue(personalScope) { withContext(Dispatchers.IO) { readUsage() } }
            mutable.update { it.copy(disconnecting = false) }
        }
    }
    fun demo() {
        if (state.value.connected || state.value.disconnecting) return
        cancelLogin()
        mutable.update { it.copy(demo = true, usage = demoUsage(), previous = null, observations = emptyList(), usageError = null,
            feed = demoFeed(it.source), feedError = null, loginError = null) }
    }
    fun exitDemo() {
        mutable.update { it.copy(demo = false, usage = null, previous = null, feed = null) }
        publicQueue.request()
    }
    fun setSource(source: ResetSource) {
        prefs.edit().putString("source", source.name).apply()
        mutable.update { it.copy(source = source, feed = if (it.demo) demoFeed(source) else null, feedError = null, nextFeedSeconds = 0) }
        if (!state.value.demo) publicQueue.request()
    }
    fun setTheme(theme: ThemeMode) { prefs.edit().putString("theme", theme.name).apply(); mutable.update { it.copy(theme = theme) } }
    fun setDynamic(enabled: Boolean) { prefs.edit().putBoolean("dynamic", enabled).apply(); mutable.update { it.copy(dynamicColor = enabled) } }
    fun setAmoled(enabled: Boolean) { prefs.edit().putBoolean("amoled", enabled).apply(); mutable.update { it.copy(amoled = enabled) } }
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
