package com.madebycli.aiusage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.ProviderState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

/** Both providers use the same card; signed-out users also see a short welcome header. */
@Composable
internal fun AccountsScreen(state: UiState, model: UsageViewModel) {
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!state.anyConnected) Welcome()
        Providers.all.forEach { name ->
            AccountCard(name, state.provider(name), connect = { model.connect(name) }, disconnect = { confirm = name },
                reload = { model.refresh(name) }, reset = { model.disconnect(name) })
        }
    }
    confirm?.let { name ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("$name trennen?") },
            text = { Text("Die Anmeldung und die gespeicherten Messungen dieses Anbieters werden auf dem Gerät gelöscht.") },
            confirmButton = { TextButton(onClick = { model.disconnect(name); confirm = null }) { Text("Trennen") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } })
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Providers.all.forEach { BrandTile(it) } }
        Text("Dein Verbrauch.\nDein Überblick.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Verbinde Codex und Claude Code mit deinem Konto. Die Daten kommen direkt vom Anbieter.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AccountCard(name: String, provider: ProviderState, connect: () -> Unit, disconnect: () -> Unit, reload: () -> Unit, reset: () -> Unit) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandIcon(name)
            Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (provider.connected) Icon(Icons.Rounded.CheckCircle, "Verbunden", tint = MaterialTheme.colorScheme.primary)
        }
        if (provider.connected) {
            Text(if (provider.usage == null) "Angemeldet · Verbrauch noch nicht geladen" else "Angemeldet · Verbrauch geladen", style = MaterialTheme.typography.bodySmall)
            provider.error?.let { Notice(it, true) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (provider.error != null) OutlinedButton(onClick = reload, enabled = !provider.refreshing) { Text("Verbrauch erneut laden") }
                else OutlinedButton(onClick = connect, enabled = !provider.disconnecting) { Text("Neu anmelden") }
                TextButton(onClick = disconnect, enabled = !provider.disconnecting) { Text("Trennen", color = MaterialTheme.colorScheme.error) }
            }
        } else {
            provider.error?.let { Notice(it, true) }
            FilledTonalButton(onClick = connect, enabled = !provider.disconnecting && !provider.login.busy, modifier = Modifier.fillMaxWidth()) { Text("Mit $name verbinden") }
            if (provider.error != null) TextButton(onClick = reset, enabled = !provider.disconnecting) { Text("Lokale Daten zurücksetzen") }
        }
    }
}
