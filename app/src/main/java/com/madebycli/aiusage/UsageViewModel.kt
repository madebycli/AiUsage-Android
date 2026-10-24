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
        theme = enumValueOr(prefs.getString("theme", null), ThemeMode.SYSTEM),
        dynamicColor = prefs.getBoolean("dynamic", false), amoled = prefs.getBoolean("amoled", false),
        nav = readNav()
    ))
    val state = mutable.asStateFlow()

    private val intel = IntelController(viewModelScope, mutable, http)
    private val accounts = AccountsController(application, viewModelScope, mutable, http, onChanged = ::onAccountsChanged)
    private val codexLogin = CodexLogin(application, viewModelScope, mutable, http, accounts)
    private val claudeLogin = ClaudeLogin(application, viewModelScope, mutable, http, accounts)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            accounts.registry.list().forEach { runCatching { SecureStore(application, it.namespace).purgeLegacyStatistics() } }
        }
        viewModelScope.launch { AccountAccess.changes.collect { accounts.reload(it) } }
        accounts.load()
        codexLogin.resume()
        claudeLogin.resume()
    }

    private fun onAccountsChanged() {
        intel.refresh()
        syncBackground()
    }

    fun refresh() {
        accounts.refreshAll()
        intel.refresh(force = true)
    }

    fun refresh(accountId: String) = accounts.refresh(accountId)

    fun foregroundRefresh() {
        if (state.value.initializing) return
        accounts.refreshIfStale(FOREGROUND_MAX_AGE_SECONDS)
    }

    fun loadResetData() = intel.refresh()

    // ---- Accounts ---------------------------------------------------------------------------------------
    /** Starts a sign-in. With [replaceAccountId] an existing account signs in again instead of a new one being added. */
    fun connect(provider: String, replaceAccountId: String? = null) {
        if (provider == Providers.CODEX) codexLogin.start(replaceAccountId) else claudeLogin.begin(replaceAccountId)
    }
    fun cancelLogin(provider: String) { if (provider == Providers.CODEX) codexLogin.cancel() else claudeLogin.cancel() }
    fun completeClaudeLogin(code: String) = claudeLogin.complete(code)
    fun importClaude(raw: String) = claudeLogin.import(raw)
    fun removeAccount(id: String) = accounts.remove(id)
    fun renameAccount(id: String, label: String?) = accounts.rename(id, label)
    fun reorderAccounts(ids: List<String>) = accounts.reorder(ids)

    fun syncBackground() {
        val s = state.value
        if (s.initializing) return
        AccountRefresh.sync(getApplication(), s.anyConnected)
    }

    fun rescheduleAlerts() {
        val s = state.value
        viewModelScope.launch(Dispatchers.IO) {
            s.accounts.forEach { entry -> entry.usage?.let { ResetAlerts.schedule(getApplication(), entry.account, it) } }
        }
    }

    // ---- Appearance and navigation ----------------------------------------------------------------------
    fun setTheme(theme: ThemeMode) { prefs.edit { putString("theme", theme.name) }; mutable.update { it.copy(theme = theme) } }
    fun setDynamic(enabled: Boolean) { prefs.edit { putBoolean("dynamic", enabled) }; mutable.update { it.copy(dynamicColor = enabled) } }
    fun setAmoled(enabled: Boolean) { prefs.edit { putBoolean("amoled", enabled) }; mutable.update { it.copy(amoled = enabled) } }

    private fun readNav(): NavSettings {
        fun tabs(key: String) = prefs.getString(key, null)?.split(',')?.mapNotNull { name -> AppTab.entries.firstOrNull { it.name == name } }
        // The old "statistics" switch becomes a hidden tab.
        val legacyHidden = if (!prefs.getBoolean("statsEnabled", true)) setOf(AppTab.STATS) else emptySet()
        return NavSettings(
            order = (tabs("nav.order").orEmpty() + AppTab.entries).distinct(),
            hidden = (tabs("nav.hidden")?.toSet() ?: legacyHidden) - AppTab.USAGE,
            start = enumValueOr(prefs.getString("nav.start", null), StartScreen.LAST),
            last = tabs("nav.last")?.firstOrNull() ?: AppTab.USAGE,
            statsRange = enumValueOr(prefs.getString("nav.range", null), StatsRange.MONTH),
            statsMetric = enumValueOr(prefs.getString("nav.metric", null), StatsMetric.FIVE)
        )
    }

    private fun changeNav(change: NavSettings.() -> NavSettings) {
        val next = state.value.nav.change()
        prefs.edit {
            putString("nav.order", next.order.joinToString(",") { it.name })
            putString("nav.hidden", next.hidden.joinToString(",") { it.name })
            putString("nav.start", next.start.name)
            putString("nav.last", next.last.name)
            putString("nav.range", next.statsRange.name)
            putString("nav.metric", next.statsMetric.name)
        }
        mutable.update { it.copy(nav = next) }
    }

    fun setTabVisible(tab: AppTab, visible: Boolean) { if (tab != AppTab.USAGE) changeNav { copy(hidden = if (visible) hidden - tab else hidden + tab) } }
    fun setTabOrder(order: List<AppTab>) = changeNav { copy(order = (order + AppTab.entries).distinct()) }
    fun setStartScreen(start: StartScreen) = changeNav { copy(start = start) }
    fun setLastTab(tab: AppTab) { if (tab != state.value.nav.last) changeNav { copy(last = tab) } }
    fun setStatsRange(range: StatsRange) = changeNav { copy(statsRange = range) }
    fun setStatsMetric(metric: StatsMetric) = changeNav { copy(statsMetric = metric) }

    private inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
