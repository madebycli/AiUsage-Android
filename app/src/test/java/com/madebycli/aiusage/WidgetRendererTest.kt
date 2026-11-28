package com.madebycli.aiusage

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.madebycli.aiusage.data.*
import com.madebycli.aiusage.widget.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

/**
 * Every widget kind and style at the sizes launchers hand out, with realistic history. Pictures go to
 * build/screenshots/widget for review; the assertions check what must hold at every size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "de-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetRendererTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val now = Instant.parse("2026-11-27T11:00:00Z").epochSecond
    private val density = 2.625f

    private fun fixture(id: String, provider: String, label: String, seed: Int, k: Double): WidgetAccount {
        val (ledger, usage) = Fixtures.history(provider, seed, k, now)
        return WidgetAccount(Account(id, provider, "acc.$id", label), label, usage.plan, usage.windows(provider), usage.fetchedAt, ledger,
            usage.availableResets)
    }

    private val accounts by lazy {
        listOf(fixture("c1", Providers.CODEX, "Privat", 1, 11.0), fixture("a1", Providers.CLAUDE, "Claude Max", 3, 14.0),
            fixture("c2", Providers.CODEX, "Arbeit", 2, 9.0),
            WidgetAccount(Account("a2", Providers.CLAUDE, "acc.a2", "Zweitaccount"), "Zweitaccount", null, emptyList(), null))
    }

    private val sizes = mapOf("2x1" to (110 to 55), "4x1" to (250 to 62), "2x2" to (110 to 120), "4x2" to (250 to 120), "3x2" to (180 to 180),
        "4x3" to (250 to 190), "4x4" to (250 to 260), "2x4" to (110 to 260), "5x3" to (320 to 210))

    private fun draw(name: String, config: WidgetConfig, w: Int, h: Int, list: List<WidgetAccount> = accounts, refreshing: Boolean = false): Bitmap {
        val bitmap = WidgetRenderer.render(context, config, list, w, h, now, refreshing, density)
        val directory = File("build/screenshots/widget").apply { mkdirs() }
        // Shown on a wallpaper-like backdrop so that the rounded shape and translucency are visible.
        val shown = Bitmap.createBitmap(bitmap.width + 40, bitmap.height + 40, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(shown).apply { drawColor(Color.rgb(96, 116, 168)); drawBitmap(bitmap, 20f, 20f, null) }
        File(directory, "$name.png").outputStream().use { shown.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun assertDrawn(bitmap: Bitmap, w: Int) {
        assertEquals((w * density).toInt(), bitmap.width)
        assertEquals("the rounded corner is transparent", 0, Color.alpha(bitmap.getPixel(0, 0)))
        assertTrue("the body is painted", Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 0)
    }

    @Test fun `every usage style renders at every common size`() {
        WidgetStyle.entries.forEach { style ->
            sizes.forEach { (label, size) ->
                assertDrawn(draw("usage-${style.name.lowercase()}-$label", WidgetConfig(style = style), size.first, size.second), size.first)
            }
        }
    }

    @Test fun `statistics planner and resets render at every common size`() {
        listOf(WidgetKind.STATS, WidgetKind.PLAN, WidgetKind.RESETS).forEach { kind ->
            sizes.forEach { (label, size) ->
                assertDrawn(draw("${kind.name.lowercase()}-$label", WidgetConfig(kind = kind), size.first, size.second), size.first)
            }
        }
    }

    @Test fun `light theme and other options`() {
        WidgetKind.entries.forEach { kind -> draw("light-${kind.name.lowercase()}", WidgetConfig(kind = kind, theme = WidgetTheme.LIGHT), 250, 190) }
        WidgetKind.entries.forEach { kind -> draw("dark-${kind.name.lowercase()}", WidgetConfig(kind = kind, theme = WidgetTheme.DARK), 250, 190) }
        WidgetKind.entries.forEach { kind -> draw("dark-large-${kind.name.lowercase()}", WidgetConfig(kind = kind, theme = WidgetTheme.DARK), 250, 260) }
        draw("usage-single", WidgetConfig(accounts = listOf("a1")), 250, 120)
        draw("usage-single-tall", WidgetConfig(accounts = listOf("c1"), style = WidgetStyle.LIQUID), 180, 180)
        draw("usage-bars-both", WidgetConfig(style = WidgetStyle.BARS, reset = ResetFormat.BOTH), 250, 260)
        draw("usage-week-only", WidgetConfig(windows = WidgetWindows.WEEK), 250, 120)
        draw("usage-traffic", WidgetConfig(style = WidgetStyle.BARS, colors = WidgetColors.TRAFFIC), 250, 190)
        draw("usage-large-text", WidgetConfig(textScale = 130), 250, 190)
        draw("usage-refreshing", WidgetConfig(), 250, 190, refreshing = true)
        draw("usage-black", WidgetConfig(theme = WidgetTheme.BLACK), 250, 190)
        draw("usage-translucent", WidgetConfig(opacity = 40), 250, 190)
        draw("stats-week-metric", WidgetConfig(kind = WidgetKind.STATS, metric = StatsMetric.WEEK), 250, 190)
        draw("stats-month", WidgetConfig(kind = WidgetKind.STATS, span = StatsSpan.MONTH), 320, 210)
        draw("resets-relative", WidgetConfig(kind = WidgetKind.RESETS, reset = ResetFormat.RELATIVE), 250, 260)
        draw("resets-week-only", WidgetConfig(kind = WidgetKind.RESETS, windows = WidgetWindows.WEEK), 250, 190)
    }

    @Test fun `themes and opacity change the picture`() {
        fun pixel(config: WidgetConfig) = WidgetRenderer.render(context, config, accounts, 250, 120, now, false, density).let { it.getPixel(it.width - 8, it.height / 2) }
        val light = pixel(WidgetConfig(theme = WidgetTheme.LIGHT))
        val dark = pixel(WidgetConfig(theme = WidgetTheme.DARK))
        val black = pixel(WidgetConfig(theme = WidgetTheme.BLACK))
        assertNotEquals(light, dark)
        assertNotEquals(dark, black)
        val faded = WidgetRenderer.render(context, WidgetConfig(opacity = 40), accounts, 250, 120, now, false, density)
        assertTrue(Color.alpha(faded.getPixel(faded.width / 2, 4)) in 90..120)
    }

    /** Not an assertion: the pictures the launcher shows in its widget list, copied to res/drawable-nodpi on release. */
    @Test fun `preview pictures for the widget picker`() {
        val sample = accounts.take(2)
        val directory = File("build/screenshots/widget-previews").apply { mkdirs() }
        mapOf("widget_preview" to WidgetKind.USAGE, "widget_preview_stats" to WidgetKind.STATS, "widget_preview_plan" to WidgetKind.PLAN,
            "widget_preview_reset" to WidgetKind.RESETS).forEach { (name, kind) ->
            val (w, h) = when (kind) { WidgetKind.RESETS -> 180 to 180; WidgetKind.USAGE -> 250 to 180; else -> 250 to 120 }
            val bitmap = WidgetRenderer.render(context, WidgetConfig(kind = kind, showRefresh = false, theme = WidgetTheme.DARK), sample, w, h, now, false, 2f)
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun `no account and no selection are explained instead of drawing nothing`() {
        WidgetKind.entries.forEach { kind ->
            draw("empty-${kind.name.lowercase()}", WidgetConfig(kind = kind), 250, 120, emptyList())
        }
        draw("none-selected", WidgetConfig(accounts = listOf("missing")), 250, 120)
        draw("stats-no-history", WidgetConfig(kind = WidgetKind.STATS), 250, 120, listOf(accounts.last()))
    }

    @Test fun `selection and order decide which accounts are shown`() {
        val picked = WidgetData.select(accounts, WidgetConfig(accounts = listOf("a1", "c1")))
        assertEquals(listOf("a1", "c1"), picked.map { it.account.id })
        assertEquals(accounts.map { it.account.id }, WidgetData.select(accounts, WidgetConfig()).map { it.account.id })
        assertEquals(listOf("c1"), WidgetData.select(accounts, WidgetConfig(accounts = listOf("gone", "c1"))).map { it.account.id })
    }

    @Test fun `configuration survives storage, is kept in range and follows its provider`() {
        val config = WidgetConfig(accounts = listOf("c1", "a1"), style = WidgetStyle.RINGS, opacity = -5, corner = 99, textScale = 500, theme = WidgetTheme.BLACK)
        WidgetConfigStore.write(context, 42, config)
        val read = WidgetConfigStore.read(context, 42)
        assertEquals(listOf("c1", "a1"), read.accounts)
        assertEquals(WidgetStyle.RINGS, read.style)
        assertEquals(0, read.opacity)
        assertEquals(40, read.corner)
        assertEquals(140, read.textScale)
        assertEquals(WidgetKind.STATS, WidgetConfigStore.read(context, 42, WidgetKind.STATS).kind)
        WidgetConfigStore.delete(context, 42)
        assertFalse(WidgetConfigStore.has(context, 42))
        assertEquals(WidgetConfig(), WidgetConfigStore.read(context, 42))
        assertEquals(ResetFormat.BOTH, WidgetConfigStore.read(context, 42, WidgetKind.RESETS).reset)
    }

    @Test fun `settings stored by version 2 still load`() {
        val old = """{"accounts":["c1"],"style":"PERCENT","preset":"CUSTOM","arrangement":"GRID","windowLayout":"STACKED","windows":"BOTH","reset":"BOTH",
            |"theme":"DARK","colors":"TRAFFIC","dynamic":false,"opacity":80,"corner":20,"textScale":110,"showRefresh":false,"showUpdated":true,"showPlan":true}""".trimMargin()
        context.getSharedPreferences("widgets", 0).edit().putString("config.77", old).commit()
        val read = WidgetConfigStore.read(context, 77, WidgetKind.USAGE)
        assertEquals(WidgetStyle.PERCENT, read.style)
        assertEquals(WidgetArrangement.GRID, read.arrangement)
        assertEquals(WidgetColors.TRAFFIC, read.colors)
        assertEquals(80, read.opacity)
        assertFalse(read.showRefresh)
        assertEquals(WidgetKind.USAGE, read.kind)
    }

    @Test fun `arrangements decide the columns`() {
        assertEquals(1, UsageWidgetDraw.columnCount(WidgetConfig(arrangement = WidgetArrangement.STACK), 600f, 100f, 4))
        assertEquals(2, UsageWidgetDraw.columnCount(WidgetConfig(arrangement = WidgetArrangement.GRID), 600f, 100f, 4))
        assertEquals(4, UsageWidgetDraw.columnCount(WidgetConfig(), 600f, 100f, 4))
        assertEquals(1, UsageWidgetDraw.columnCount(WidgetConfig(arrangement = WidgetArrangement.GRID), 90f, 100f, 4))
    }

    @Test fun `the default style fits a standard four by two widget instead of falling back to compact`() {
        WidgetStyle.entries.forEach { style ->
            assertEquals(style, WidgetRenderer.effectiveStyle(WidgetConfig(style = style), 250, 120))
        }
        assertEquals(WidgetStyle.COMPACT, WidgetRenderer.effectiveStyle(WidgetConfig(style = WidgetStyle.LIQUID), 110, 55))
        assertFalse(WidgetRenderer.hasHeader(WidgetConfig(), 250, 120))
        assertTrue(WidgetRenderer.hasHeader(WidgetConfig(kind = WidgetKind.STATS), 250, 120))
    }

    @Test fun `the resets widget lists coming resets soonest first and counts saved resets`() {
        val entries = ResetsWidgetDraw.entries(accounts, WidgetConfig(kind = WidgetKind.RESETS), now)
        assertTrue(entries.isNotEmpty())
        assertEquals(entries.sortedBy { it.at }.map { it.at }, entries.map { it.at })
        assertTrue(entries.all { it.at > now })
        assertTrue(ResetsWidgetDraw.entries(accounts, WidgetConfig(kind = WidgetKind.RESETS, windows = WidgetWindows.WEEK), now).all { it.window.seconds == ONE_WEEK })
        assertEquals(4, ResetsWidgetDraw.banked(accounts))
    }

    @Test fun `the statistics widget adds the same numbers as the statistics tab`() {
        val config = WidgetConfig(kind = WidgetKind.STATS)
        val data = StatsWidgetDraw.data(accounts, config, now)
        val zone = java.time.ZoneId.systemDefault()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val expected = LedgerStats.summarize(LedgerStats.merge(accounts.map { it.ledger }, zone), today.minusDays(6)..today, StatsMetric.FIVE).total
        assertEquals(expected, data.summary.total, 1e-9)
        assertEquals(7, data.dates.size)
        assertEquals(today, data.dates.last())
        assertEquals(data.shares.sumOf { it.second }, data.summary.total, 1e-6)
    }

    @Test fun `the planner widget recommends the same account as the planner tab`() {
        val plan = PlanWidgetDraw.plan(accounts, now)
        assertEquals(accounts.size, plan.budgets.size)
        assertTrue(plan.order.first() in accounts.map { it.id })
    }
}
