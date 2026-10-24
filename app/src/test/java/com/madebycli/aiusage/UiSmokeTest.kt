package com.madebycli.aiusage

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.madebycli.aiusage.data.*
import com.madebycli.aiusage.ui.SortList
import com.madebycli.aiusage.ui.UsageApp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val now = Instant.now().epochSecond

    @Before fun initializeBackgroundScheduler() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun clearModels() = models.clear()

    private fun model(): UsageViewModel = UsageViewModel(ApplicationProvider.getApplicationContext()).also { models.put("usage", it) }
    private fun codexUsage(percent: Double = 21.0) = UsageSnapshot("Plus", listOf(QuotaGroup("Codex", listOf(UsageWindow(percent, 18000, now + 9800), UsageWindow(36.0, 604800, now + 330000)))), now, 1)
    private fun claudeUsage(percent: Double = 42.0) = UsageSnapshot("Max 5x", listOf(QuotaGroup("Claude Code", listOf(UsageWindow(percent, 18000, now + 7600), UsageWindow(18.0, 604800, now + 230000)))), now)
    private fun entry(id: String, provider: String, label: String? = null, usage: UsageSnapshot? = null, ledger: UsageLedger = UsageLedger(), error: String? = null) =
        AccountState(Account(id, provider, "acc.$id", label), label ?: provider, usage, ledger, error = error)
    private fun codex(label: String? = null, usage: UsageSnapshot? = codexUsage(), id: String = "c1") = entry(id, Providers.CODEX, label, usage)
    private fun claude(label: String? = null, usage: UsageSnapshot? = claudeUsage(), id: String = "a1") = entry(id, Providers.CLAUDE, label, usage)
    private fun ui(vararg accounts: AccountState, nav: NavSettings = NavSettings(), logins: Map<String, LoginState> = emptyMap(), feed: ResetFeed? = null,
        community: Map<CommunitySource, CommunityResult> = emptyMap(), theme: ThemeMode = ThemeMode.SYSTEM) =
        UiState(accounts = accounts.toList(), loaded = true, logins = logins, feed = feed, community = community, nav = nav, theme = theme)

    /** Lazy lists only compose what is near the screen, so rows further down are scrolled to by their text. */
    private fun scrollTo(text: String) { compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text)) }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ledger(vararg values: Pair<Int, Double>): UsageLedger {
        val today = LocalDate.now()
        return UsageLedger(values.map { (ago, five) -> LedgerDay(today.minusDays(ago.toLong()).toString(), five = five, week = five / 5, fiveRuns = (five / 60).toInt().coerceAtLeast(if (five > 0) 1 else 0),
            fiveResets = (five / 60).toInt(), peak = five.coerceAtMost(100.0), reads = 4) }.sortedByDescending { it.date })
    }

    @Test fun `signed out screen offers both providers and no navigation or data`() {
        val model = model()
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(ui(), model) } }
        compose.onNodeWithText("Mit Codex verbinden").assertIsDisplayed()
        compose.onNodeWithText("Mit Claude Code verbinden").assertIsDisplayed()
        compose.onNodeWithText("Resets").assertDoesNotExist()
        compose.onNodeWithText("5 Stunden").assertDoesNotExist()
        screenshot("accounts-signed-out")
    }

    @Test fun `the home screen shows reset as a clock time with the remaining time below`() {
        val model = model()
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(ui(codex()), model) } }
        compose.onNodeWithText("5 Stunden").assertIsDisplayed()
        compose.onNodeWithText("21%").assertIsDisplayed()
        compose.onAllNodesWithText("Reset", substring = true).assertCountEquals(2)
        compose.onAllNodesWithText("Uhr", substring = true)[0].assertIsDisplayed()
        compose.onAllNodesWithText("in 2 Std.", substring = true)[0].assertIsDisplayed()
        compose.onAllNodesWithText(":", substring = true).filter(hasText("00:")).assertCountEquals(0)
        screenshot("home-light")
    }

    @Test fun `two accounts of one provider are told apart and open their own page`() {
        val model = model()
        val screen = mutableStateOf(ui(codex("Privat", codexUsage(21.0), "c1"), codex("Arbeit", codexUsage(77.0), "c2"), claude("Claude Max", claudeUsage(42.0), "a1")))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Privat").assertIsDisplayed()
        compose.onNodeWithText("Arbeit").assertIsDisplayed()
        compose.onNodeWithText("77%").assertIsDisplayed()
        screenshot("home-three-accounts")
        compose.onNodeWithText("Arbeit").performClick()
        compose.onAllNodesWithText("Nächster Reset").assertCountEquals(2)
        compose.onNodeWithText("77%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Privat").assertIsDisplayed()
    }

    @Test fun `a removed account closes its page`() {
        val model = model()
        val screen = mutableStateOf(ui(codex("Privat"), claude("Claude Max")))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Claude Max").performClick()
        compose.onNodeWithText("Max 5x", substring = true).assertExists()
        compose.runOnIdle { screen.value = ui(codex("Privat")) }
        compose.onNodeWithText("Privat").assertIsDisplayed()
        compose.onNodeWithText("Nächster Reset").assertDoesNotExist()
    }

    @Test fun `sorting mode lists accounts and the arrows change the order`() {
        var saved: List<String>? = null
        val accounts = listOf(codex("Privat", id = "c1"), codex("Arbeit", id = "c2"), claude("Claude Max", id = "a1"))
        compose.setContent { AniHyouTheme(dynamicColor = false) { SortList(accounts) { saved = it } } }
        compose.onNodeWithText("Privat").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Nach unten")[0].performClick()
        compose.waitForIdle()
        assert(saved == listOf("c2", "c1", "a1")) { "saved=$saved" }
        compose.onAllNodesWithContentDescription("Nach oben")[2].performClick()
        compose.waitForIdle()
        assert(saved == listOf("c2", "a1", "c1")) { "saved=$saved" }
        screenshot("sort-mode")
    }

    @Test fun `the sort button appears with two accounts and switches the title`() {
        val model = model()
        val screen = mutableStateOf(ui(codex("Privat")))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithContentDescription("Reihenfolge ändern").assertDoesNotExist()
        compose.runOnIdle { screen.value = ui(codex("Privat"), claude("Claude Max")) }
        compose.onNodeWithContentDescription("Reihenfolge ändern").performClick()
        compose.onNodeWithText("Reihenfolge").assertIsDisplayed()
        compose.onNodeWithText("Fertig").performClick()
        compose.onNodeWithText("AI Usage").assertIsDisplayed()
    }

    @Test fun `connected providers open resets and the account pages`() {
        val model = model()
        val screen = mutableStateOf(ui(codex("Privat")))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = screen.value.theme == ThemeMode.DARK) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
        compose.runOnIdle { screen.value = screen.value.copy(accounts = screen.value.accounts + claude("Claude Max")) }
        screenshot("overview-both")
        compose.runOnIdle { screen.value = screen.value.copy(forecasts = ResetSource.entries.associateWith { source ->
            ForecastResult(ResetForecast(source, if (source == ResetSource.CODEX_RESET) listOf(ForecastChance(24, 19.0), ForecastChance(48, 34.0)) else listOf(ForecastChance(6, 3.2, 1.1, 9.7), ForecastChance(24, 27.0, 13.6, 45.2)), now, now, now - 86400)) }) }
        compose.onNode(hasText("Resets") and hasClickAction()).performClick()
        compose.onNodeWithText("Deine nächsten Resets").assertIsDisplayed()
        scrollTo("Reset-Chance")
        compose.onNodeWithText("Reset-Chance").assertIsDisplayed()
        scrollTo("23%")
        compose.onNodeWithText("23%").assertIsDisplayed()
        scrollTo("Quellen & Berechnung")
        compose.onNodeWithText("Quellen & Berechnung").performClick()
        compose.onNodeWithText("Quellen · Codex").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(theme = ThemeMode.DARK) }
        screenshot("forecast-dark")
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.onNodeWithText("Anmeldungen").performClick()
        compose.onAllNodesWithText("Neu anmelden")[0].assertIsDisplayed()
        compose.onNodeWithText("Privat").assertIsDisplayed()
        compose.onNodeWithText("Claude Max").assertIsDisplayed()
        screenshot("accounts-page")
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Darstellung").assertIsDisplayed()
    }

    @Test fun `Codex and Claude sign in with the same page structure`() {
        val model = model()
        val codexLogin = LoginState(open = true, busy = true, phase = LoginPhase.WAITING, url = "https://auth.openai.com/codex/device", deviceCode = "ABCD-1234")
        val screen = mutableStateOf(ui(logins = mapOf(Providers.CODEX to codexLogin)))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Codex verbinden").assertIsDisplayed()
        compose.onNodeWithText("ABCD-1234").assertIsDisplayed()
        compose.onNodeWithText("Code kopieren").assertIsDisplayed()
        compose.onNodeWithText("Im Browser anmelden").assertIsEnabled()
        compose.onNodeWithText("Resets").assertDoesNotExist()
        screenshot("connect-codex")
        compose.runOnIdle { screen.value = ui(logins = mapOf(Providers.CODEX to codexLogin.copy(phase = LoginPhase.EXCHANGING))) }
        compose.onNodeWithText("Browser-Freigabe wird übernommen").assertIsDisplayed()
        compose.onNodeWithText("ABCD-1234").assertDoesNotExist()
        compose.runOnIdle { screen.value = ui(logins = mapOf(Providers.CODEX to LoginState(open = true, error = "Der Anmeldecode ist abgelaufen. Bitte einen neuen anfordern."))) }
        compose.onNodeWithText("Der Anmeldecode ist abgelaufen. Bitte einen neuen anfordern.").assertIsDisplayed()
        compose.onNodeWithText("Neuen Code anfordern").assertIsDisplayed()
        compose.runOnIdle { screen.value = ui(logins = mapOf(Providers.CLAUDE to LoginState(open = true, url = "https://claude.com/cai/oauth/authorize"))) }
        compose.onNodeWithText("Claude Code verbinden").assertIsDisplayed()
        compose.onNodeWithText("Claude im Browser öffnen").assertIsDisplayed()
        compose.onNodeWithText("Anmeldecode").performScrollTo().performTextInput("code#state")
        compose.onNodeWithText("Anmeldung abschließen").performScrollTo().assertIsEnabled()
        screenshot("connect-claude")
        compose.runOnIdle { screen.value = ui(logins = mapOf(Providers.CLAUDE to LoginState(open = true, busy = true, url = "https://claude.com/cai/oauth/authorize"))) }
        compose.onNodeWithText("Anmeldung abschließen").assertIsNotEnabled()
        compose.runOnIdle { screen.value = ui(logins = mapOf(Providers.CLAUDE to LoginState(open = true, error = "Anmeldung abgelaufen. Bitte neu starten."))) }
        compose.onNodeWithText("Neue Anmeldung starten").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Anmeldecode").assertDoesNotExist()
    }

    @Test fun `adding a second account says so and signing in again names the account`() {
        val model = model()
        val login = LoginState(open = true, url = "https://claude.com/cai/oauth/authorize")
        val screen = mutableStateOf(ui(claude("Claude Max"), logins = mapOf(Providers.CLAUDE to login)))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Weiterer Claude Code-Account").assertIsDisplayed()
        compose.runOnIdle { screen.value = ui(claude("Claude Max"), logins = mapOf(Providers.CLAUDE to login.copy(replaceAccountId = "a1"))) }
        compose.onNodeWithText("Erneute Anmeldung für Claude Max").assertIsDisplayed()
    }

    @Test fun `a finished sign in opens the new account instead of leaving the page`() {
        val model = model()
        val login = LoginState(open = true, url = "https://claude.com/cai/oauth/authorize")
        val screen = mutableStateOf(ui(codex("Privat"), logins = mapOf(Providers.CLAUDE to login)))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Claude Code verbinden").assertIsDisplayed()
        compose.runOnIdle { screen.value = ui(codex("Privat"), claude("Neu", null).copy(refreshing = true)).copy(signedIn = SignedIn("a1", 1)) }
        compose.onNodeWithText("Verbrauch wird geladen").assertIsDisplayed()
        compose.onNodeWithText("Claude Code verbinden").assertDoesNotExist()
        val parsed = ClaudeParser.parse("""{"five_hour":{"utilization":42},"seven_day":{"utilization":18},"cedar_ember":{"eligible":true,"grants":"changed"},"extra_usage":{"is_enabled":true,"used_credits":434.43,"monthly_limit":600,"currency":"EUR","decimal_places":2}}""", now, "Pro")
        compose.runOnIdle { screen.value = ui(codex("Privat"), claude("Neu", parsed)).copy(signedIn = SignedIn("a1", 1)) }
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onNodeWithText("Reset-Details konnten nicht gelesen werden.").assertIsDisplayed()
        screenshot("claude-partial-usage")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Zusatzverbrauch"))
        compose.onNodeWithText("4,34 EUR").assertIsDisplayed()
    }

    @Test fun `history filters and notification settings cover both providers`() {
        val model = model()
        val feed = ResetFeed(ResetSource.CODEX_RESET, listOf(ResetEvent("api", now, "API-Meldung", "Only API", null)), null, now)
        val report = CommunityReport(CommunitySource.VERCEL_CODEX, ForecastChance(48, 20.0), null, now, null,
            listOf(ResetEvent("tracker", now, "Tracker-Meldung", "Only tracker", null)))
        val screen = mutableStateOf(ui(codex(usage = null), claude(usage = null), feed = feed,
            community = mapOf(CommunitySource.VERCEL_CODEX to CommunityResult(report)), theme = ThemeMode.DARK))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = true) { UsageApp(screen.value, model) } }
        compose.onNode(hasText("Resets") and hasClickAction()).performClick()
        scrollTo("Reset-Verlauf")
        compose.onNodeWithText("Reset-Verlauf").performClick()
        compose.onNodeWithText("API-Meldung").assertIsDisplayed()
        compose.onNodeWithText("Tracker-Meldung").assertDoesNotExist()
        compose.onNodeWithText("Codex Reset Tracker").performScrollTo().performClick()
        compose.onNodeWithText("Tracker-Meldung").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.onNodeWithText("Benachrichtigungen & Aktualisierung").performClick()
        compose.onNodeWithText("Im Hintergrund aktualisieren").assertIsDisplayed()
        compose.onNodeWithText("3 Std.").assertIsDisplayed()
        screenshot("notifications-dark")
        compose.onNodeWithText("Claude Code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Darstellung").assertIsDisplayed()
    }

    @Test fun `notification tap opens that account once and does not override a later sign in`() {
        val model = model()
        val screen = mutableStateOf(ui(codex("Privat"), claude("Claude Max", null)))
        val event = mutableStateOf(1L)
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model, "c1", event.value) } }
        compose.onNodeWithText("Wöchentlich").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { event.value = 1L; screen.value = ui(codex("Privat"), claude("Claude Max")).copy(signedIn = SignedIn("a1", 1)) }
        compose.onNodeWithText("Max 5x", substring = true).assertIsDisplayed()
    }

    @Test fun `bottom bar follows the navigation settings and home can never disappear`() {
        val model = model()
        val screen = mutableStateOf(ui(codex("Privat")))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onAllNodes(hasClickAction() and (hasText("Verbrauch") or hasText("Statistiken") or hasText("Resets"))).assertCountEquals(3)
        compose.runOnIdle { screen.value = screen.value.copy(nav = NavSettings(hidden = setOf(AppTab.STATS, AppTab.USAGE, AppTab.RESETS))) }
        compose.onNode(hasText("Verbrauch") and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText("Statistiken") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("Resets") and hasClickAction()).assertDoesNotExist()
        compose.runOnIdle { screen.value = screen.value.copy(nav = NavSettings(order = listOf(AppTab.RESETS, AppTab.USAGE, AppTab.STATS), hidden = setOf(AppTab.STATS))) }
        compose.onAllNodes(hasClickAction() and (hasText("Verbrauch") or hasText("Resets"))).assertCountEquals(2)
        compose.onNode(hasText("Statistiken") and hasClickAction()).assertDoesNotExist()
    }

    @Test fun `a fresh start opens the configured page and falls back to home when it is hidden`() {
        assert(NavSettings().initial == AppTab.USAGE)
        assert(NavSettings(start = StartScreen.LAST, last = AppTab.RESETS).initial == AppTab.RESETS)
        assert(NavSettings(start = StartScreen.STATS, last = AppTab.RESETS).initial == AppTab.STATS)
        assert(NavSettings(start = StartScreen.STATS, hidden = setOf(AppTab.STATS)).initial == AppTab.USAGE)
        assert(NavSettings(start = StartScreen.LAST, last = AppTab.STATS, hidden = setOf(AppTab.STATS)).initial == AppTab.USAGE)
        val model = model()
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(ui(codex("Privat"), nav = NavSettings(start = StartScreen.STATS)), model) } }
        compose.onNodeWithText("Noch keine Messung").assertIsDisplayed()
    }

    @Test fun `navigation settings page lists tabs start screen and defaults`() {
        val model = model()
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(ui(codex("Privat")), model) } }
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.onNodeWithText("Navigation & Start").performClick()
        compose.onNodeWithText("Untere Leiste").assertIsDisplayed()
        compose.onNodeWithText("Immer sichtbar").assertIsDisplayed()
        compose.onNodeWithText("Zuletzt geöffnet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Statistik-Standard").performScrollTo().assertIsDisplayed()
        screenshot("navigation-settings")
    }

    @Test fun `statistics show usage resets and the busiest weekday per account`() {
        val model = model()
        val data = ledger(0 to 130.0, 1 to 60.0, 2 to 0.0, 3 to 220.0, 5 to 90.0, 9 to 140.0, 20 to 80.0)
        val screen = ui(codex("Privat", id = "c1").copy(ledger = data), claude("Claude Max", id = "a1").copy(ledger = ledger(0 to 50.0, 1 to 10.0)), nav = NavSettings(statsRange = StatsRange.MONTH))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = true) { UsageApp(screen, model) } }
        compose.onNode(hasText("Statistiken") and hasClickAction()).performClick()
        compose.onNodeWithText("Aktive Tage").assertExists()
        scrollTo("Wochentage")
        compose.onNodeWithText("Wochentage").assertIsDisplayed()
        compose.onNodeWithText("Am meisten", substring = true).assertExists()
        screenshot("stats-month-dark")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Resets"))
        compose.onAllNodesWithText("Resets").assertCountEquals(2)
        compose.onNode(hasText("Woche") and hasClickAction()).performClick()
        scrollTo("Wochentage")
        compose.onNodeWithText("Wochentage").assertIsDisplayed()
        screenshot("stats-week-dark")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Jahr"))
        compose.onNode(hasText("Jahr") and hasClickAction()).performClick()
        scrollTo("Pro Monat")
        compose.onNodeWithText("Pro Monat").assertExists()
        screenshot("stats-year-dark")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Privat"))
        compose.onNode(hasText("Privat") and hasClickAction()).performClick()
        scrollTo("Wochentage")
        compose.onNodeWithText("Wochentage").assertIsDisplayed()
    }

    @Test fun `statistics without any measurement explain how data appears`() {
        val model = model()
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(ui(codex("Privat", usage = null)), model) } }
        compose.onNode(hasText("Statistiken") and hasClickAction()).performClick()
        compose.onNodeWithText("Noch keine Messung").assertIsDisplayed()
        compose.onNodeWithText("Ein Reset-Zyklus zählt dabei nur einmal.", substring = true).assertIsDisplayed()
    }

    @Test fun `account page shows the last seven days and a way into the statistics`() {
        val model = model()
        val screen = ui(codex("Privat").copy(ledger = ledger(0 to 130.0, 1 to 60.0)))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen, model) } }
        compose.onNodeWithText("Privat").performClick()
        scrollTo("Letzte 7 Tage")
        compose.onNodeWithText("Letzte 7 Tage").assertIsDisplayed()
        scrollTo("Alle Statistiken")
        compose.onNodeWithText("Alle Statistiken").performClick()
        scrollTo("Wochentage")
        compose.onNodeWithText("Wochentage").assertIsDisplayed()
    }
}
