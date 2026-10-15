package com.madebycli.aiusage

import com.madebycli.aiusage.data.*

object Providers {
    const val CODEX = "Codex"
    const val CLAUDE = "Claude Code"
    val all = listOf(CODEX, CLAUDE)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** One sign-in attempt, shared by the Codex device-code and Claude PKCE flows. */
data class LoginState(
    val open: Boolean = false,
    val busy: Boolean = false,
    val phase: LoginPhase = LoginPhase.REQUEST_CODE,
    /** Page to open in the browser; null while the attempt is being prepared. */
    val url: String? = null,
    /** Codex only: the code to type into the browser page. */
    val deviceCode: String? = null,
    val hint: String? = null,
    val error: String? = null
)

data class ProviderState(
    val connected: Boolean = false,
    val initializing: Boolean = true,
    val usage: UsageSnapshot? = null,
    /** Per-day values the provider reported when the app read them. */
    val days: List<UsageDay> = emptyList(),
    val stats: StatsBundle = StatsBundle(),
    val statsLoading: Boolean = false,
    val statsError: String? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val disconnecting: Boolean = false,
    val login: LoginState = LoginState(),
    /** Incremented after every successful sign-in so the UI can open the new account. */
    val loginResult: Long = 0
)

data class UiState(
    val codex: ProviderState = ProviderState(),
    val claude: ProviderState = ProviderState(),
    /** Hides the statistics tab and stops every statistics request when switched off. */
    val statsEnabled: Boolean = true,
    val creditsLoading: Boolean = false,
    val storageWarning: String? = null,
    val feed: ResetFeed? = null, val feedLoading: Boolean = false, val feedError: String? = null,
    val source: ResetSource = ResetSource.CODEX_RESET, val nextFeedSeconds: Long = 0,
    val community: Map<CommunitySource, CommunityResult> = emptyMap(),
    val forecasts: Map<ResetSource, ForecastResult> = emptyMap(), val forecastLoading: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = false, val amoled: Boolean = false
) {
    fun provider(name: String): ProviderState = if (name == Providers.CODEX) codex else claude
    val initializing get() = codex.initializing || claude.initializing
    val anyConnected get() = codex.connected || claude.connected
    val connectedProviders get() = Providers.all.filter { provider(it).connected }
    val loginProvider: String? get() = Providers.all.firstOrNull { provider(it).login.open }
    val refreshing get() = codex.refreshing || claude.refreshing
}
