package com.madebycli.aiusage

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.madebycli.aiusage.ui.UsageApp

class MainActivity : ComponentActivity() {
    private var notificationEvent by mutableLongStateOf(0)
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("notificationEvent", notificationEvent); super.onSaveInstanceState(outState) }
    private var notificationAccount by mutableStateOf<String?>(null)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); notificationAccount = intent.getStringExtra("account"); notificationEvent++ }
    private val model: UsageViewModel by viewModels()
    override fun onResume() { super.onResume(); model.foregroundRefresh() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationAccount = intent.getStringExtra("account")
        notificationEvent = savedInstanceState?.getLong("notificationEvent") ?: if (notificationAccount != null) 1L else 0L
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val dark = when (state.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AniHyouTheme(darkTheme = dark, dynamicColor = state.dynamicColor, blackColors = state.amoled) {
                // The window behind the app has the app's own background, so no light flash can show through.
                val background = androidx.compose.material3.MaterialTheme.colorScheme.background
                androidx.compose.runtime.SideEffect { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.toArgb())) }
                UsageApp(state, model, notificationAccount, notificationEvent)
            }
        }
    }
}
