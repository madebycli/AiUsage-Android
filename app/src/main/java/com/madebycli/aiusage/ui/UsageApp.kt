@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithLargeTopAppBar
import com.axiel7.anihyou.core.ui.composables.common.SmallCircularProgressIndicator
import com.madebycli.aiusage.*
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val cardShape = RoundedCornerShape(28.dp)

@Composable
fun UsageApp(state: UiState, model: UsageViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val now = rememberNow()
    val uri = LocalUriHandler.current
    DefaultScaffoldWithLargeTopAppBar(
        title = when (page) { 0 -> "AI Usage"; 1 -> "Resets"; else -> "Einstellungen" },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        scrollBehavior = scroll,
        actions = {
            FilledTonalIconButton(onClick = model::refresh, modifier = Modifier.size(48.dp)) {
                if (state.refreshing || state.feedLoading) SmallCircularProgressIndicator()
                else Icon(Icons.Rounded.Refresh, "Jetzt aktualisieren")
            }
            Spacer(Modifier.width(16.dp))
        },
        bottomBar = {
            // Reduced from AniHyou's MainBottomNavBar: domain routes and AniList actions removed.
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                listOf("Verbrauch" to Icons.Rounded.DonutLarge, "Resets" to Icons.Rounded.History, "Einstellungen" to Icons.Rounded.Tune)
                    .forEachIndexed { index, destination ->
                        NavigationBarItem(selected = page == index, onClick = { page = index },
                            icon = { Icon(destination.second, null) }, label = { Text(destination.first) })
                    }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (page) {
                0 -> UsageScreen(state, now, model)
                1 -> ResetScreen(state, now, model)
                else -> SettingsScreen(state, model)
            }
        }
    }
    if (state.loginLoading) {
        AlertDialog(onDismissRequest = model::cancelLogin,
            icon = { Icon(Icons.Rounded.Key, null) }, title = { Text("Codex verbinden") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Melde dich im Browser bei OpenAI an und gib diesen einmaligen Code ein. Die Freigabe verbindet deinen Account mit AI Usage.")
                    if (state.deviceCode == null) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
                    state.deviceCode?.let {
                        Text(it.code, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text("Code gilt 15 Minuten. Die App wartet auf deine Freigabe.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                state.deviceCode?.let { TextButton(onClick = { uri.openUri("https://auth.openai.com/codex/device") }) { Text("Im Browser anmelden") } }
            }, dismissButton = { TextButton(onClick = model::cancelLogin) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun UsageScreen(state: UiState, now: Long, model: UsageViewModel) {
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.demo) item { DemoBanner(model::exitDemo) }
        if (!state.connected && !state.demo) item {
            IntroCard(model::connect, model::demo)
        }
        state.loginError?.let { item { Notice(it, true) } }
        state.usageError?.let { item { Notice(it, true) } }
        state.storageWarning?.let { item { Notice(it) } }
        if (state.connected || state.demo) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("CODEX", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
                        Text(state.usage?.plan?.replaceFirstChar { it.titlecase() } ?: "Dein Account", style = MaterialTheme.typography.titleMedium)
                    }
                    StatusPill(if (state.demo) "Beispieldaten" else if (state.refreshing) "Wird geladen" else if (state.usageError != null) "Veraltet" else "Serverabruf",
                        if (state.usageError != null) Icons.Rounded.CloudOff else Icons.Rounded.CloudDone)
                }
            }
            if (state.usage == null) item {
                SoftCard {
                    if (state.refreshing) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
                    Text("Noch keine Verbrauchsdaten. Jetzt aktualisieren.")
                }
            }
            state.usage?.let { usage ->
                val main = usage.groups.firstOrNull { it.name == "Codex" }
                val windows = main?.windows.orEmpty()
                // Never create a zero-value 5h/weekly meter for an absent server window.
                items(windows, key = { "main:${it.seconds}" }) { window ->
                    WindowCard(window, now, usageRate(state.previous, usage, window), window.seconds == 18000L)
                }
                if (windows.none { it.seconds == 18000L }) item {
                    Notice("5-Stunden-Fenster: Der Anbieter meldet aktuell kein solches Limit.")
                }
                if (windows.none { it.seconds == 604800L }) item {
                    Notice("Wochenfenster: Der Anbieter meldet aktuell kein solches Limit.")
                }
                usage.groups.filter { it.name != "Codex" && it.windows.isNotEmpty() }.forEachIndexed { index, group ->
                    item(key = "group:$index") { SectionTitle(group.name) }
                    items(group.windows, key = { "group:$index:${it.seconds}" }) { window -> WindowCard(window, now, null, false) }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${if (state.demo) "Demo" else "Abgerufen"}: ${date(usage.fetchedAt, true)} · ${age(now - usage.fetchedAt)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { BankedCard(usage, now) }
            }
        }
        item {
            Button(onClick = model::refresh, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(10.dp))
                Text(if (state.refreshing) "Noch einen Abruf vormerken" else "Jetzt aktualisieren", style = MaterialTheme.typography.titleMedium)
            }
        }
        item { Text("Jeder Tap fragt deinen Verbrauch neu ab. Kein lokaler Verbrauchs-Cache. Der Server kann verzögert messen; Sekundenwerte sind nur Mittelwerte zwischen Abrufen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun WindowCard(window: UsageWindow, now: Long, rate: Double?, primary: Boolean) {
    val colors = MaterialTheme.colorScheme
    val exhausted = window.usedPercent >= 100
    val background = when { exhausted -> colors.errorContainer; primary -> colors.primaryContainer; else -> colors.surfaceContainer }
    val foreground = when { exhausted -> colors.onErrorContainer; primary -> colors.onPrimaryContainer; else -> colors.onSurface }
    val animated by animateFloatAsState((window.usedPercent / 100).toFloat().coerceIn(0f, 1f), label = "usage")
    Surface(shape = cardShape, color = background, contentColor = foreground, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (window.seconds == 18000L) Icons.Rounded.Timelapse else Icons.Rounded.DateRange, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp)); Text(window.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (exhausted) "Limit erreicht" else "Verbraucht", style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(percent(window.usedPercent), fontSize = 64.sp, lineHeight = 70.sp, fontWeight = FontWeight.Medium, letterSpacing = (-3).sp)
                Text(" %", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 7.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 7.dp)) {
                    Text("${percent((100 - window.usedPercent).coerceAtLeast(0.0))} %", style = MaterialTheme.typography.titleLarge)
                    Text("übrig", style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50)).background(foreground.copy(alpha = 0.12f))
                .semantics { contentDescription = "${window.title}: ${percent(window.usedPercent)} Prozent verbraucht" }) {
                if (animated > 0) Box(Modifier.fillMaxWidth(animated).fillMaxHeight().clip(RoundedCornerShape(50)).background(foreground))
            }
            window.elapsed(now)?.let { elapsed ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(foreground.copy(alpha = 0.1f))) {
                        if (elapsed > 0) Box(Modifier.fillMaxWidth(elapsed).fillMaxHeight().background(foreground.copy(alpha = 0.4f)))
                    }
                    Text("${(elapsed * 100).toInt()} % des Zeitfensters vergangen", style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider(color = foreground.copy(alpha = 0.12f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Nächster Fenster-Reset", style = MaterialTheme.typography.labelSmall)
                    Text(window.resetAt?.let { if (it <= now) "Fällig · bitte aktualisieren" else "In ${countdown(it - now)}" } ?: "Nicht gemeldet",
                        style = MaterialTheme.typography.titleMedium)
                    window.resetAt?.let { Text(date(it), style = MaterialTheme.typography.bodySmall) }
                }
                rate?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(String.format(Locale.GERMAN, "%.4f pp/s", it), style = MaterialTheme.typography.labelLarge)
                        Text("Ø zwischen Abrufen", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun BankedCard(usage: UsageSnapshot, now: Long) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ConfirmationNumber, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Deine gespeicherten Resets", style = MaterialTheme.typography.titleMedium)
                Text(usage.availableResets?.let { "$it verfügbar" } ?: "Anzahl nicht gemeldet", style = MaterialTheme.typography.bodyMedium)
            }
        }
        usage.resetCredits.filter { it.status == "available" }.forEach { credit ->
            Text(credit.title, style = MaterialTheme.typography.labelLarge)
            Text(credit.expiresAt?.let { "Gültig bis ${date(it)}${if (it <= now) " · Ablauf erreicht" else ""}" } ?: "Ablaufdatum nicht gemeldet",
                style = MaterialTheme.typography.bodySmall)
        }
        usage.creditsWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text("Anzeige aus deinem Account. Öffentliche Reset-Meldungen bestätigen keine persönlichen Guthaben.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResetScreen(state: UiState, now: Long, model: UsageViewModel) {
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.demo) item { DemoBanner(model::exitDemo) }
        state.usage?.let { usage ->
            item { SectionTitle("Dein Account"); Spacer(Modifier.height(8.dp)); BankedCard(usage, now) }
            items(usage.resetCredits.filter { it.grantedAt != null || it.status != "available" }) { credit ->
                SoftCard {
                    Text(credit.title, style = MaterialTheme.typography.titleSmall)
                    Text(when (credit.status) {
                        "available" -> "Verfügbar"; "redeemed" -> "Eingelöst"; "expired" -> "Abgelaufen"; else -> "Status: ${credit.status}"
                    }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    credit.grantedAt?.let { Text("Vergeben: ${date(it)}", style = MaterialTheme.typography.bodySmall) }
                    if (credit.status == "redeemed") Text("Einlösezeitpunkt vom Anbieter nicht gemeldet.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SectionTitle("Öffentlicher Verlauf")
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResetSource.entries.forEach { source -> FilterChip(selected = state.source == source, onClick = { model.setSource(source) }, label = { Text(source.label) }) }
            }
        }
        item {
            SoftCard {
                val latest = state.feed?.events?.firstOrNull { it.kind == "Globaler Reset" }
                Text(if (state.source == ResetSource.CODEX_RESET) "Letzte globale Reset-Meldung" else "Letztes Ledger-Ereignis", style = MaterialTheme.typography.labelLarge)
                val event = if (state.source == ResetSource.CODEX_RESET) latest else state.feed?.events?.firstOrNull()
                Text(event?.let { date(it.at) } ?: "Noch keine Meldung geladen", style = MaterialTheme.typography.headlineSmall)
                Text("Community-Daten. Ob dein Account zurückgesetzt wurde, prüfst du über deinen Verbrauch.", style = MaterialTheme.typography.bodySmall)
                if (state.source == ResetSource.LUNARWERX) Text("Dieses Ledger enthält auch Banked-Vergaben; Ereignisse werden hier nicht als persönliche oder globale Resets bestätigt.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uri.openUri(state.source.site) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Daten: ${state.source.label}"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                }
                state.feed?.let { feed ->
                    Text("Quellenstand: ${feed.publishedAt?.let { date(it, true) } ?: "nicht gemeldet"}\nAbgerufen: ${date(feed.fetchedAt, true)}",
                        style = MaterialTheme.typography.labelSmall)
                    if (feed.publishedAt != null && now - feed.publishedAt > 600) Text("Quellenstand älter als 10 Minuten", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        state.feedError?.let { item { Notice("Öffentliche Quelle: $it", true) } }
        item {
            FilledTonalButton(onClick = model::refresh, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shapes = ButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Aktualisieren")
            }
            Text("${state.source.label} veröffentlicht neue Daten höchstens alle ${state.source.minimumSeconds / 60} Minuten. Persönlicher Verbrauch wird unabhängig davon neu abgefragt.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.feedLoading && state.feed == null) item { LoadingIndicator(Modifier.fillMaxWidth()) }
        if (state.feed != null && state.feed.events.isEmpty()) item { Notice("Keine passenden Reset-Meldungen in dieser Quelle.") }
        items(state.feed?.events.orEmpty(), key = { it.id }) { event ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (event.kind.startsWith("Banked")) Icons.Rounded.ConfirmationNumber else Icons.Rounded.RestartAlt,
                        null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(event.kind, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(date(event.at), style = MaterialTheme.typography.labelSmall)
                    }
                    event.url?.let { link -> IconButton(onClick = { uri.openUri(link) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Originalquelle öffnen") } }
                }
                Text(event.summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { SectionTitle("Auf deinem Gerät beobachtet") }
        if (state.observations.isEmpty()) item { Notice("Noch kein Verbrauchsrückgang zwischen zwei Messungen beobachtet. Zeitpunkt und Ursache eines Resets lassen sich daraus nicht sicher bestimmen.") }
        items(state.observations) { observation -> SoftCard {
            Text("${observation.windowTitle} · Verbrauch gesunken", style = MaterialTheme.typography.titleSmall)
            Text("${percent(observation.before)} % → ${percent(observation.after)} %", style = MaterialTheme.typography.bodyMedium)
            Text("Beobachtet: ${date(observation.detectedAt, true)}", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable
private fun SettingsScreen(state: UiState, model: UsageViewModel) {
    val uri = LocalUriHandler.current
    var confirmDisconnect by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp, 8.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Darstellung")
        SoftCard {
            Text("Farbschema", style = MaterialTheme.typography.titleMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Hell", ThemeMode.DARK to "Dunkel").forEach { (theme, name) ->
                    FilterChip(state.theme == theme, onClick = { model.setTheme(theme) }, label = { Text(name) })
                }
            }
            SwitchRow("Dynamische Farben", "Farben aus deinem Android-Hintergrund", state.dynamicColor, model::setDynamic)
            SwitchRow("Schwarzer Hintergrund", "Für das dunkle Farbschema", state.amoled, model::setAmoled)
        }
        SectionTitle("Account")
        SoftCard {
            Text(if (state.connected) "Codex verbunden" else "Codex verbinden", style = MaterialTheme.typography.titleMedium)
            Text("Anmeldung über einen Gerätecode im OpenAI-Browser. Tokens bleiben verschlüsselt auf diesem Gerät. Keine Analyse- oder Trackingdienste.", style = MaterialTheme.typography.bodyMedium)
            if (state.connected) OutlinedButton(onClick = { confirmDisconnect = true }) { Text("Account trennen") }
            else Button(onClick = model::connect, enabled = !state.disconnecting) { Text(if (state.disconnecting) "Wird getrennt" else "Mit Codex verbinden") }
            TextButton(onClick = { uri.openUri("https://chatgpt.com/codex/cloud/settings/analytics#usage") }) { Text("OpenAI-Verbrauch im Browser") }
        }
        SectionTitle("Über AI Usage")
        SoftCard {
            Text("0.1.0 · Native Android-App", style = MaterialTheme.typography.titleMedium)
            Text("Auf Basis von AniHyou von axiel7. Kotlin, Jetpack Compose und Material 3 Expressive. GPL-3.0.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { uri.openUri("https://github.com/axiel7/AniHyou-android") }) { Text("AniHyou · Originalprojekt") }
            TextButton(onClick = { uri.openUri("https://github.com/madebycli/AiUsage-Android") }) { Text("AI Usage · Quellcode") }
            Text("Inoffizielle App. Die Codex-Account-Endpunkte sind keine zugesicherte öffentliche API und können sich ändern.", style = MaterialTheme.typography.bodySmall)
        }
        if (!state.connected) TextButton(onClick = { if (state.demo) model.exitDemo() else model.demo() }) {
            Text(if (state.demo) "Demo beenden" else "Design mit Beispieldaten ansehen")
        }
    }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false }, title = { Text("Account trennen?") },
        text = { Text("Die lokale Anmeldung und dein lokal beobachteter Verlauf werden gelöscht.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; model.disconnect() }) { Text("Trennen") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Abbrechen") } })
}

@Composable
private fun IntroCard(connect: () -> Unit, demo: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = RoundedCornerShape(32.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Rounded.DonutLarge, null, Modifier.size(48.dp))
            Text("Dein Budget.\nAuf einen Blick.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium)
            Text("5 Stunden, eine Woche und deine gespeicherten Resets. Direkt aus deinem Codex-Account.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = connect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) { Text("Mit Codex verbinden") }
            TextButton(onClick = demo, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Erst das Design ansehen") }
        }
    }
}

@Composable
private fun SoftCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable
private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
@Composable
private fun Notice(text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(18.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
    }
}
@Composable
private fun DemoBanner(exit: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Demo · keine Live-Daten", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onTertiaryContainer)
        TextButton(onClick = exit) { Text("Beenden") }
    }
}
@Composable
private fun StatusPill(text: String, icon: ImageVector) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(10.dp, 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, Modifier.size(14.dp)); Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, onChange)
    }
}
@Composable
private fun rememberNow(): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = Instant.now().epochSecond; delay(1000) } } }
    return now
}
private fun percent(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.GERMAN, "%.1f", value)
private fun date(at: Long, seconds: Boolean = false): String = DateTimeFormatter.ofPattern(if (seconds) "dd.MM. · HH:mm:ss" else "dd.MM. · HH:mm", Locale.GERMAN)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(at))
private fun countdown(seconds: Long): String = when {
    seconds >= 86400 -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
    else -> "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
}
private fun age(seconds: Long): String = when { seconds < 60 -> "vor ${seconds.coerceAtLeast(0)} s"; seconds < 3600 -> "vor ${seconds / 60} min"; else -> "vor ${seconds / 3600} h" }
