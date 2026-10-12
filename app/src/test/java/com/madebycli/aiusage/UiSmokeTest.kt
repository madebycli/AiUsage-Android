package com.madebycli.aiusage

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun `demo dashboard reset list and appearance settings work`() {
        compose.onNodeWithText("Erst das Design ansehen").performClick()
        compose.onNodeWithText("Demo · keine Live-Daten").assertIsDisplayed()
        compose.onNodeWithText("5 Stunden", useUnmergedTree = true).assertIsDisplayed()
        screenshot("usage-light")
        compose.onNodeWithText("Resets", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Öffentlicher Verlauf").assertIsDisplayed()
        screenshot("resets-light")
        compose.onNodeWithText("LunarWerx").performClick()
        compose.onNodeWithText("Letztes Ledger-Ereignis").assertIsDisplayed()
        compose.onNodeWithText("Einstellungen", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Dunkel").performClick()
        compose.onNodeWithText("Verbrauch", useUnmergedTree = true).performClick()
        screenshot("usage-dark")
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File("build/screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
