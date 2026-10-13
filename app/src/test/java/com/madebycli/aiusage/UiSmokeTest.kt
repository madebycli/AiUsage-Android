package com.madebycli.aiusage

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.madebycli.aiusage.data.*
import com.madebycli.aiusage.ui.UsageApp
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.SynchronousExecutor
import org.junit.Before
import org.junit.After
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
    @Before fun initializeBackgroundScheduler() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(),
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun clearModels() = models.clear()
    @Test fun `signed out screen has no quotas preview navigation or public data`() {
        val model = UsageViewModel(ApplicationProvider.getApplicationContext())
        models.put("usage", model)
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(UiState(initializing = false, claudeInitializing = false), model) } }
        compose.onNodeWithText("Mit Codex verbinden").assertIsDisplayed()
        compose.onNodeWithText("Mit Claude Code verbinden").assertIsDisplayed()
        compose.onNodeWithText("Verbrauch").assertDoesNotExist()
        compose.onNodeWithText("Resets").assertDoesNotExist()
        compose.onNodeWithText("Erst das Design ansehen").assertDoesNotExist()
        compose.onNodeWithText("5 Stunden").assertDoesNotExist()
        screenshot("login-light")
    }
    @Test fun `connected providers forecasts theme and account route work`() {
        val model = UsageViewModel(ApplicationProvider.getApplicationContext())
        models.put("usage", model)
        val now = Instant.now().epochSecond
        val usage = UsageSnapshot("Plus", listOf(QuotaGroup("Codex", listOf(UsageWindow(21.0, 18000, now + 9800), UsageWindow(36.0, 604800, now + 330000)))), now, 1)
        val claude = UsageSnapshot("Max 5x", listOf(QuotaGroup("Claude Code", listOf(UsageWindow(42.0, 18000, now + 7600), UsageWindow(18.0, 604800, now + 230000)))), now)
        val screen = mutableStateOf(UiState(initializing = false, claudeInitializing = false, connected = true, usage = usage))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = screen.value.theme == ThemeMode.DARK) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Claude Code").assertDoesNotExist()
        compose.onNodeWithText("5 Stunden").assertIsDisplayed()
        screenshot("usage-light")
        compose.runOnIdle { screen.value = screen.value.copy(claudeConnected = true, claudeUsage = claude) }
        screenshot("overview-both")
        compose.onAllNodesWithText("Claude Code")[0].performClick()
        compose.onNodeWithText("Max 5x").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").assertIsDisplayed()
        compose.onNodeWithText("Verbrauch").assertDoesNotExist()
        compose.onNodeWithContentDescription("Verbrauchsdetails öffnen").assertDoesNotExist()
        screenshot("claude-light")
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(forecasts = ResetSource.entries.associateWith { source -> ForecastResult(ResetForecast(source, if (source == ResetSource.CODEX_RESET) listOf(ForecastChance(24, 19.0), ForecastChance(48, 34.0)) else listOf(ForecastChance(6, 3.2, 1.1, 9.7), ForecastChance(24, 27.0, 13.6, 45.2)), now, now, now - 86400)) }) }
        compose.onNode(hasText("Resets") and hasClickAction()).performClick()
        compose.onNodeWithText("Reset-Chance").assertIsDisplayed()
        compose.onNodeWithText("23%").assertIsDisplayed()
        compose.onNodeWithText("codex-reset.com").assertDoesNotExist()
        compose.onNodeWithText("Quellen & Berechnung").performScrollTo().performClick()
        compose.onNodeWithText("Quellen · Codex").assertIsDisplayed()
        compose.onNodeWithText("Resets").assertDoesNotExist()
        compose.onNodeWithText("So wird gerechnet").performClick()
        screenshot("sources-light")
        compose.onNodeWithContentDescription("Zurück").performClick()
        screenshot("forecast-light")
        compose.runOnIdle { screen.value = screen.value.copy(community = CommunitySource.entries.filter { it.provider == "Claude Code" }.associateWith { source ->
            CommunityResult(CommunityReport(source, ForecastChance(if (source == CommunitySource.CLAUDE_RESETS) 168 else 48, if (source == CommunitySource.CLAUDE_RESETS) 80.0 else 42.0), null, now, null, emptyList()))
        }) }
        compose.onAllNodesWithText("Claude Code")[0].performClick()
        compose.onNodeWithText("Reset-Chance").assertIsDisplayed()
        compose.onNodeWithText("in 7 Tagen").assertIsDisplayed()
        compose.onNodeWithText("in 48 Stunden").assertIsDisplayed()
        screenshot("claude-forecast-light")
        compose.onNodeWithText("Quellen & Berechnung").performScrollTo().performClick()
        compose.onNodeWithText("Quellen · Claude Code").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("in 7 Tagen").assertIsDisplayed()
        compose.runOnIdle { screen.value = screen.value.copy(theme = ThemeMode.DARK) }
        screenshot("claude-forecast-dark")
        compose.onNode(hasText("Einstellungen") and hasClickAction()).performClick()
        compose.onNodeWithText("Anmeldungen verwalten").performClick()
        compose.onNodeWithText("Anmeldungen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Verbrauch", useUnmergedTree = true).performClick()
        compose.runOnIdle { screen.value = screen.value.copy(theme = ThemeMode.DARK,
            days = (0..50).map { day -> UsageDay(java.time.LocalDate.now().minusDays(day.toLong()).toString(), (day * 11 % 100).toDouble(), 2, now - day * 86400L) }) }
        screenshot("usage-dark")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Aktivitätsverlauf"))
        compose.onNodeWithText("Monat").assertIsDisplayed()
        compose.onNodeWithText("0% · 5h-Spitze").performScrollTo()
        screenshot("calendar-dark")
        compose.onNodeWithText("Woche").performClick()
        compose.onNodeWithText("0% · 5h-Spitze").performScrollTo()
        screenshot("calendar-week-dark")
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        compose.onAllNodesWithText("Claude Code")[0].performClick()
        compose.onNodeWithText("Wie wird gemessen?").assertDoesNotExist()
        screenshot("claude-dark")
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(connected = false) }
        compose.onNode(hasText("Resets") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Codex").assertDoesNotExist()
    }
    @Test fun `phone login is a separate titled screen with code entry and no main navigation`() {
        val model = UsageViewModel(ApplicationProvider.getApplicationContext())
        models.put("usage", model)
        val screen = mutableStateOf(UiState(initializing = false, claudeInitializing = false,
            claudeLoginOpen = true, claudeLoginUrl = "https://claude.com/cai/oauth/authorize"))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Claude verbinden").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").assertIsDisplayed()
        compose.onNodeWithText("Claude im Browser öffnen").assertIsDisplayed()
        compose.onNodeWithText("Verbrauch").assertDoesNotExist()
        compose.onNodeWithText("Resets").assertDoesNotExist()
        screenshot("claude-phone-login")
        compose.onNodeWithText("Anmeldecode").performScrollTo().performTextInput("code#state")
        compose.onNodeWithText("Anmeldung abschließen").performScrollTo().assertIsEnabled()
        compose.runOnIdle { screen.value = screen.value.copy(claudeLoginBusy = true) }
        compose.onNodeWithText("Anmeldung abschließen").assertIsNotEnabled()
        compose.runOnIdle { screen.value = screen.value.copy(claudeLoginBusy = false, claudeLoginUrl = null,
            claudeLoginError = "Browser-Freigabe konnte nicht übernommen werden. Bitte neu anmelden.") }
        compose.onNodeWithText("Neue Anmeldung starten").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Anmeldecode").assertDoesNotExist()
    }
    @Test fun `history has real source filters and notification settings cover both providers`() {
        val model = UsageViewModel(ApplicationProvider.getApplicationContext())
        models.put("usage", model)
        val now = Instant.now().epochSecond
        val feed = ResetFeed(ResetSource.CODEX_RESET, listOf(ResetEvent("api", now, "API-Meldung", "Only API", null)), null, now)
        val report = CommunityReport(CommunitySource.VERCEL_CODEX, ForecastChance(48, 20.0), null, now, null,
            listOf(ResetEvent("tracker", now, "Tracker-Meldung", "Only tracker", null)))
        val screen = mutableStateOf(UiState(initializing = false, claudeInitializing = false, connected = true, claudeConnected = true,
            feed = feed, community = mapOf(CommunitySource.VERCEL_CODEX to CommunityResult(report))))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = true) { UsageApp(screen.value, model) } }
        compose.onNode(hasText("Resets") and hasClickAction()).performClick()
        compose.onNodeWithText("Reset-Verlauf").performClick()
        compose.onNodeWithText("API-Meldung").assertIsDisplayed()
        compose.onNodeWithText("Tracker-Meldung").assertDoesNotExist()
        compose.onNodeWithText("LunarWerx").performClick()
        compose.onNodeWithText("API-Meldung").assertDoesNotExist()
        compose.onNodeWithText("Noch keine Meldungen").assertIsDisplayed()
        compose.onNodeWithText("Codex Reset Tracker").performScrollTo().performClick()
        compose.onNodeWithText("Tracker-Meldung").assertIsDisplayed()
        compose.onNodeWithText("API-Meldung").assertDoesNotExist()
        screenshot("history-filter-dark")
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNode(hasText("Einstellungen") and hasClickAction()).performClick()
        compose.onNodeWithText("Benachrichtigungen & Aktualisierung").performScrollTo().performClick()
        compose.onNodeWithText("Benachrichtigungen").assertIsDisplayed()
        compose.onNodeWithText("Alle 4 Stunden").assertIsDisplayed()
        screenshot("notifications-dark")
        compose.onNodeWithText("Claude Code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Anmeldungen verwalten").assertIsDisplayed()
    }
    @Test fun `notification tap is consumed once and does not override a later Claude login`() {
        val model = UsageViewModel(ApplicationProvider.getApplicationContext())
        models.put("usage", model)
        val screen = mutableStateOf(UiState(initializing = false, claudeInitializing = false, connected = true))
        val event = mutableStateOf(1L)
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model, "Codex", event.value) } }
        compose.onNodeWithText("Codex").assertIsDisplayed()
        compose.onNodeWithText("Verbrauch").assertDoesNotExist()
        compose.onNodeWithText("Statistiken").performClick()
        compose.onNodeWithText("Statistiken · Codex").assertIsDisplayed()
        compose.runOnIdle { event.value = 2L }
        compose.onNodeWithText("Statistiken · Codex").assertDoesNotExist()
        compose.onNodeWithText("Codex").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(claudeConnected = true, claudeLoginResult = 1) }
        compose.onNodeWithText("Claude Code").assertIsDisplayed()
        compose.onNodeWithText("Verbrauch").assertDoesNotExist()
        compose.runOnIdle { event.value = 3L }
        compose.onNodeWithText("Codex").assertIsDisplayed()
    }
    @Test fun `provider daily tokens show real activity and detailed statistics without fetch counts`() {
        val model = UsageViewModel(ApplicationProvider.getApplicationContext())
        models.put("usage", model)
        val now = Instant.now().epochSecond
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val report = StatsParser.codex(CodexReport.PROFILE, """{"stats":{"lifetime_tokens":2150000000,"current_streak_days":2,"daily_usage_buckets":[{"start_date":"$today","tokens":250},{"start_date":"${today.minusDays(1)}","tokens":1750}]}}""", now)
        val screen = UiState(initializing = false, claudeInitializing = false, connected = true,
            usage = UsageSnapshot("Plus", listOf(QuotaGroup("Codex", listOf(UsageWindow(21.0, 18000, now + 1000)))), now), stats = StatsBundle(listOf(report)))
        compose.setContent { AniHyouTheme(dynamicColor = false, darkTheme = true) { UsageApp(screen, model) } }
        compose.onNodeWithText("2.000 Tokens · 2 aktive Tage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("20×").assertDoesNotExist()
        screenshot("real-activity-dark")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Statistiken"))
        compose.onNodeWithText("Statistiken").performClick()
        compose.onNodeWithText("Statistiken · Codex").assertIsDisplayed()
        compose.onNodeWithText("2.000").assertIsDisplayed()
        compose.onNodeWithText("Verbrauch").assertDoesNotExist()
        screenshot("stats-dark")
        compose.onNodeWithText("Messwert: Tokens").performClick()
        compose.onNodeWithText("Tokens insgesamt").performClick()
        compose.onNodeWithText("2,15 Mrd.").assertIsDisplayed()
        compose.onNodeWithText("2.150.000.000").assertIsDisplayed()
        screenshot("stats-large-dark")
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).performScrollToNode(hasText("Datenquellen"))
        compose.onNodeWithText("Datenquellen").performClick()
        compose.onNodeWithText("Admin-API-Key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("AI Usage").assertIsDisplayed()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun `completed Claude login opens usage details and preserves quota with optional warnings`() {
        val model = UsageViewModel(ApplicationProvider.getApplicationContext())
        models.put("usage", model)
        val screen = mutableStateOf(UiState(initializing = false, claudeInitializing = false,
            connected = true, claudeLoginOpen = true, claudeLoginUrl = "https://claude.com/cai/oauth/authorize"))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Claude verbinden").assertIsDisplayed()
        compose.runOnIdle { screen.value = screen.value.copy(claudeLoginOpen = false, claudeLoginUrl = null,
            claudeConnected = true, claudeLoginResult = 1, claudeRefreshing = true) }
        compose.onNodeWithText("Claude Code").assertIsDisplayed()
        compose.onNodeWithText("Verbrauch wird geladen").assertIsDisplayed()
        compose.onNodeWithText("Dein Verbrauch.\nDein Überblick.").assertDoesNotExist()
        compose.onNodeWithText("Verbrauch").assertDoesNotExist()
        compose.runOnIdle {
            screen.value = screen.value.copy(claudeRefreshing = false,
                claudeUsage = ClaudeParser.parse("""{"five_hour":{"utilization":42},"seven_day":{"utilization":18},"cedar_ember":{"eligible":true,"grants":"changed"},"extra_usage":{"is_enabled":true,"used_credits":434.43,"monthly_limit":600,"currency":"EUR","decimal_places":2}}""", Instant.now().epochSecond, "Pro"))
        }
        compose.onNodeWithText("42%").assertIsDisplayed()
        compose.onNodeWithText("Reset-Details konnten nicht gelesen werden.").assertIsDisplayed()
        screenshot("claude-partial-usage")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Zusatzverbrauch"))
        compose.onNodeWithText("Zusatzverbrauch").assertIsDisplayed()
        compose.onNodeWithText("4,34 EUR").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNode(hasText("Einstellungen") and hasClickAction()).performClick()
        compose.onNodeWithText("Anmeldungen verwalten").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(claudeUsage = null,
            claudeError = safeError(ClaudeUsageFormatException(ClaudeUsageSection.WINDOWS))) }
        compose.onNodeWithText("Angemeldet · Verbrauch noch nicht geladen").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Verbrauch erneut laden").performScrollTo().assertIsEnabled()
    }
}
