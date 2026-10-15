package com.madebycli.aiusage

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.madebycli.aiusage.account.*
import com.madebycli.aiusage.background.AccountRefresh
import com.madebycli.aiusage.background.ResetAlerts
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Resuming the app only re-reads data older than this; the refresh button always reads. */
private const val FOREGROUND_MAX_AGE_SECONDS = 120L

class UsageViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("appearance", 0)
    private val http = HttpTransport()
    private val mutable = MutableStateFlow(UiState(
        source = enumValueOr(prefs.getString("source", null), ResetSource.CODEX_RESET),
        theme = enumValueOr(prefs.getString("theme", null), ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic", false), amoled = prefs.getBoolean("amoled", false)
    ))
    val state = mutable.asStateFlow()

    private val resetData = ResetDataController(viewModelScope, mutable, http)
    private val codex = CodexController(application, viewModelScope, mutable, http, onSignedIn = ::onSignedIn)
    private val claude = ClaudeController(application, viewModelScope, mutable, http, onSignedIn = ::onSignedIn)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SecureStore(application).purgeLegacyStatistics() }
            runCatching { SecureStore(application, "claude").purgeLegacyStatistics() }
        }
        viewModelScope.launch {
            AccountAccess.changes.collect { provider ->
                if (provider == Providers.CODEX) codex.reloadStoredRecord() else claude.reloadStoredRecord()
            }
        }
        codex.load()
        claude.load()
    }

    private fun onSignedIn() { resetData.refresh() }

    fun refresh() {
        codex.refresh()
        claude.refresh()
        resetData.refresh()
    }

    fun foregroundRefresh() {
        if (state.value.initializing) return
        codex.refreshIfStale(FOREGROUND_MAX_AGE_SECONDS)
        claude.refreshIfStale(FOREGROUND_MAX_AGE_SECONDS)
    }

    fun refreshStats(provider: String, force: Boolean = false) { if (provider == Providers.CODEX) codex.refreshStats(force) }
    fun loadResetData() = resetData.refresh()
    fun setSource(source: ResetSource) { prefs.edit { putString("source", source.name) }; resetData.setSource(source) }

    fun connect(provider: String) { if (provider == Providers.CODEX) codex.connect() else claude.beginLogin() }
    fun cancelLogin(provider: String) { if (provider == Providers.CODEX) codex.cancelLogin() else claude.cancelLogin() }
    fun disconnect(provider: String) { if (provider == Providers.CODEX) codex.disconnect() else claude.disconnect() }
    fun refresh(provider: String) { if (provider == Providers.CODEX) codex.refresh() else claude.refresh() }
    fun completeClaudeLogin(code: String) = claude.completeLogin(code)
    fun importClaude(raw: String) = claude.importCredential(raw)

    fun syncBackground() {
        val s = state.value
        if (!s.initializing) AccountRefresh.sync(getApplication(), s.anyConnected)
    }

    fun rescheduleAlerts() {
        val s = state.value
        viewModelScope.launch(Dispatchers.IO) {
            Providers.all.forEach { provider ->
                s.provider(provider).takeIf { it.connected }?.usage?.let { ResetAlerts.schedule(getApplication(), provider, it) }
            }
        }
    }

    fun setTheme(theme: ThemeMode) { prefs.edit { putString("theme", theme.name) }; mutable.update { it.copy(theme = theme) } }
    fun setDynamic(enabled: Boolean) { prefs.edit { putBoolean("dynamic", enabled) }; mutable.update { it.copy(dynamicColor = enabled) } }
    fun setAmoled(enabled: Boolean) { prefs.edit { putBoolean("amoled", enabled) }; mutable.update { it.copy(amoled = enabled) } }
    private inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
