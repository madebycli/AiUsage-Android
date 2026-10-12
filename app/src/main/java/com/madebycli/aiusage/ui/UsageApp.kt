@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.axiel7.anihyou.core.ui.composables.common.SmallCircularProgressIndicator
import com.madebycli.aiusage.*
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun UsageApp(state: UiState, model: UsageViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val now = rememberNow()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val anyConnected = state.connected || state.claudeConnected
    val accounts = !anyConnected || page == 3
    BackHandler(enabled = anyConnected && page != 0) { page = 0 }
    LaunchedEffect(page, state.connected, state.claudeConnected) { if (page == 1 && anyConnected) model.loadCommunity() }
    Scaffold(
        topBar = {
            if (!accounts) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(24.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI USAGE", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
                    Text(when (page) { 1 -> "Reset Radar"; 2 -> "Ganz dein Stil"; else -> "Dein Überblick" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                FilledTonalIconButton(onClick = model::refresh) {
                    if (state.refreshing || state.claudeRefreshing) SmallCircularProgressIndicator()
                    else Icon(Icons.Rounded.Refresh, "Jetzt aktualisieren")
                }
            }
        },
        bottomBar = {
            if (!accounts) Surface(Modifier.navigationBarsPadding().padding(20.dp, 8.dp), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val destinations = buildList {
                        add(Triple(0, "Verbrauch", Icons.Rounded.DonutLarge))
                        add(Triple(1, "Resets", Icons.Rounded.AutoAwesome))
                        add(Triple(2, "Einstellungen", Icons.Rounded.Tune))
                    }
                    destinations.forEach { (index, label, icon) ->
                        Surface(onClick = { page = index }, shape = RoundedCornerShape(26.dp), color = if (page == index) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                            Column(Modifier.padding(16.dp, 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(icon, label, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (state.initializing || state.claudeInitializing) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else if (accounts) LoginScreen(state, model, if (anyConnected) ({ page = 0 }) else null)
            else when (page) {
                1 -> ResetScreen(state, now, model)
                2 -> SettingsScreen(state, model) { page = 3 }
                else -> UsageScreen(state, now)
            }
        }
    }
    if (state.loginLoading) {
        AlertDialog(onDismissRequest = {},
            icon = { Icon(Icons.Rounded.Key, null) }, title = { Text("Codex verbinden") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(state.loginPhase.label, style = MaterialTheme.typography.titleSmall)
                    state.loginHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (state.loginPhase == LoginPhase.WAITING) Text("Im Browser anmelden, Code eingeben und die Freigabe bestätigen. Danach zu AI Usage zurückkehren.")
                    if (state.deviceCode == null) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
                    state.deviceCode?.takeIf { state.loginPhase == LoginPhase.WAITING }?.let {
                        Text(it.code, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        TextButton(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("OpenAI Anmeldecode", it.code))
                        }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Code kopieren") }
                        Text("Code gilt 15 Minuten. Die App wartet auf deine Freigabe.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                state.deviceCode?.takeIf { state.loginPhase == LoginPhase.WAITING }?.let { TextButton(onClick = { uri.openUri("https://auth.openai.com/codex/device") }) { Text("Im Browser anmelden") } }
            }, dismissButton = { TextButton(onClick = model::cancelLogin) { Text(if (state.connected) "Schließen" else "Abbrechen") } }
        )
    }
    state.loginError?.takeIf { !state.loginLoading }?.let { error ->
        AlertDialog(onDismissRequest = model::clearLoginError,
            title = { Text(if (state.connected) "Verbrauch nicht geladen" else "Anmeldung fehlgeschlagen") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = {
                model.clearLoginError()
                if (state.connected) model.refresh() else model.connect()
            }) { Text(if (state.connected) "Erneut aktualisieren" else "Neuer Anmeldecode") } },
            dismissButton = { TextButton(onClick = model::clearLoginError) { Text("Schließen") } })
    }
}

@Composable
private fun UsageScreen(state: UiState, now: Long) {
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    val names = buildList { if (state.connected) add("Codex"); if (state.claudeConnected) add("Claude Code") }
    val active = provider?.takeIf { it in names }
    if (active == null) {
        LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(24.dp, 4.dp, 24.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (state.connected) item { ProviderBanner("Codex", state.usage, state.usageError, state.refreshing, now) { provider = "Codex" } }
            if (state.claudeConnected) item { ProviderBanner("Claude Code", state.claudeUsage, state.claudeError, state.claudeRefreshing, now) { provider = "Claude Code" } }
        }
        return
    }
    BackHandler { provider = null }
    val usage = if (active == "Codex") state.usage else state.claudeUsage
    val error = if (active == "Codex") state.usageError else state.claudeError
    val refreshing = if (active == "Codex") state.refreshing else state.claudeRefreshing
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(24.dp, 4.dp, 24.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick = { provider = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp)); Text("Zur Übersicht") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { name -> FilterChip(active == name, { provider = name }, label = { Text(name) },
                    leadingIcon = { Icon(if (name == "Codex") Icons.Rounded.Terminal else Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) }) }
            }
            Text(usage?.plan?.replaceFirstChar { it.titlecase() } ?: "Verbunden", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { item { Notice(it, true) } }
        if (active == "Codex") state.storageWarning?.let { item { Notice(it) } }
        if (usage == null) item { SoftCard {
            if (refreshing) LoadingIndicator()
            Text("Verbrauch wird geladen", style = MaterialTheme.typography.titleMedium)
            Text("Aktualisiere oben, um es erneut zu versuchen.", style = MaterialTheme.typography.bodySmall)
        } }
        usage?.let { snapshot ->
            snapshot.groups.forEachIndexed { index, group ->
                if (group.name != active) item(key = "title:$index") { SectionTitle(group.name) }
                items(group.windows, key = { "$index:${it.seconds}" }) { window -> WindowCard(window, now, window.seconds == 18000L) }
            }
            if (snapshot.groups.all { it.windows.isEmpty() }) item { Notice("Der Anbieter meldet aktuell keine Zeitfenster.") }
            if (snapshot.availableResets != null || snapshot.resetCredits.isNotEmpty()) item { BankedCard(snapshot, now, active == "Codex" && state.creditsLoading) }
            snapshot.extraUsage?.let { extra -> item { ExtraUsageCard(extra) } }
            item { Text("${if (refreshing) "Aktualisiert… · " else ""}${age(now - snapshot.fetchedAt)} · ${date(snapshot.fetchedAt, true)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Disclosure("Wie wird gemessen?") {
                Text("Jeder Refresh fragt den Anbieter neu ab. Serverwerte können verzögert sein. Zeit und Verbrauch laufen unabhängig. Alte Werte bleiben bei Fehlern sichtbar.", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable
private fun WindowCard(window: UsageWindow, now: Long, primary: Boolean) {
    val colors = MaterialTheme.colorScheme
    val exhausted = window.usedPercent >= 100
    val background = when { exhausted -> colors.errorContainer; primary -> colors.primaryContainer; else -> colors.surfaceContainer }
    val foreground = when { exhausted -> colors.onErrorContainer; primary -> colors.onPrimaryContainer; else -> colors.onSurface }
    val animated by animateFloatAsState((window.usedPercent / 100).toFloat().coerceIn(0f, 1f), label = "usage")
    Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp), color = background, contentColor = foreground, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(window.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (primary) Icons.Rounded.Bolt else Icons.Rounded.NightsStay, null, Modifier.size(22.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(106.dp).semantics { contentDescription = "${window.title}: ${percent(window.usedPercent)} Prozent verbraucht" }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(foreground.copy(alpha = 0.12f), 140f, 260f, false, topLeft = Offset(7.dp.toPx(), 7.dp.toPx()), size = Size(size.width - 14.dp.toPx(), size.height - 14.dp.toPx()), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                        if (animated > 0) drawArc(foreground, 140f, 260f * animated, false, topLeft = Offset(7.dp.toPx(), 7.dp.toPx()), size = Size(size.width - 14.dp.toPx(), size.height - 14.dp.toPx()), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${percent(window.usedPercent)}%", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text("genutzt", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (exhausted) "Pause fürs Budget" else "${percent((100 - window.usedPercent).coerceAtLeast(0.0))}% frei", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(window.resetAt?.let { if (it <= now) "Reset fällig · aktualisieren" else "Reset in ${countdown(it - now)}" } ?: "Reset nicht gemeldet", style = MaterialTheme.typography.bodySmall)
                    window.resetAt?.let { Text(date(it), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .7f)) }
                }
            }
            window.elapsed(now)?.let { elapsed ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { elapsed }, modifier = Modifier.weight(1f).height(4.dp), color = foreground.copy(alpha = .5f), trackColor = foreground.copy(alpha = .1f))
                    Text("${(elapsed * 100).toInt()}% Zeit", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun BankedCard(usage: UsageSnapshot, now: Long, loading: Boolean) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.ConfirmationNumber, null, tint = MaterialTheme.colorScheme.primary)
            Text("Reset-Vorrat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusPill(usage.availableResets?.let { "$it verfügbar" } ?: "Nicht gemeldet", Icons.Rounded.AutoAwesome)
        }
        if (loading) Text("Details werden geladen…", style = MaterialTheme.typography.labelSmall)
        if (usage.resetCredits.isNotEmpty() || usage.creditsWarning != null) Disclosure("Details ansehen") {
            usage.resetCredits.forEach { credit ->
                Text(credit.title, style = MaterialTheme.typography.labelLarge)
                Text(when (credit.status) { "available" -> "Verfügbar"; "redeemed" -> "Eingelöst"; "paused" -> "Pausiert"; "expired" -> "Abgelaufen"; else -> "Derzeit nicht verfügbar" }, style = MaterialTheme.typography.bodySmall)
                credit.expiresAt?.let { Text("Bis ${date(it)}${if (it <= now) " · Ablauf erreicht" else ""}", style = MaterialTheme.typography.bodySmall) }
            }
            usage.creditsWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Persönliche Account-Daten. Keine automatische Einlösung.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun ExtraUsageCard(extra: ExtraUsage) {
    fun money(value: Double?): String = value?.let {
        val decimals = extra.decimals ?: if (extra.currency == null) 2 else return "${percent(it)} Minor Units (${extra.currency})"
        "${String.format(Locale.GERMAN, "%.${decimals}f", it / Math.pow(10.0, decimals.toDouble()))} ${extra.currency ?: "USD"}"
    } ?: "Nicht gemeldet"
    SoftCard {
        Text("Zusatzverbrauch", style = MaterialTheme.typography.titleMedium)
        Text(if (extra.enabled) "Aktiviert" else "Deaktiviert", style = MaterialTheme.typography.labelMedium)
        if (extra.enabled) {
            Text(money(extra.used), style = MaterialTheme.typography.headlineSmall)
            Text("Monatslimit: ${money(extra.limit)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun CodexResetScreen(state: UiState, now: Long, model: UsageViewModel) {
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        item { SectionTitle("Forecast"); Text("Chance auf einen außerplanmäßigen Codex-Reset", style = MaterialTheme.typography.bodySmall) }
        ResetSource.entries.forEach { source -> item(key = "forecast:${source.name}") { ForecastCard(source, state.forecasts[source], state.forecastLoading, now) } }
        item { CommunityCard(CommunitySource.VERCEL_CODEX, state.community[CommunitySource.VERCEL_CODEX], state.forecastLoading, now) }
        state.usage?.let { usage ->
            item { SectionTitle("Dein Account"); Spacer(Modifier.height(8.dp)); BankedCard(usage, now, state.creditsLoading) }
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
        if (state.observations.isNotEmpty()) item { SectionTitle("Auf deinem Gerät beobachtet") }
        items(state.observations) { observation -> SoftCard {
            Text("${observation.windowTitle} · Verbrauch gesunken", style = MaterialTheme.typography.titleSmall)
            Text("${percent(observation.before)} % → ${percent(observation.after)} %", style = MaterialTheme.typography.bodyMedium)
            Text("Beobachtet: ${date(observation.detectedAt, true)}", style = MaterialTheme.typography.bodySmall)
        } }
    }
}

@Composable
private fun ProviderBanner(name: String, usage: UsageSnapshot?, error: String?, loading: Boolean, now: Long, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = if (name == "Codex") colors.primaryContainer else colors.tertiaryContainer
    val foreground = if (name == "Codex") colors.onPrimaryContainer else colors.onTertiaryContainer
    Surface(onClick = open, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 12.dp, bottomEnd = 32.dp, bottomStart = 32.dp), color = background, contentColor = foreground) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (name == "Codex") Icons.Rounded.Terminal else Icons.Rounded.AutoAwesome, null, Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(usage?.plan?.replaceFirstChar { it.titlecase() } ?: "Verbunden", style = MaterialTheme.typography.labelMedium)
                }
                Icon(Icons.Rounded.NorthEast, "Verbrauchsdetails öffnen", Modifier.size(20.dp))
            }
            val windows = usage?.groups?.firstOrNull { it.name == name }?.windows.orEmpty()
            windows.forEach { window ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(window.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("${percent(window.usedPercent)}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(" genutzt", style = MaterialTheme.typography.labelSmall)
                }
                LinearProgressIndicator(progress = { (window.usedPercent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = foreground, trackColor = foreground.copy(alpha = .12f))
                window.resetAt?.let { Text(if (it > now) "Reset in ${countdown(it - now)}" else "Reset fällig · aktualisieren", style = MaterialTheme.typography.labelSmall) }
            }
            if (usage == null || windows.isEmpty()) Text(if (loading) "Verbrauch wird geladen…" else "Keine Zeitfenster gemeldet", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (error != null) "Letzter Abruf fehlgeschlagen" else if (loading) "Wird aktualisiert…" else usage?.let { age(now - it.fetchedAt) } ?: "Noch keine Messung", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Text("Mehr Details", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ResetScreen(state: UiState, now: Long, model: UsageViewModel) {
    var provider by rememberSaveable { mutableStateOf("Codex") }
    val names = buildList { if (state.connected) add("Codex"); if (state.claudeConnected) add("Claude Code") }
    val active = provider.takeIf { it in names } ?: names.firstOrNull() ?: return
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            names.forEach { name -> FilterChip(active == name, { provider = name }, label = { Text(name) }) }
        }
        if (active == "Codex") CodexResetScreen(state, now, model)
        else LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(24.dp, 8.dp, 24.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { SectionTitle("Claude Forecast"); Text("Öffentliche Schätzungen, getrennt von deinem Account", style = MaterialTheme.typography.bodySmall) }
            CommunitySource.entries.filter { it.provider == "Claude Code" }.forEach { source -> item(key = source.name) { CommunityCard(source, state.community[source], state.forecastLoading, now) } }
            state.claudeUsage?.takeIf { it.availableResets != null || it.resetCredits.isNotEmpty() }?.let { usage -> item { SectionTitle("Dein Reset-Vorrat"); BankedCard(usage, now, false) } }
            item { FilledTonalButton(onClick = model::refresh, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Refresh, null); Text("Aktualisieren") } }
        }
    }
}
@Composable
private fun CommunityCard(source: CommunitySource, result: CommunityResult?, loading: Boolean, now: Long) {
    val uri = LocalUriHandler.current
    SoftCard {
        Text(source.label, style = MaterialTheme.typography.titleMedium)
        val report = result?.report
        if (report == null) Text(if (loading) "Quelle wird geladen…" else result?.error ?: "Noch keine Daten", style = MaterialTheme.typography.bodySmall)
        else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${percent(report.chance.percent)}%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(if (report.chance.hours == 168) "in 7 Tagen" else "in ${report.chance.hours} Stunden", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 5.dp))
            }
            Text("Community-Schätzung · kein Reset-Versprechen", style = MaterialTheme.typography.bodySmall)
            if (result.error != null) Text("Letzter Abruf fehlgeschlagen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Text(report.publishedAt?.let { "Quellenstand ${date(it)}${if (now - it > source.minimumSeconds * 2) " · veraltet" else ""}" } ?: "Quellenstand nicht angegeben", style = MaterialTheme.typography.labelSmall)
            Disclosure("Verlauf & Details") {
                report.expectedAt?.let { Text("Geschätzter Termin: ${date(it)}${if (it < now) " · bereits vergangen" else ""}", style = MaterialTheme.typography.bodySmall) }
                Text("Abgerufen ${date(report.fetchedAt, true)}", style = MaterialTheme.typography.labelSmall)
                report.events.take(14).forEach { event ->
                    Text("${date(event.at)} · ${event.kind}", style = MaterialTheme.typography.labelLarge)
                    Text(event.summary, style = MaterialTheme.typography.bodySmall)
                    event.url?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("Originalmeldung") } }
                }
                if (report.events.isEmpty()) Text("Kein Verlauf aus dieser Quelle gelesen.", style = MaterialTheme.typography.bodySmall)
                Text("Aus der öffentlichen Webseite gelesen. Änderungen der Seite können den Abruf unterbrechen.", style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = { uri.openUri(source.site) }, contentPadding = PaddingValues(0.dp)) { Text("Daten: ${source.label}"); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 6.dp).size(14.dp)) }
    }
}

@Composable
private fun ForecastCard(source: ResetSource, result: ForecastResult?, loading: Boolean, now: Long) {
    val uri = LocalUriHandler.current
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(source.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.tertiary)
        }
        val forecast = result?.forecast
        if (forecast == null) Text(if (loading) "Forecast wird geladen…" else result?.error ?: "Noch kein Forecast geladen", style = MaterialTheme.typography.bodySmall)
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                forecast.chances.forEach { chance -> Column(Modifier.weight(1f)) {
                    Text("${percent(chance.percent)}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("in ${chance.hours} Stunden", style = MaterialTheme.typography.labelMedium)
                    if (chance.low != null && chance.high != null) Text("${percent(chance.low)}–${percent(chance.high)}% Bereich", style = MaterialTheme.typography.labelSmall)
                } }
            }
            Text("Schätzung · kein angekündigter Reset", style = MaterialTheme.typography.bodySmall)
            result.error?.let { Text("Letzter Abruf fehlgeschlagen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            forecast.publishedAt?.let { Text("Stand ${date(it)}${if (now - it > source.minimumSeconds * 2) " · veraltet" else ""}", style = MaterialTheme.typography.labelSmall) }
            Disclosure("Einordnung") {
                forecast.confidence?.let { Text("Vertrauen: ${when (it) { "low" -> "niedrig"; "medium" -> "mittel"; "high" -> "hoch"; else -> it }}", style = MaterialTheme.typography.bodySmall) }
                forecast.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                forecast.accuracy?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                forecast.lastReset?.let { Text("Letzter Reset: ${date(it)}", style = MaterialTheme.typography.bodySmall) }
                Text("Abgerufen ${date(forecast.fetchedAt, true)}", style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = { uri.openUri(source.site) }, contentPadding = PaddingValues(0.dp)) {
            Text("Daten: ${source.label}"); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.padding(start = 6.dp).size(14.dp))
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, model: UsageViewModel, accounts: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp, 8.dp, 24.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SoftCard {
            Text("Deine Accounts", style = MaterialTheme.typography.titleMedium)
            if (state.connected) Text("Codex verbunden", style = MaterialTheme.typography.bodyMedium)
            if (state.claudeConnected) Text("Claude Code verbunden", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = accounts) { Text("Anmeldungen verwalten") }
        }
        SoftCard {
            Text("Farben & Gefühl", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Hell", ThemeMode.DARK to "Dunkel").forEach { (theme, label) -> FilterChip(state.theme == theme, { model.setTheme(theme) }, label = { Text(label) }) }
            }
            SwitchRow("Android-Farben", "Dein Hintergrund bestimmt die Palette", state.dynamicColor, model::setDynamic)
            SwitchRow("Tiefschwarz", "Für den dunklen Modus", state.amoled, model::setAmoled)
        }
        Disclosure("Über AI Usage · ${BuildConfig.VERSION_NAME}") {
            Text("Kotlin · Compose · Material 3 Expressive. Basis: AniHyou von axiel7, GPL-3.0. Eigenes Design inspiriert von ArchiveTune.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/madebycli/AiUsage-Android") }) { Text("Quellcode") }
            Text("Inoffizielle Account-Anzeige. Anbieter-Endpunkte können sich ändern.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LoginScreen(state: UiState, model: UsageViewModel, close: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var claudeForm by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() <= 65_536) {
                        val count = stream.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    require(bytes.size <= 65_536)
                    bytes.toString(Charsets.UTF_8)
                } ?: error("Missing file") }
                model.importClaude(raw)
                fileError = null
            } catch (_: Exception) { fileError = "Datei konnte nicht gelesen werden. Kleine Claude-Code-Zugangsdaten-Datei auswählen." }
        }
    }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (close != null) TextButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Text("Zurück") }
        Spacer(Modifier.height(18.dp))
        FlowerMark()
        Text("Wen verbinden\nwir heute?", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Deine Tools. Dein Überblick.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                Text("Codex", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.connected) Icon(Icons.Rounded.CheckCircle, "Verbunden", tint = MaterialTheme.colorScheme.primary)
            }
            if (state.connected) TextButton(onClick = { confirm = "Codex" }) { Text("Verbindung trennen") }
            else Button(onClick = model::connect, enabled = !state.loginLoading && !state.disconnecting, modifier = Modifier.fillMaxWidth()) { Text("Mit Codex verbinden") }
        }
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.tertiary)
                Text("Claude Code", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.claudeConnected) Icon(Icons.Rounded.CheckCircle, "Verbunden", tint = MaterialTheme.colorScheme.tertiary)
            }
            if (state.claudeConnected) TextButton(onClick = { confirm = "Claude Code" }) { Text("Verbindung trennen") }
            FilledTonalButton(onClick = { claudeForm = !claudeForm }, modifier = Modifier.fillMaxWidth()) { Text(if (state.claudeConnected) "Anmeldung erneuern" else "Mit Claude Code verbinden") }
            if (claudeForm) {
                Text("Importiere deine Claude-Code-Anmeldung vom Computer oder füge einen OAuth-Token ein.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.claudeRefreshing) { Icon(Icons.Rounded.UploadFile, null); Text("Datei auswählen") }
                OutlinedTextField(secret, { secret = it.take(65_536) }, label = { Text("OAuth-Token oder Zugangsdaten-JSON") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Button(onClick = { model.importClaude(secret); secret = "" }, enabled = secret.isNotBlank() && !state.claudeRefreshing, modifier = Modifier.fillMaxWidth()) { Text(if (state.claudeRefreshing) "Wird verbunden…" else "Anmeldung importieren") }
                Disclosure("Wo finde ich die Anmeldung?") {
                    Text("Claude Code: ~/.claude/.credentials.json (Windows: %USERPROFILE%\\.claude\\.credentials.json). Ein mit claude setup-token erstellter OAuth-Token benötigt user:profile-Zugriff für die Verbrauchsanzeige. Ein API-Key zeigt keine Abo-Limits.", style = MaterialTheme.typography.bodySmall)
                    Text("Die App liest die Anmeldung nur und erneuert keinen kopierten Refresh-Token. Bei Ablauf erneut importieren. Tokens bleiben verschlüsselt auf diesem Gerät.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.claudeRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.claudeError?.let { Notice(it, true) }
            fileError?.let { Notice(it, true) }
        }
        Text("Nur verbundene Tools erscheinen in deiner Übersicht.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.usageError?.takeIf { !state.connected }?.let { Notice(it, true); TextButton(onClick = model::disconnect) { Text("Lokale Codex-Daten zurücksetzen") } }
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, title = { Text("$confirm trennen?") }, text = { Text("Die Anmeldung und Messungen dieses Anbieters werden auf dem Gerät gelöscht.") }, confirmButton = { TextButton(onClick = { if (confirm == "Codex") model.disconnect() else model.disconnectClaude(); confirm = null }) { Text("Trennen") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } })
}

@Composable
private fun FlowerMark() {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.size(76.dp)) {
        val radius = size.minDimension / 5
        for (i in 0..7) {
            val angle = i * Math.PI / 4
            drawCircle(colors.primaryContainer, radius, Offset(center.x + kotlin.math.cos(angle).toFloat() * radius, center.y + kotlin.math.sin(angle).toFloat() * radius))
        }
        drawCircle(colors.primary, radius * .65f)
        drawCircle(colors.onPrimary, radius * .15f, Offset(center.x - radius * .23f, center.y - radius * .12f))
        drawCircle(colors.onPrimary, radius * .15f, Offset(center.x + radius * .23f, center.y - radius * .12f))
    }
}
@Composable
private fun Disclosure(label: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(label); Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
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
