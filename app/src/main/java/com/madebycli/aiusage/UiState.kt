package com.madebycli.aiusage

import com.madebycli.aiusage.data.*

object Providers {
    const val CODEX = "Codex"
    const val CLAUDE = "Claude Code"
    const val GEMINI = "Gemini"
    val all = listOfNotNull(CODEX, CLAUDE, GEMINI.takeIf { com.madebycli.aiusage.data.GeminiOAuth.configured })
    /** Short name for tight places such as the widget. */
    fun short(provider: String) = when (provider) { CODEX -> "Codex"; GEMINI -> "Gemini"; else -> "Claude" }
    /** Brand names stay untranslated. */
    fun label(provider: String) = provider
    /** A note for providers whose sign-in relies on undocumented endpoints that changed recently. */
    fun experimental(provider: String): Int? = if (provider == GEMINI) R.string.provider_experimental else null
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Pages of the bottom bar. The usage page is the home screen and cannot be hidden. */
enum class AppTab(@androidx.annotation.StringRes val label: Int) {
    USAGE(R.string.tab_usage), PLAN(R.string.tab_plan), STATS(R.string.tab_stats), RESETS(R.string.tab_resets)
}

enum class StartScreen(val tab: AppTab?) { LAST(null), USAGE(AppTab.USAGE), PLAN(AppTab.PLAN), STATS(AppTab.STATS), RESETS(AppTab.RESETS) }

enum class StatsRange(@androidx.annotation.StringRes val label: Int) { WEEK(R.string.range_week), MONTH(R.string.range_month), YEAR(R.string.range_year) }

/** How the home screen draws accounts. */
enum class HomeStyle { COMPACT, PLAYFUL }

/** Which bottom-bar pages exist, in which order, and where a fresh start opens. */
data class NavSettings(
    val order: List<AppTab> = AppTab.entries,
    val hidden: Set<AppTab> = emptySet(),
    val start: StartScreen = StartScreen.LAST,
    val last: AppTab = AppTab.USAGE,
    val statsRange: StatsRange = StatsRange.MONTH,
    val statsMetric: StatsMetric = StatsMetric.FIVE
) {
    /** Visible pages. The home screen is always among them. */
    val visible: List<AppTab> get() = (order + AppTab.entries).distinct().filter { it == AppTab.USAGE || it !in hidden }
    /** The page a fresh start opens on, falling back to the home screen if it is hidden. */
    val initial: AppTab get() = (start.tab ?: last).takeIf { it in visible } ?: AppTab.USAGE
}

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
    val error: String? = null,
    /** Set when an existing account signs in again instead of a new one being added. */
    val replaceAccountId: String? = null
)

data class AccountState(
    val account: Account,
    val title: String,
    val usage: UsageSnapshot? = null,
    val ledger: UsageLedger = UsageLedger(),
    val refreshing: Boolean = false,
    val error: String? = null,
    val creditsLoading: Boolean = false,
    val storageWarning: String? = null
) {
    val id get() = account.id
    val provider get() = account.provider
    val plan get() = usage?.plan?.takeIf { it.isNotBlank() }
    fun windows() = usage?.windows(provider).orEmpty()
}

/** Raised after a sign-in so the UI can open the new account once. */
data class SignedIn(val accountId: String, val sequence: Long)

data class UiState(
    val accounts: List<AccountState> = emptyList(),
    /** False until the stored accounts have been read. */
    val loaded: Boolean = false,
    val logins: Map<String, LoginState> = emptyMap(),
    val signedIn: SignedIn? = null,
    val storageWarning: String? = null,
    val intel: Map<IntelSource, IntelResult> = emptyMap(), val intelLoading: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM, val dynamicColor: Boolean = false, val amoled: Boolean = false,
    val homeStyle: HomeStyle = HomeStyle.COMPACT,
    val nav: NavSettings = NavSettings()
) {
    val initializing get() = !loaded
    val anyConnected get() = accounts.isNotEmpty()
    val connectedProviders get() = Providers.all.filter { provider -> accounts.any { it.provider == provider } }
    fun accountsOf(provider: String) = accounts.filter { it.provider == provider }
    fun hasProvider(provider: String) = accounts.any { it.provider == provider }
    fun account(id: String?) = accounts.firstOrNull { it.id == id }
    fun login(provider: String) = logins[provider] ?: LoginState()
    val loginProvider: String? get() = Providers.all.firstOrNull { login(it).open }
    val refreshing get() = accounts.any { it.refreshing }
}
