package com.madebycli.aiusage.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.AccountState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

/** Connected accounts with rename, sign in again and disconnect, plus a way to add more of either provider. */
@Composable
internal fun AccountsScreen(state: UiState, model: UsageViewModel, sort: (() -> Unit)? = null) {
    var confirm by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.widthIn(max = 560.dp).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!state.anyConnected) item(key = "welcome") { Welcome() }
        itemsIndexed(state.accounts, key = { _, entry -> entry.id }) { index, entry ->
            AccountCard(entry, state.login(entry.provider).busy, Modifier.animateItem(),
                rename = { renaming = entry.id }, signInAgain = { model.connect(entry.provider, entry.id) }, disconnect = { confirm = entry.id },
                reload = { model.refresh(entry.id) })
        }
        if (state.accounts.size > 1 && sort != null) item(key = "sort") {
            OutlinedButton(onClick = sort, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Reihenfolge ändern") }
        }
        item(key = "add") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.animateContentSize()) {
                if (state.anyConnected) SectionTitle("Account hinzufügen")
                Providers.all.forEach { provider ->
                    FilledTonalButton(onClick = { model.connect(provider) }, enabled = !state.login(provider).busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        BrandIcon(provider, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                        Text(if (state.accountsOf(provider).isEmpty()) "Mit $provider verbinden" else "Weiteren $provider-Account hinzufügen")
                    }
                }
                Text("Du kannst mehrere Accounts desselben Anbieters verbinden, zum Beispiel privat und beruflich.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    confirm?.let { id ->
        val entry = state.account(id)
        if (entry == null) confirm = null
        else AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("${entry.title} trennen?") },
            text = { Text("Die Anmeldung und die gespeicherten Messungen und Statistiken dieses Accounts werden auf dem Gerät gelöscht. Andere Accounts bleiben unberührt.") },
            confirmButton = { TextButton(onClick = { model.removeAccount(id); confirm = null }) { Text("Trennen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } })
    }
    renaming?.let { id ->
        val entry = state.account(id)
        if (entry == null) renaming = null else RenameDialog(entry, { renaming = null }) { model.renameAccount(id, it); renaming = null }
    }
}

@Composable
internal fun RenameDialog(entry: AccountState, dismiss: () -> Unit, save: (String?) -> Unit) {
    var text by remember(entry.id) { mutableStateOf(entry.account.label.orEmpty()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Account umbenennen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(40) }, singleLine = true, label = { Text("Name") },
                    placeholder = { Text(entry.account.detail ?: entry.provider) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                Text("Leer lassen, um den Standardnamen zu verwenden.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { save(text.ifBlank { null }) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Abbrechen") } })
}

@Composable
private fun Welcome() {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Providers.all.forEach { BrandTile(it) } }
        Text("Dein Verbrauch.\nDein Überblick.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Verbinde Codex und Claude Code mit deinem Konto, auch mehrere pro Anbieter. Die Daten kommen direkt vom Anbieter.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCard(entry: AccountState, loginBusy: Boolean, modifier: Modifier, rename: () -> Unit, signInAgain: () -> Unit, disconnect: () -> Unit, reload: () -> Unit) {
    SoftCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandIcon(entry.provider)
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = listOfNotNull(entry.provider, entry.plan, entry.account.detail?.takeIf { it != entry.title }).joinToString(" · ")
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (entry.error != null) Icon(Icons.Rounded.ErrorOutline, "Fehler", tint = MaterialTheme.colorScheme.error)
            else Icon(Icons.Rounded.CheckCircle, "Verbunden", tint = MaterialTheme.colorScheme.primary)
        }
        Text(if (entry.usage == null) "Angemeldet · Verbrauch noch nicht geladen" else "Angemeldet · Verbrauch geladen", style = MaterialTheme.typography.bodySmall)
        entry.error?.let { Notice(it, true) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (entry.error != null) OutlinedButton(onClick = reload, enabled = !entry.refreshing) { Text("Erneut laden") }
            OutlinedButton(onClick = signInAgain, enabled = !loginBusy) { Text("Neu anmelden") }
            TextButton(onClick = rename) { Text("Umbenennen", maxLines = 1) }
            TextButton(onClick = disconnect) { Text("Trennen", color = MaterialTheme.colorScheme.error, maxLines = 1) }
        }
    }
}
