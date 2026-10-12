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
        compose.onNodeWithText("Claude Code").performClick()
        compose.onNodeWithText("Max 5x").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").assertIsDisplayed()
        compose.onNodeWithText("Verbrauch").assertDoesNotExist()
        compose.onNodeWithContentDescription("Verbrauchsdetails öffnen").assertDoesNotExist()
        screenshot("claude-light")
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(forecasts = ResetSource.entries.associateWith { source -> ForecastResult(ResetForecast(source, if (source == ResetSource.CODEX_RESET) listOf(ForecastChance(24, 19.0), ForecastChance(48, 34.0)) else listOf(ForecastChance(6, 3.2, 1.1, 9.7), ForecastChance(24, 27.0, 13.6, 45.2)), now, now, now - 86400)) }) }
        compose.onNode(hasText("Resets") and hasClickAction()).performClick()
        compose.onNodeWithText("Forecast").assertIsDisplayed()
        screenshot("forecast-light")
        compose.runOnIdle { screen.value = screen.value.copy(community = CommunitySource.entries.filter { it.provider == "Claude Code" }.associateWith { source ->
            CommunityResult(CommunityReport(source, ForecastChance(if (source == CommunitySource.CLAUDE_RESETS) 168 else 48, if (source == CommunitySource.CLAUDE_RESETS) 80.0 else 42.0), null, now, null, emptyList()))
        }) }
        compose.onNodeWithText("Claude Code").performClick()
        compose.onNodeWithText("Claude Forecast").assertIsDisplayed()
        compose.onNodeWithText("in 7 Tagen").assertIsDisplayed()
        compose.onNodeWithText("in 48 Stunden").assertIsDisplayed()
        screenshot("claude-forecast-light")
        compose.onNode(hasText("Einstellungen") and hasClickAction()).performClick()
        compose.onNodeWithText("Anmeldungen verwalten").performClick()
        compose.onNodeWithText("Wen verbinden\nwir heute?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Verbrauch", useUnmergedTree = true).performClick()
        compose.runOnIdle { screen.value = screen.value.copy(theme = ThemeMode.DARK) }
        screenshot("usage-dark")
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
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
