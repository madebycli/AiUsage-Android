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
import java.time.ZoneId

/**
 * The real app UI with realistic data: several weeks of 5-hour cycles fed through the usage tracker. Every screen is
 * rendered to build/screenshots in the German locale (the default of the device it is tested for); a few in English.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-w411dp-h891dp-420dpi")
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

    /** Three weeks of work at habitual hours; every cycle is read shortly after it starts and shortly before it ends. */
    private fun history(provider: String, seed: Int, weekPerLimit: Double): Pair<UsageLedger, UsageSnapshot> {
        val zone = ZoneId.systemDefault()
        val random = kotlin.random.Random(seed)
        var ledger = UsageLedger()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        var weekEnd = today.minusDays(20).atTime(17, 0).atZone(zone).toEpochSecond() + ONE_WEEK
        var weekly = 0.0
        for (dayAgo in 20 downTo 1) {
            val day = today.minusDays(dayAgo.toLong())
            for (hour in listOf(8, 14, 20)) {
                if (random.nextInt(10) < 3) continue
                val start = day.atTime(hour, 5 + random.nextInt(50)).atZone(zone).toEpochSecond()
                if (start >= weekEnd) { weekEnd += ONE_WEEK; weekly = 0.0 }
                val end = start + FIVE_HOURS
                val peak = (20 + random.nextInt(81)).toDouble()
                val mid = peak / 2
                weekly = (weekly + mid / 100 * weekPerLimit).coerceAtMost(100.0)
                ledger = UsageTracker.record(ledger, snap(provider, start + 900, mid to end, weekly to weekEnd), provider)
                weekly = (weekly + (peak - mid) / 100 * weekPerLimit).coerceAtMost(100.0)
                ledger = UsageTracker.record(ledger, snap(provider, end - 240, peak to end, weekly to weekEnd), provider)
            }
        }
        if (now >= weekEnd) { weekEnd += ONE_WEEK; weekly = 0.0 }
        val fiveEnd = now + 2 * 3600 + 43 * 60
        val current = snap(provider, now, 42.0 to fiveEnd, (weekly + 5) to weekEnd)
        val earlier = UsageSnapshot(null, listOf(QuotaGroup(provider, listOf(UsageWindow(20.0, FIVE_HOURS, fiveEnd), UsageWindow(weekly + 2, ONE_WEEK, weekEnd)))), now - 600)
        ledger = UsageTracker.record(ledger, earlier, provider)
        ledger = UsageTracker.record(ledger, current, provider)
        return ledger to current.copy(plan = if (provider == Providers.CODEX) "Plus" else "Max 5x", availableResets = if (provider == Providers.CODEX) 2 else null,
            resetCredits = if (provider == Providers.CODEX) listOf(ResetCredit("Reset", "available", now - 86_400, now + 9 * 86_400), ResetCredit("Reset", "available", now - 3600, now + 20 * 86_400)) else emptyList())
    }

    private fun snap(provider: String, at: Long, five: Pair<Double, Long>, week: Pair<Double, Long>) =
        UsageSnapshot(null, listOf(QuotaGroup(provider, listOf(UsageWindow(five.first, FIVE_HOURS, five.second), UsageWindow(week.first.coerceAtMost(100.0), ONE_WEEK, week.second)))), at)

    private fun account(id: String, provider: String, label: String?, seed: Int, k: Double): AccountState {
        val (ledger, usage) = history(provider, seed, k)
        return AccountState(Account(id, provider, "acc.$id", label), label ?: provider, usage, ledger)
    }

    private val accounts by lazy {
        listOf(account("c1", Providers.CODEX, "Privat", 1, 11.0), account("c2", Providers.CODEX, "Arbeit", 2, 9.0), account("a1", Providers.CLAUDE, "Claude Max", 3, 14.0))
    }

    private fun ui(vararg list: AccountState, style: HomeStyle = HomeStyle.COMPACT, theme: ThemeMode = ThemeMode.DARK) =
        UiState(accounts = list.toList(), loaded = true, theme = theme, homeStyle = style)

    private fun show(state: UiState, dark: Boolean = true): UsageViewModel {
        val model = model()
        compose.setContent { AniHyouTheme(darkTheme = dark, dynamicColor = false) { UsageApp(state, model) } }
        compose.waitForIdle()
        return model
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tab(name: String) = compose.onNode(hasText(name) and hasClickAction() and !hasSetTextAction(), useUnmergedTree = false).performClick()
    private fun scrollToText(text: String) = compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true))

    // ---- screens --------------------------------------------------------------------------------------

    @Test fun `home compact shows every account with clock time and time left`() {
        show(ui(*accounts.toTypedArray()))
        compose.onNodeWithText("Privat").assertIsDisplayed()
        compose.onAllNodesWithText("5 Stunden").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("in 2 Std.", substring = true).onFirst().assertIsDisplayed()
        screenshot("home-compact-dark")
    }

    @Test fun `home compact in light theme`() {
        show(ui(*accounts.toTypedArray(), theme = ThemeMode.LIGHT), dark = false)
        screenshot("home-compact-light")
    }

    @Test fun `home playful draws liquid shapes and the next reset`() {
        show(ui(*accounts.toTypedArray(), style = HomeStyle.PLAYFUL))
        screenshot("home-playful-dark")
    }

    @Test fun `account page opens on a tinted hero and lists recent windows`() {
        show(ui(*accounts.toTypedArray()))
        compose.onNodeWithText("Arbeit").performClick()
        compose.onAllNodesWithText("Nächster Reset").onFirst().assertIsDisplayed()
        screenshot("account-top")
        scrollToText("Letzte 5-Stunden-Fenster")
        screenshot("account-cycles")
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(0)
        compose.onNodeWithContentDescription("Zurück").performClick()
        compose.onNodeWithText("Privat").assertIsDisplayed()
    }

    @Test fun `planner recommends an account and shows a week of lanes`() {
        show(ui(*accounts.toTypedArray()))
        tab("Planer")
        compose.onNodeWithText("Jetzt nutzen").assertIsDisplayed()
        compose.onNodeWithText("Nächste 7 Tage").assertExists()
        screenshot("plan-top")
        scrollToText("Deine Startzeiten")
        screenshot("plan-bottom")
    }

    @Test fun `statistics week month and year with one row per control`() {
        show(ui(*accounts.toTypedArray()))
        tab("Statistiken")
        compose.onNodeWithText("Alle").assertIsDisplayed()
        screenshot("stats-month")
        compose.onAllNodes(hasText("Woche") and hasClickAction()).onFirst().performClick()
        screenshot("stats-week")
        compose.onNode(hasText("Jahr") and hasClickAction()).performClick()
        screenshot("stats-year")
        compose.onNode(hasText("Monat") and hasClickAction()).performClick()
        scrollToText("Wochentage")
        screenshot("stats-weekdays")
    }

    @Test fun `resets show your next resets first`() {
        show(ui(*accounts.toTypedArray()))
        tab("Resets")
        compose.onNodeWithText("Deine nächsten Resets").assertIsDisplayed()
        screenshot("resets")
    }

    // ---- settings as in Iris -------------------------------------------------------------------------

    @Test fun `settings hub and every page scroll their hero away instead of pinning a bar`() {
        show(ui(*accounts.toTypedArray()))
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.onNodeWithTag("settings-hub").assertExists()
        screenshot("settings-hub")
        compose.onNodeWithTag("settings-hub").performScrollToIndex(3)
        screenshot("settings-hub-scrolled")
        // The hero, back button included, scrolled away with the content: nothing is pinned at the top.
        compose.onNodeWithContentDescription("Zurück").assertIsNotDisplayed()
        compose.onNodeWithTag("settings-hub").performScrollToIndex(0)
        listOf("ACCOUNTS" to "Anmeldungen", "BACKGROUND" to "Hintergrund", "NOTIFICATIONS" to "Benachrichtigungen", "APPEARANCE" to "Erscheinungsbild",
            "NAVIGATION" to "Navigation & Start", "WIDGETS" to "Widgets", "ABOUT" to "Über AI Usage").forEach { (key, title) ->
            compose.onNodeWithTag("settings-hub").performScrollToNode(hasText(title))
            compose.onNode(hasText(title) and hasClickAction()).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("settings-page-$key").assertExists()
            screenshot("settings-${key.lowercase()}")
            compose.onNodeWithTag("settings-page-$key").performScrollToIndex(1)
            screenshot("settings-${key.lowercase()}-scrolled")
            compose.onNodeWithTag("settings-page-$key").performScrollToIndex(0)
            compose.onNodeWithContentDescription("Zurück").performClick()
            compose.waitForIdle()
        }
    }

    @Test fun `background page shows the log of reads`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        com.madebycli.aiusage.background.BackgroundLog.clear(context)
        listOf(com.madebycli.aiusage.background.SyncTrigger.PERIODIC, com.madebycli.aiusage.background.SyncTrigger.CYCLE_END, com.madebycli.aiusage.background.SyncTrigger.AFTER_RESET)
            .forEachIndexed { i, trigger -> com.madebycli.aiusage.background.BackgroundLog.add(context, com.madebycli.aiusage.background.BackgroundLog.Entry(now - 3600L * (3 - i), trigger, 800,
                listOf(com.madebycli.aiusage.background.BackgroundLog.Result("c1", i != 1, if (i == 1) "http-429" else null)))) }
        show(ui(*accounts.toTypedArray()))
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.onNode(hasText("Hintergrund") and hasClickAction()).performClick()
        scrollToText("Protokoll")
        compose.onNodeWithText("Fehler: http-429", substring = true).assertExists()
        screenshot("settings-background-log")
    }

    @Test fun `settings open and close with the Iris motion`() {
        compose.mainClock.autoAdvance = false
        val model = model()
        compose.setContent { AniHyouTheme(darkTheme = true, dynamicColor = false) { UsageApp(ui(*accounts.toTypedArray()), model) } }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        repeat(10) { i -> compose.mainClock.advanceTimeBy(48); screenshot("motion-settings-open-%02d".format(i)) }
        compose.mainClock.advanceTimeBy(800)
        compose.onNode(hasText("Erscheinungsbild") and hasClickAction()).performClick()
        repeat(10) { i -> compose.mainClock.advanceTimeBy(48); screenshot("motion-tile-to-page-%02d".format(i)) }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithContentDescription("Zurück").performClick()
        repeat(10) { i -> compose.mainClock.advanceTimeBy(48); screenshot("motion-page-to-hub-%02d".format(i)) }
    }

    // ---- sign-in ----------------------------------------------------------------------------------------

    @Test fun `welcome offers every provider`() {
        show(ui())
        compose.onNodeWithText("Mit Codex verbinden").assertIsDisplayed()
        compose.onNodeWithText("Mit Claude Code verbinden").assertIsDisplayed()
        compose.onNodeWithText("Statistiken").assertDoesNotExist()
        screenshot("welcome")
    }

    @Test fun `sorting lists accounts and the arrows change the order`() {
        val model = model()
        val screen = mutableStateOf(ui(*accounts.toTypedArray()))
        compose.setContent { AniHyouTheme(darkTheme = true, dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithContentDescription("Reihenfolge ändern").performClick()
        compose.onNodeWithText("Fertig").assertIsDisplayed()
        screenshot("sort-mode")
        compose.onAllNodesWithContentDescription("Nach unten").onFirst().performClick()
        compose.onNodeWithText("Fertig").performClick()
        compose.onNodeWithText("Privat").assertIsDisplayed()
    }

    @Test fun `a removed account closes its page`() {
        val model = model()
        val screen = mutableStateOf(ui(*accounts.toTypedArray()))
        compose.setContent { AniHyouTheme(darkTheme = true, dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Arbeit").performClick()
        compose.onAllNodesWithText("Nächster Reset").onFirst().assertIsDisplayed()
        screen.value = ui(accounts[0], accounts[2])
        compose.waitForIdle()
        compose.onNodeWithText("Arbeit").assertDoesNotExist()
        compose.onNodeWithText("Privat").assertIsDisplayed()
    }
}

/** The same app in English: every text comes from the resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    @Before fun init() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext(), Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun clear() = models.clear()

    @Test fun `home and settings read in English`() {
        val now = Instant.now().epochSecond
        val usage = UsageSnapshot("Plus", listOf(QuotaGroup(Providers.CODEX, listOf(UsageWindow(21.0, FIVE_HOURS, now + 9800), UsageWindow(36.0, ONE_WEEK, now + 330000)))), now)
        val state = UiState(accounts = listOf(AccountState(Account("c1", Providers.CODEX, "acc.c1", "Personal"), "Personal", usage)), loaded = true, theme = ThemeMode.DARK)
        val model = UsageViewModel(ApplicationProvider.getApplicationContext()).also { models.put("usage", it) }
        compose.setContent { AniHyouTheme(darkTheme = true, dynamicColor = false) { UsageApp(state, model) } }
        compose.onNodeWithText("5 hours").assertIsDisplayed()
        compose.onNodeWithText("in 2 h", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Usage").assertIsDisplayed()
        File("build/screenshots").mkdirs()
        File("build/screenshots/en-home.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").assertExists()
        File("build/screenshots/en-settings.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
