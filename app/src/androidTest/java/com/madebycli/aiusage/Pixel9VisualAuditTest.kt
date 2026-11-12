package com.madebycli.aiusage

import android.graphics.Bitmap
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.madebycli.aiusage.data.*
import com.madebycli.aiusage.ui.UsageApp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Real emulator screenshots of production UI. Accounts/measurements are fixtures, never live logins. */
@RunWith(AndroidJUnit4::class)
class Pixel9VisualAuditTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private val now = Instant.now().epochSecond
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val phase get() = InstrumentationRegistry.getArguments().getString("auditPhase") ?: "before"
    private var mode = "dark"

    @After fun clearModels() = models.clear()
    @Test fun darkPages() = audit(true)
    @Test fun lightPages() = audit(false)

    private fun fixture(name: String) = instrumentation.context.assets.open("intel/$name").bufferedReader().use { it.readText() }
    private fun intel(): Map<IntelSource, IntelResult> {
        val forecast = IntelParser.codexResetForecast(fixture("codex_reset_forecast.json"), now)
        val lunar = IntelParser.lunarForecast(fixture("lunarwerx_forecast.json"), now)
        return mapOf(
            IntelSource.CODEX_RESET to IntelResult(forecast.copy(publishedAt = now - 600).withHistory(IntelParser.codexResetTimeline(fixture("codex_reset_timeline.json")))),
            IntelSource.LUNARWERX to IntelResult(lunar.copy(publishedAt = now - 300).withHistory(IntelParser.lunarResets(fixture("lunarwerx_resets.json")))),
            IntelSource.CODEX_TRACKER to IntelResult(IntelParser.tracker(IntelSource.CODEX_TRACKER, fixture("tracker_codex_pingreset.html"), now).copy(publishedAt = now - 300, validUntil = now + 3600)),
            IntelSource.CLAUDE_TRACKER to IntelResult(IntelParser.tracker(IntelSource.CLAUDE_TRACKER, fixture("tracker_claude_pingreset.html"), now).copy(publishedAt = now - 300, validUntil = now + 3600)),
            IntelSource.CLAUDE_RESETS to IntelResult(IntelParser.claudeResets(fixture("claude_resets.html"), now).copy(publishedAt = now - 300, validUntil = now + 3600)))
    }
    private fun accounts(): List<AccountState> = listOf(
        Triple("private", Providers.CODEX, "Privat"), Triple("work", Providers.CODEX, "Arbeit"), Triple("claude", Providers.CLAUDE, "Claude Max")
    ).mapIndexed { index, (id, provider, label) ->
        AccountState(Account(id, provider, "fixture.$id", label), label,
            UsageSnapshot(if (provider == Providers.CODEX) "Plus" else "Max 5x", listOf(QuotaGroup(provider,
                listOf(UsageWindow(listOf(21.0, 77.0, 42.0)[index], 18000, now + 9800), UsageWindow(36.0, 604800, now + 330000)))), now))
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        check(bitmap.width == 1080 && bitmap.height == 2424) { "Expected Pixel 9 1080x2424, got ${bitmap.width}x${bitmap.height}" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        check(pixels.indices.step(251).map { pixels[it] }.distinct().size > 8) { "Blank startup frame is not a valid screenshot: $name" }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-audit/$phase/$mode").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun back() = compose.onNodeWithContentDescription("Zurück").performClick()
    private fun scrollTo(text: String) = compose.onNodeWithText(text).performScrollTo()
    private fun audit(dark: Boolean) {
        mode = if (dark) "dark" else "light"
        val activity = compose.activity
        val model = UsageViewModel(activity.application).also { models.put("visual", it) }
        activity.getSharedPreferences("appearance", 0).edit().remove("settings-order").commit()
        val state = mutableStateOf(UiState(accounts = accounts(), loaded = true, intel = intel(), theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT))
        compose.runOnUiThread {
            val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            activity.setContent { AniHyouTheme(darkTheme = dark, dynamicColor = false) { UsageApp(state.value, model) } }
        }
        capture("usage")
        compose.onNodeWithText("Privat").performClick()
        capture("account")
        back()
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        capture("settings")
        listOf("Anmeldungen" to "accounts", "Erscheinungsbild" to "appearance", "Benachrichtigungen" to "notifications",
            "Navigation & Start" to "navigation", "Widgets" to "widgets", "Über AI Usage" to "about").forEach { (title, file) ->
            compose.onNode(hasText(title) and hasClickAction()).performScrollTo().performClick()
            capture(file)
            if (file == "notifications") {
                compose.onNodeWithText("Hinweise beruhen auf", substring = true).performScrollTo()
                capture("notifications-bottom")
            }
            if (file == "navigation") {
                scrollTo("Statistik-Standard")
                capture("navigation-bottom")
            }
            if (file == "accounts") {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Account hinzufügen"))
                capture("accounts-bottom")
            }
            back()
        }
        compose.onNodeWithText("Über AI Usage").performScrollTo()
        capture("settings-bottom")
        back()
        compose.onNode(hasText("Resets") and hasClickAction()).performClick()
        capture("forecast-codex")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("In den nächsten 48 Stunden"))
        capture("forecast-codex-probabilities")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Quellen"))
        compose.onNodeWithText("Quellen").performClick()
        capture("sources-codex")
        back()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Gesamten Verlauf ansehen"))
        compose.onNodeWithText("Gesamten Verlauf ansehen").performClick()
        capture("history-codex")
        back()
        compose.onNode(hasText(Providers.CLAUDE) and hasClickAction()).performClick()
        capture("forecast-claude")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Quellen"))
        compose.onNodeWithText("Quellen").performClick()
        capture("sources-claude")
        back()
        compose.onNode(hasText("Statistiken") and hasClickAction()).performClick()
        capture("statistics")
        compose.onNode(hasText("Verbrauch") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Reihenfolge ändern").performClick()
        capture("sorting")
        compose.onNodeWithText("Fertig").performClick()
        compose.runOnIdle { state.value = state.value.copy(accounts = emptyList()) }
        capture("welcome")
        val login = LoginState(open = true, busy = true, phase = LoginPhase.WAITING, url = "https://auth.openai.com/codex/device", deviceCode = "ABCD-1234")
        compose.runOnIdle { state.value = state.value.copy(logins = mapOf(Providers.CODEX to login)) }
        capture("login-codex")
        compose.runOnIdle { state.value = state.value.copy(logins = mapOf(Providers.CLAUDE to LoginState(open = true, url = "https://claude.com/cai/oauth/authorize"))) }
        capture("login-claude")
        compose.runOnUiThread { activity.setContent { AniHyouTheme(darkTheme = dark, dynamicColor = false) {
            com.madebycli.aiusage.widget.ConfigScreen(com.madebycli.aiusage.widget.WidgetConfig(), 250, 260, {}, {})
        } } }
        capture("widget-config")
        compose.onNodeWithText("Accounts anordnen").performScrollTo()
        capture("widget-layout")
        compose.onNodeWithText("Schriftgröße").performScrollTo()
        capture("widget-appearance")
    }
}
