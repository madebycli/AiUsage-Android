@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.LoginState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel
import com.madebycli.aiusage.data.LoginPhase

/** One sign-in page for both providers: the same cards, order and actions, only the steps differ. */
@Composable
internal fun ConnectScreen(provider: String, state: UiState, model: UsageViewModel) {
    val login = state.provider(provider).login
    val uri = LocalUriHandler.current
    var browserError by remember { mutableStateOf<String?>(null) }
    fun openBrowser(url: String) {
        try { uri.openUri(url); browserError = null }
        catch (_: Exception) { browserError = "Der Browser konnte nicht geöffnet werden." }
    }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandTile(provider, 56)
            Text(if (provider == Providers.CODEX) "Melde dich bei OpenAI an und bestätige den Code." else "Melde dich bei Claude an und kopiere den angezeigten Code.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (provider == Providers.CODEX) CodexSteps(login, ::openBrowser) else ClaudeSteps(login, model, ::openBrowser)
        if (login.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(login.phase.label, style = MaterialTheme.typography.bodySmall)
        }
        login.hint?.let { Notice(it) }
        login.error?.let { Notice(it, true) }
        browserError?.let { Notice(it, true) }
        if (login.error != null && !login.busy && login.url == null)
            FilledTonalButton(onClick = { model.connect(provider) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (provider == Providers.CODEX) "Neuen Code anfordern" else "Neue Anmeldung starten")
            }
        if (provider == Providers.CLAUDE) ClaudeImport(login, model)
        TextButton(onClick = { model.cancelLogin(provider) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Abbrechen") }
    }
}

@Composable
private fun StepCard(number: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    SoftCard {
        Text("$number · $title", style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun CodexSteps(login: LoginState, openBrowser: (String) -> Unit) {
    val context = LocalContext.current
    val code = login.deviceCode?.takeIf { login.phase == LoginPhase.WAITING }
    fun copy(value: String) = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("OpenAI Anmeldecode", value))
    StepCard(1, "Code kopieren") {
        if (code == null) {
            if (!login.busy) Text("Es ist kein Code aktiv.", style = MaterialTheme.typography.bodySmall)
            else Text("Der Code wird angefordert.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(code, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedButton(onClick = { copy(code) }, modifier = Modifier.fillMaxWidth()) { Text("Code kopieren") }
            Text("Der Code gilt 15 Minuten.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    StepCard(2, "Im Browser freigeben") {
        Text("Code eingeben und die Freigabe bestätigen. Danach hierher zurückkehren, die App erkennt die Freigabe von selbst.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { code?.let(::copy); login.url?.let(openBrowser) }, enabled = login.url != null && code != null, modifier = Modifier.fillMaxWidth()) {
            Text("Im Browser anmelden")
        }
    }
}

@Composable
private fun ClaudeSteps(login: LoginState, model: UsageViewModel, openBrowser: (String) -> Unit) {
    var code by remember(login.url) { mutableStateOf("") }
    StepCard(1, "Bei Claude anmelden") {
        Text("Google oder E-Mail funktionieren direkt im Browser.", style = MaterialTheme.typography.bodySmall)
        if (login.url != null) Button(onClick = { openBrowser(login.url) }, enabled = !login.busy, modifier = Modifier.fillMaxWidth()) { Text("Claude im Browser öffnen") }
        else if (!login.busy) Button(onClick = { model.connect(Providers.CLAUDE) }, modifier = Modifier.fillMaxWidth()) { Text("Neue Anmeldung starten") }
    }
    if (login.url != null) StepCard(2, "Code einfügen") {
        Text("Den ganzen Code kopieren, einschließlich # und dem Text dahinter. Dieser Versuch gilt 10 Minuten.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(code, { code = it.take(4096) }, enabled = !login.busy, label = { Text("Anmeldecode") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { model.completeClaudeLogin(code); code = "" }, enabled = code.isNotBlank() && !login.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Anmeldung abschließen")
        }
    }
}

@Composable
private fun ClaudeImport(login: LoginState, model: UsageViewModel) {
    var secret by remember { mutableStateOf("") }
    Disclosure("Stattdessen Zugangsdaten vom Computer verwenden") {
        Text("Claude-Code-Zugangsdaten (~/.claude/.credentials.json) oder ein OAuth-Token einfügen. Die App liest sie nur und erneuert sie nicht.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(secret, { secret = it.take(65_536) }, enabled = !login.busy, label = { Text("OAuth-Token oder Zugangsdaten-JSON") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(), maxLines = 3)
        OutlinedButton(onClick = { model.importClaude(secret); secret = "" }, enabled = secret.isNotBlank() && !login.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Zugangsdaten verwenden")
        }
    }
}
