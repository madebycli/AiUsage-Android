package com.madebycli.aiusage

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.madebycli.aiusage.data.DeviceCode
import com.madebycli.aiusage.data.LoginPhase
import com.madebycli.aiusage.ui.UsageApp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class LoginUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    @After fun clearModels() { models.clear() }

    @Test fun `login result remains visible when started from settings`() {
        val model = UsageViewModel(ApplicationProvider.getApplicationContext())
        models.put("usage", model)
        val screen = mutableStateOf(UiState(initializing = false))
        compose.setContent { AniHyouTheme(dynamicColor = false) { UsageApp(screen.value, model) } }
        compose.onNodeWithText("Einstellungen", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            screen.value = screen.value.copy(loginLoading = true, loginPhase = LoginPhase.WAITING,
                deviceCode = DeviceCode("device", "ABCD-1234", 5))
        }
        compose.onNodeWithText("ABCD-1234").assertIsDisplayed()
        compose.onNodeWithText("Code kopieren").assertIsDisplayed()
        compose.runOnIdle { screen.value = screen.value.copy(loginPhase = LoginPhase.EXCHANGING) }
        compose.onNodeWithText("Browser-Freigabe wird übernommen").assertIsDisplayed()
        compose.onNodeWithText("ABCD-1234").assertDoesNotExist()
        compose.runOnIdle {
            screen.value = screen.value.copy(loginLoading = false,
                loginError = "Browser-Freigabe konnte nicht übernommen werden. HTTP 400.")
        }
        compose.onNodeWithText("Anmeldung fehlgeschlagen").assertIsDisplayed()
        compose.onNodeWithText("Browser-Freigabe konnte nicht übernommen werden. HTTP 400.").assertIsDisplayed()
        compose.onNodeWithText("Neuer Anmeldecode").assertIsDisplayed()
        compose.runOnIdle {
            screen.value = screen.value.copy(connected = true,
                loginError = "Anmeldung bestätigt, Verbrauch konnte nicht geladen werden.")
        }
        compose.onNodeWithText("Verbrauch nicht geladen").assertIsDisplayed()
        compose.onNodeWithText("Erneut aktualisieren").assertIsDisplayed()
    }
}
