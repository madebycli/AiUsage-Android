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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetRendererTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val now = Instant.parse("2026-10-24T12:00:00Z").epochSecond
    private val density = 2.625f

    private fun account(id: String, provider: String, label: String, five: Double?, week: Double?, plan: String?, fiveReset: Long? = now + 2 * 3600 + 50 * 60, weekReset: Long? = now + 3 * 86_400 + 4 * 3600) =
        WidgetAccount(Account(id, provider, "acc.$id", label), label, plan,
            listOfNotNull(five?.let { UsageWindow(it, FIVE_HOURS, fiveReset) }, week?.let { UsageWindow(it, ONE_WEEK, weekReset) }), now - 300)

    private val accounts = listOf(
        account("c1", Providers.CODEX, "Privat", 21.0, 36.0, "Plus"),
        account("a1", Providers.CLAUDE, "Claude Max", 42.0, 18.0, "Max 5x"),
        account("c2", Providers.CODEX, "Arbeit", 97.0, 64.0, "Team", fiveReset = now - 600),
        account("a2", Providers.CLAUDE, "Zweitaccount", null, null, null))

    private fun draw(name: String, config: WidgetConfig, w: Int, h: Int, list: List<WidgetAccount> = accounts, refreshing: Boolean = false): Bitmap {
        val bitmap = WidgetRenderer.render(context, config, list, w, h, now, refreshing, density)
        val directory = File("build/screenshots/widget").apply { mkdirs() }
        // Show the transparent widget on a wallpaper-like backdrop so that the rounded shape is visible.
        val shown = Bitmap.createBitmap(bitmap.width + 40, bitmap.height + 40, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(shown).apply { drawColor(Color.rgb(120, 140, 190)); drawBitmap(bitmap, 20f, 20f, null) }
        File(directory, "$name.png").outputStream().use { shown.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test fun `every style renders at every common size`() {
        val sizes = mapOf("2x1" to (110 to 55), "4x1" to (250 to 62), "2x2" to (110 to 120), "4x2" to (250 to 120), "4x3" to (250 to 190), "4x4" to (250 to 260), "2x4" to (110 to 260))
        WidgetStyle.entries.forEach { style ->
            sizes.forEach { (label, size) ->
                val bitmap = draw("${style.name.lowercase()}-$label", WidgetConfig(style = style), size.first, size.second)
                assertEquals((size.first * density).toInt(), bitmap.width)
                assertEquals("the rounded corner is transparent", 0, Color.alpha(bitmap.getPixel(0, 0)))
                assertTrue("the body is painted", Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 0)
            }
        }
    }

    @Test fun `themes opacity and traffic colours change the picture`() {
        val base = WidgetConfig(style = WidgetStyle.BARS)
        fun pixel(config: WidgetConfig, name: String) = draw(name, config, 250, 120).let { it.getPixel(it.width - 12, it.height / 2) }
        val light = pixel(base.copy(theme = WidgetTheme.LIGHT), "theme-light")
        val dark = pixel(base.copy(theme = WidgetTheme.DARK), "theme-dark")
        val black = pixel(base.copy(theme = WidgetTheme.BLACK), "theme-black")
        assertNotEquals(light, dark)
        assertNotEquals(dark, black)
        val faded = draw("opacity", base.copy(opacity = 40), 250, 120)
        assertTrue(Color.alpha(faded.getPixel(faded.width / 2, 6)) in 90..120)
        draw("traffic", base.copy(colors = WidgetColors.TRAFFIC, theme = WidgetTheme.DARK), 250, 190)
        draw("single-account", base.copy(accounts = listOf("a1")), 250, 120)
        draw("week-only-clock", base.copy(windows = WidgetWindows.WEEK, reset = ResetFormat.CLOCK), 250, 120)
        draw("large-text", base.copy(textScale = 130), 250, 190)
        draw("refreshing", base, 250, 120, refreshing = true)
    }

    /** Not an assertion: writes the picture the launcher shows in its widget list. */
    @Test fun `preview picture for the widget picker`() {
        val sample = listOf(accounts[0], accounts[1])
        val bitmap = WidgetRenderer.render(context, WidgetConfig(style = WidgetStyle.BARS, showRefresh = false), sample, 250, 250, now, false, density)
        File("build/screenshots").apply { mkdirs() }
        File("build/screenshots/widget-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.width > 0)
    }

    @Test fun `no account and no selection are explained instead of drawing nothing`() {
        draw("empty", WidgetConfig(), 250, 120, emptyList())
        draw("none-selected", WidgetConfig(accounts = listOf("missing")), 250, 120)
    }

    @Test fun `selection and order decide which accounts are shown`() {
        val picked = WidgetData.select(accounts, WidgetConfig(accounts = listOf("a1", "c1")))
        assertEquals(listOf("a1", "c1"), picked.map { it.account.id })
        assertEquals(accounts.map { it.account.id }, WidgetData.select(accounts, WidgetConfig()).map { it.account.id })
        assertEquals(listOf("c1"), WidgetData.select(accounts, WidgetConfig(accounts = listOf("gone", "c1"))).map { it.account.id })
    }

    @Test fun `configuration survives storage and is kept in range`() {
        val config = WidgetConfig(accounts = listOf("c1", "a1"), style = WidgetStyle.RINGS, opacity = 5, corner = 99, textScale = 500, theme = WidgetTheme.BLACK)
        WidgetConfigStore.write(context, 42, config)
        val read = WidgetConfigStore.read(context, 42)
        assertEquals(listOf("c1", "a1"), read.accounts)
        assertEquals(WidgetStyle.RINGS, read.style)
        assertEquals(20, read.opacity)
        assertEquals(40, read.corner)
        assertEquals(140, read.textScale)
        WidgetConfigStore.delete(context, 42)
        assertFalse(WidgetConfigStore.has(context, 42))
        assertEquals(WidgetConfig(), WidgetConfigStore.read(context, 42))
    }
}
