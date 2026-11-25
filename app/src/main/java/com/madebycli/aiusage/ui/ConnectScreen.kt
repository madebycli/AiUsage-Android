@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.LoginState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.R
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.data.LoginPhase

/** One sign-in page for every provider: the same cards, order and actions, only the steps differ. */
@Composable
internal fun ConnectScreen(provider: String, state: UiState, model: UsageViewModel) {
    val login = state.login(provider)
    val replacing = state.account(login.replaceAccountId)
    val uri = LocalUriHandler.current
    var browserError by remember { mutableStateOf(false) }
    fun openBrowser(url: String) { browserError = runCatching { uri.openUri(url) }.isFailure }
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ProviderBadge(provider, 56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(when (provider) { Providers.CODEX -> R.string.connect_lead_codex; Providers.CLAUDE -> R.string.connect_lead_claude; else -> R.string.connect_lead_gemini }),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val note = when {
                    replacing != null -> stringResource(R.string.connect_again_for, replacing.title)
                    state.accountsOf(provider).isNotEmpty() -> stringResource(R.string.connect_another, Providers.label(provider))
                    else -> null
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                Providers.experimental(provider)?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
            }
        }
        when (provider) {
            Providers.CODEX -> CodexSteps(login, ::openBrowser)
            Providers.CLAUDE -> ClaudeSteps(login, model, ::openBrowser)
            else -> GeminiSteps(login, model, ::openBrowser)
        }
        if (login.busy) {
            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(login.phase.label), style = MaterialTheme.typography.bodySmall)
        }
        login.hint?.let { Notice(it) }
        login.error?.let { Notice(it, true) }
        if (browserError) Notice(stringResource(R.string.connect_browser_failed), true)
        if (provider == Providers.CODEX && login.error != null && !login.busy && login.url == null)
            FilledTonalButton(onClick = { model.connect(provider, login.replaceAccountId) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_new_code)) }
        if (provider == Providers.CLAUDE) ClaudeImport(login, model)
        TextButton(onClick = { model.cancelLogin(provider) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun StepCard(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    SoftCard(PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie6Sided.toShape()), contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
        }
        content()
    }
}

@Composable
private fun CodexSteps(login: LoginState, openBrowser: (String) -> Unit) {
    val context = LocalContext.current
    val code = login.deviceCode?.takeIf { login.phase == LoginPhase.WAITING }
    val label = stringResource(R.string.connect_code)
    fun copy(value: String) = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, value))
    StepCard(1, stringResource(R.string.connect_code)) {
        if (code == null) Text(stringResource(if (login.busy) R.string.connect_requesting else R.string.connect_no_code), style = MaterialTheme.typography.bodySmall)
        else {
            Text(code, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedButton(onClick = { copy(code) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_copy)) }
            Text(stringResource(R.string.connect_valid_15), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    StepCard(2, stringResource(R.string.connect_approve)) {
        Text(stringResource(R.string.connect_approve_text), style = MaterialTheme.typography.bodySmall)
        OrionCtaButton(stringResource(R.string.connect_open_browser), { code?.let(::copy); login.url?.let(openBrowser) }, enabled = login.url != null && code != null)
    }
}

@Composable
private fun ClaudeSteps(login: LoginState, model: UsageViewModel, openBrowser: (String) -> Unit) {
    var code by remember(login.url) { mutableStateOf("") }
    StepCard(1, stringResource(R.string.connect_claude_step)) {
        Text(stringResource(R.string.connect_claude_step_text), style = MaterialTheme.typography.bodySmall)
        if (login.url != null) OrionCtaButton(stringResource(R.string.connect_claude_open), { openBrowser(login.url) }, enabled = !login.busy)
        else if (!login.busy) OrionCtaButton(stringResource(R.string.connect_restart), { model.connect(Providers.CLAUDE, login.replaceAccountId) })
    }
    if (login.url != null) StepCard(2, stringResource(R.string.connect_paste)) {
        Text(stringResource(R.string.connect_paste_text), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(code, { code = it.take(4096) }, enabled = !login.busy, label = { Text(stringResource(R.string.connect_code)) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OrionCtaButton(stringResource(R.string.connect_finish), { model.completeClaudeLogin(code); code = "" }, enabled = code.isNotBlank() && !login.busy)
    }
}

@Composable
private fun GeminiSteps(login: LoginState, model: UsageViewModel, openBrowser: (String) -> Unit) {
    var address by remember(login.url) { mutableStateOf("") }
    StepCard(1, stringResource(R.string.connect_gemini_step)) {
        Text(stringResource(R.string.connect_gemini_step_text), style = MaterialTheme.typography.bodySmall)
        if (login.url != null) OrionCtaButton(stringResource(R.string.connect_gemini_open), { openBrowser(login.url) }, enabled = !login.busy || login.phase == LoginPhase.WAITING)
        else if (!login.busy) OrionCtaButton(stringResource(R.string.connect_restart), { model.connect(Providers.GEMINI, login.replaceAccountId) })
    }
    if (login.url != null) StepCard(2, stringResource(R.string.connect_gemini_paste)) {
        Text(stringResource(R.string.connect_gemini_paste_text), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(address, { address = it.take(4096) }, label = { Text(stringResource(R.string.connect_gemini_field)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
        OrionCtaButton(stringResource(R.string.connect_finish), { model.completeGeminiLogin(address); address = "" }, enabled = address.isNotBlank(), tonal = true)
    }
}

@Composable
private fun ClaudeImport(login: LoginState, model: UsageViewModel) {
    var secret by remember { mutableStateOf("") }
    Disclosure(stringResource(R.string.connect_import)) {
        Text(stringResource(R.string.connect_import_text), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(secret, { secret = it.take(65_536) }, enabled = !login.busy, label = { Text(stringResource(R.string.connect_import_field)) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(), maxLines = 3)
        OutlinedButton(onClick = { model.importClaude(secret); secret = "" }, enabled = secret.isNotBlank() && !login.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_import_use))
        }
    }
}

/** First start: the app's hero and one button per provider. */
@Composable
internal fun WelcomeScreen(state: UiState, model: UsageViewModel) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        item(key = "hero") {
            OrionPageHero(Icons.Rounded.DonutLarge, stringResource(R.string.welcome_title), stringResource(R.string.welcome_text),
                MaterialTheme.colorScheme.primary, MaterialShapes.SoftBurst, topInset = statusBar + 24.dp)
        }
        settingsItem("providers", 1) {
            SettingsGroup {
                Providers.all.forEach { provider ->
                    SettingsItem(stringResource(R.string.accounts_connect, Providers.label(provider)), { model.connect(provider) }, enabled = !state.login(provider).busy,
                        subtitle = Providers.experimental(provider)?.let { stringResource(it) }, leading = { ProviderBadge(provider) }) { Icon(Icons.Rounded.Add, null) }
                }
            }
        }
    }
}
