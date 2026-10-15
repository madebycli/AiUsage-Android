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
    private fun ui(codex: ProviderState = account(), claude: ProviderState = account(), feed: ResetFeed? = null,
        community: Map<CommunitySource, CommunityResult> = emptyMap()) = UiState(codex = codex, claude = claude, feed = feed, community = community)
    private fun account(connected: Boolean = false, usage: UsageSnapshot? = null, login: LoginState = LoginState(), error: String? = null) =
        ProviderState(connected = connected, initializing = false, usage = usage, login = login, error = error)
    private fun codexUsage() = UsageSnapshot("Plus", listOf(QuotaGroup("Codex", listOf(UsageWindow(21.0, 18000, now + 9800), UsageWindow(36.0, 604800, now + 330000)))), now, 1)
    private fun claudeUsage() = UsageSnapshot("Max 5x", listOf(QuotaGroup("Claude Code", listOf(UsageWindow(42.0, 18000, now + 7600), UsageWindow(18.0, 604800, now + 230000)))), now)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `signed out screen offers both providers and no navigation or data`() {
        val model = model()
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(ui(codex = account(), claude = account()), model) } }
        compose.onNodeWithText("Mit Codex verbinden").assertIsDisplayed()
        compose.onNodeWithText("Mit Claude Code verbinden").assertIsDisplayed()
        compose.onNodeWithText("Resets").assertDoesNotExist()
        compose.onNodeWithText("5 Stunden").assertDoesNotExist()
        screenshot("accounts-signed-out")
    }

    @Test fun `connected providers open details, resets and the account page`() {
        val model = model()
        val screen = mutableStateOf(ui(codex = account(true, codexUsage())))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = screen.value.theme == ThemeMode.DARK) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
        compose.onNodeWithText("5 Stunden").assertIsDisplayed()
        screenshot("usage-light")
        compose.runOnIdle { screen.value = screen.value.copy(claude = account(true, claudeUsage())) }
        screenshot("overview-both")
        compose.onAllNodesWithText("Claude Code")[0].performClick()
        compose.onNodeWithText("Max 5x").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(forecasts = ResetSource.entries.associateWith { source ->
            ForecastResult(ResetForecast(source, if (source == ResetSource.CODEX_RESET) listOf(ForecastChance(24, 19.0), ForecastChance(48, 34.0)) else listOf(ForecastChance(6, 3.2, 1.1, 9.7), ForecastChance(24, 27.0, 13.6, 45.2)), now, now, now - 86400)) }) }
        compose.onNode(hasText("Resets") and hasClickAction()).performClick()
        compose.onNodeWithText("Reset-Chance").assertIsDisplayed()
        compose.onNodeWithText("23%").assertIsDisplayed()
        compose.onNodeWithText("Quellen & Berechnung").performScrollTo().performClick()
        compose.onNodeWithText("Quellen · Codex").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(theme = ThemeMode.DARK) }
        screenshot("forecast-dark")
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.onNodeWithText("Anmeldungen").performClick()
        compose.onAllNodesWithText("Neu anmelden")[0].assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Darstellung").assertIsDisplayed()
    }

    @Test fun `Codex and Claude sign in with the same page structure`() {
        val model = model()
        val codex = LoginState(open = true, busy = true, phase = LoginPhase.WAITING, url = "https://auth.openai.com/codex/device", deviceCode = "ABCD-1234")
        val screen = mutableStateOf(ui(codex = account(login = codex), claude = account()))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Codex verbinden").assertIsDisplayed()
        compose.onNodeWithText("ABCD-1234").assertIsDisplayed()
        compose.onNodeWithText("Code kopieren").assertIsDisplayed()
        compose.onNodeWithText("Im Browser anmelden").assertIsEnabled()
        compose.onNodeWithText("Resets").assertDoesNotExist()
        screenshot("connect-codex")
        compose.runOnIdle { screen.value = screen.value.copy(codex = account(login = codex.copy(phase = LoginPhase.EXCHANGING))) }
        compose.onNodeWithText("Browser-Freigabe wird übernommen").assertIsDisplayed()
        compose.onNodeWithText("ABCD-1234").assertDoesNotExist()
        compose.runOnIdle { screen.value = screen.value.copy(codex = account(login = LoginState(open = true, error = "Der Anmeldecode ist abgelaufen. Bitte einen neuen anfordern."))) }
        compose.onNodeWithText("Der Anmeldecode ist abgelaufen. Bitte einen neuen anfordern.").assertIsDisplayed()
        compose.onNodeWithText("Neuen Code anfordern").assertIsDisplayed()
        compose.runOnIdle { screen.value = ui(codex = account(), claude = account(login = LoginState(open = true, url = "https://claude.com/cai/oauth/authorize"))) }
        compose.onNodeWithText("Claude Code verbinden").assertIsDisplayed()
        compose.onNodeWithText("Claude im Browser öffnen").assertIsDisplayed()
        compose.onNodeWithText("Anmeldecode").performScrollTo().performTextInput("code#state")
        compose.onNodeWithText("Anmeldung abschließen").performScrollTo().assertIsEnabled()
        screenshot("connect-claude")
        compose.runOnIdle { screen.value = screen.value.copy(claude = account(login = LoginState(open = true, busy = true, url = "https://claude.com/cai/oauth/authorize"))) }
        compose.onNodeWithText("Anmeldung abschließen").assertIsNotEnabled()
        compose.runOnIdle { screen.value = screen.value.copy(claude = account(login = LoginState(open = true, error = "Anmeldung abgelaufen. Bitte neu starten."))) }
        compose.onNodeWithText("Neue Anmeldung starten").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Anmeldecode").assertDoesNotExist()
    }

    @Test fun `a finished sign in opens the new account instead of leaving the page`() {
        val model = model()
        val screen = mutableStateOf(ui(codex = account(true, codexUsage()), claude = account(login = LoginState(open = true, url = "https://claude.com/cai/oauth/authorize"))))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Claude Code verbinden").assertIsDisplayed()
        compose.runOnIdle { screen.value = screen.value.copy(claude = account(true, null).copy(refreshing = true, loginResult = 1)) }
        compose.onNodeWithText("Verbrauch wird geladen").assertIsDisplayed()
        compose.onNodeWithText("Claude Code verbinden").assertDoesNotExist()
        val parsed = ClaudeParser.parse("""{"five_hour":{"utilization":42},"seven_day":{"utilization":18},"cedar_ember":{"eligible":true,"grants":"changed"},"extra_usage":{"is_enabled":true,"used_credits":434.43,"monthly_limit":600,"currency":"EUR","decimal_places":2}}""", now, "Pro")
        compose.runOnIdle { screen.value = screen.value.copy(claude = screen.value.claude.copy(refreshing = false, usage = parsed)) }
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onNodeWithText("Reset-Details konnten nicht gelesen werden.").assertIsDisplayed()
        screenshot("claude-partial-usage")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Zusatzverbrauch"))
        compose.onNodeWithText("4,34 EUR").assertIsDisplayed()
    }

    @Test fun `history filters and notification settings cover both providers`() {
        val model = model()
        val feed = ResetFeed(ResetSource.CODEX_RESET, listOf(ResetEvent("api", now, "API-Meldung", "Only API", null)), null, now)
        val report = CommunityReport(CommunitySource.VERCEL_CODEX, ForecastChance(48, 20.0), null, now, null,
            listOf(ResetEvent("tracker", now, "Tracker-Meldung", "Only tracker", null)))
        val screen = mutableStateOf(ui(codex = account(true), claude = account(true), feed = feed,
            community = mapOf(CommunitySource.VERCEL_CODEX to CommunityResult(report))))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = true) { UsageApp(screen.value, model) } }
        compose.onNode(hasText("Resets") and hasClickAction()).performClick()
        compose.onNodeWithText("Reset-Verlauf").performClick()
        compose.onNodeWithText("API-Meldung").assertIsDisplayed()
        compose.onNodeWithText("Tracker-Meldung").assertDoesNotExist()
        compose.onNodeWithText("Codex Reset Tracker").performScrollTo().performClick()
        compose.onNodeWithText("Tracker-Meldung").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.onNodeWithText("Benachrichtigungen & Aktualisierung").performClick()
        compose.onNodeWithText("Alle 3 Stunden").assertIsDisplayed()
        screenshot("notifications-dark")
        compose.onNodeWithText("Claude Code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Darstellung").assertIsDisplayed()
    }

    @Test fun `notification tap is consumed once and does not override a later sign in`() {
        val model = model()
        val screen = mutableStateOf(ui(codex = account(true, codexUsage()), claude = account()))
        val event = mutableStateOf(1L)
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model, "Codex", event.value) } }
        compose.onNodeWithText("Wöchentlich").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { event.value = 1L; screen.value = screen.value.copy(claude = account(true, claudeUsage()).copy(loginResult = 1)) }
        compose.onNodeWithText("Max 5x").assertIsDisplayed()
    }

    @Test fun `statistics are the second tab and can be switched off`() {
        val model = model()
        val screen = mutableStateOf(ui(codex = account(true, codexUsage())))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onAllNodes(hasClickAction() and (hasText("Verbrauch") or hasText("Statistiken") or hasText("Resets")))
            .assertCountEquals(3)
        compose.onNodeWithText("Einstellungen").assertDoesNotExist()
        compose.runOnIdle { screen.value = screen.value.copy(statsEnabled = false) }
        compose.onNode(hasText("Statistiken") and hasClickAction()).assertDoesNotExist()
    }

    @Test fun `Codex statistics show reported tokens and keep report tabs in order`() {
        val model = model()
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val profile = StatsParser.codex(CodexReport.PROFILE, """{"stats":{"lifetime_tokens":2150000000,"current_streak_days":2,"daily_usage_buckets":[{"start_date":"$today","tokens":250},{"start_date":"${today.minusDays(1)}","tokens":1750}]}}""", now)
        val credits = StatsParser.codex(CodexReport.CREDITS, """{"data":[]}""", now)
        val screen = ui(codex = account(true, codexUsage()).copy(stats = StatsBundle(listOf(credits, profile))))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = true) { UsageApp(screen, model) } }
        compose.onNode(hasText("Statistiken") and hasClickAction()).performClick()
        compose.onNodeWithText("2.000 Tokens · 2 aktive Tage").assertIsDisplayed()
        screenshot("stats-codex-dark")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Heute (UTC)"))
        compose.onNodeWithText("Heute (UTC)").assertIsDisplayed()
        compose.onNodeWithText("Summe der letzten 30 Tage").assertIsDisplayed()
        compose.onAllNodesWithText("Profil")[0].assertExists()
    }

    @Test fun `Claude statistics use only the limits and values Claude reported`() {
        val model = model()
        val day = java.time.LocalDate.now().toString()
        val screen = ui(claude = account(true, claudeUsage()).copy(days = listOf(UsageDay(day, 42.0, 0, now, weekly = 18.0))))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = true) { UsageApp(screen, model) } }
        compose.onNode(hasText("Statistiken") and hasClickAction()).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Aktuelle Limits"))
        compose.onNodeWithText("Aktuelle Limits").assertIsDisplayed()
        screenshot("stats-claude-dark")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("5 Std. 42% · Woche 18%"))
        compose.onNodeWithText("5 Std. 42% · Woche 18%").assertIsDisplayed()
    }
}
