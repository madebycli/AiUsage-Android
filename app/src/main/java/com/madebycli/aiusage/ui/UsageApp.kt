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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithMediumTopAppBar
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
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

import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.vectorResource
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.madebycli.aiusage.R
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

private val cardShape = RoundedCornerShape(28.dp)

@Composable
fun UsageApp(state: UiState, model: UsageViewModel, notificationProvider: String? = null) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var resetProvider by rememberSaveable { mutableStateOf("Codex") }
    var resetPage by rememberSaveable { mutableStateOf<String?>(null) }
    val now = rememberNow()
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val anyConnected = state.connected || state.claudeConnected
    val accounts = !anyConnected || page == 3
    val detail = selectedProvider?.takeIf { if (it == "Codex") state.connected else state.claudeConnected }
    val resetDetail = resetPage?.takeIf { if (it.startsWith("Codex:")) state.connected else state.claudeConnected }
    val mainScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val detailScroll = TopAppBarDefaults.pinnedScrollBehavior()
    BackHandler(enabled = state.claudeLoginOpen || (anyConnected && (page != 0 || detail != null || resetDetail != null))) {
        if (state.claudeLoginOpen) model.cancelClaudeLogin()
        else if (resetDetail != null) resetPage = null
        else if (detail != null) selectedProvider = null else if (page == 3 || page == 4) page = 2 else page = 0
    }
    LaunchedEffect(state.connected, state.claudeConnected, state.initializing, state.claudeInitializing) { model.syncBackground() }
    LaunchedEffect(page, state.connected, state.claudeConnected) { if (page == 1 && anyConnected) model.loadCommunity() }
    LaunchedEffect(state.claudeLoginResult) {
        if (state.claudeLoginResult > 0 && state.claudeConnected) { page = 0; selectedProvider = "Claude Code" }
    }
    LaunchedEffect(notificationProvider, state.connected, state.claudeConnected) {
        if (notificationProvider == "Codex" && state.connected || notificationProvider == "Claude Code" && state.claudeConnected) {
            page = 0; selectedProvider = notificationProvider; resetPage = null
        }
    }
    val refreshAction: @Composable RowScope.() -> Unit = {
        FilledTonalIconButton(onClick = model::refresh, modifier = Modifier.size(48.dp)) {
            if (state.refreshing || state.claudeRefreshing) SmallCircularProgressIndicator()
            else Icon(Icons.Rounded.Refresh, "Jetzt aktualisieren")
        }
        Spacer(Modifier.width(16.dp))
    }
    when {
        state.initializing || state.claudeInitializing -> Scaffold { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
        }
        state.claudeLoginOpen -> DefaultScaffoldWithSmallTopAppBar(
            title = "Claude verbinden", scrollBehavior = detailScroll,
            navigationIcon = { IconButton(onClick = model::cancelClaudeLogin) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } }
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { ClaudeLoginScreen(state, model) } }
        accounts -> {
            if (anyConnected) DefaultScaffoldWithSmallTopAppBar(
                title = "Anmeldungen", scrollBehavior = detailScroll,
                navigationIcon = { IconButton(onClick = { page = 2 }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } }
            ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { LoginScreen(state, model, null) } }
            else Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { LoginScreen(state, model, null) } }
        }
        page == 4 -> DefaultScaffoldWithSmallTopAppBar(
            title = "Benachrichtigungen", scrollBehavior = detailScroll,
            navigationIcon = { IconButton(onClick = { page = 2 }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } }
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { NotificationSettings(state, model) } }
        resetDetail != null -> DefaultScaffoldWithSmallTopAppBar(
            title = "${resetDetail.substringAfter(':')} · ${resetDetail.substringBefore(':')}", scrollBehavior = detailScroll,
            navigationIcon = { IconButton(onClick = { resetPage = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
            actions = refreshAction
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            ResetDetailsScreen(state, now, model, resetDetail.substringBefore(':'), resetDetail.substringAfter(':'))
        } }
        detail != null -> DefaultScaffoldWithSmallTopAppBar(
            title = detail, scrollBehavior = detailScroll,
            modifier = Modifier.nestedScroll(detailScroll.nestedScrollConnection),
            navigationIcon = { IconButton(onClick = { selectedProvider = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
            actions = refreshAction
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { ProviderUsageScreen(state, now, detail) } }
        else -> DefaultScaffoldWithMediumTopAppBar(
            title = when (page) { 1 -> "Resets"; 2 -> "Einstellungen"; else -> "AI Usage" },
            modifier = Modifier.nestedScroll(mainScroll.nestedScrollConnection),
            scrollBehavior = mainScroll, actions = refreshAction,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    listOf("Verbrauch" to Icons.Rounded.DonutLarge, "Resets" to Icons.Rounded.History, "Einstellungen" to Icons.Rounded.Tune).forEachIndexed { index, destination ->
                        NavigationBarItem(selected = page == index, onClick = { page = index },
                            icon = { Icon(destination.second, null) }, label = { Text(destination.first) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                when (page) {
                    1 -> ResetScreen(state, now, resetProvider, { resetProvider = it }) { provider, destination -> resetPage = "$provider:$destination" }
                    2 -> SettingsScreen(state, model, { page = 3 }, { page = 4 })
                    else -> UsageScreen(state, now) { selectedProvider = it }
                }
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
private fun UsageScreen(state: UiState, now: Long, open: (String) -> Unit) {
    var calendarProvider by rememberSaveable { mutableStateOf("Codex") }
    val names = buildList { if (state.connected) add("Codex"); if (state.claudeConnected) add("Claude Code") }
    val active = calendarProvider.takeIf { it in names } ?: names.firstOrNull()
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.connected) item { ProviderBanner("Codex", state.usage, state.usageError, state.refreshing, now) { open("Codex") } }
        if (state.claudeConnected) item { ProviderBanner("Claude Code", state.claudeUsage, state.claudeError, state.claudeRefreshing, now) { open("Claude Code") } }
        if (active != null) {
            item { SectionTitle("Aktivitätsverlauf") }
            if (names.size > 1) item { ProviderTabs(names, active, { calendarProvider = it }, inset = false) }
            item { UsageCalendar(active, if (active == "Codex") state.days else state.claudeDays, now) }
        }
    }
}

@Composable
private fun ProviderUsageScreen(state: UiState, now: Long, active: String) {
    val usage = if (active == "Codex") state.usage else state.claudeUsage
    val error = if (active == "Codex") state.usageError else state.claudeError
    val refreshing = if (active == "Codex") state.refreshing else state.claudeRefreshing
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(24.dp, 4.dp, 24.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(usage?.plan?.replaceFirstChar { it.titlecase() } ?: "Verbunden", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { item { Notice(it, true) } }
        if (active == "Codex") state.storageWarning?.let { item { Notice(it) } }
        if (usage == null) item { SoftCard {
            if (refreshing) LoadingIndicator()
            Text(if (refreshing) "Verbrauch wird geladen" else "Verbrauch noch nicht geladen", style = MaterialTheme.typography.titleMedium)
            Text("Aktualisiere oben, um es erneut zu versuchen.", style = MaterialTheme.typography.bodySmall)
        } }
        usage?.let { snapshot ->
            if (snapshot.warnings.isNotEmpty()) item { Notice(snapshot.warnings.joinToString("\n")) }
            snapshot.groups.forEachIndexed { index, group ->
                if (group.name != active) item(key = "title:$index") { SectionTitle(group.name) }
                items(group.windows, key = { "$index:${it.seconds}" }) { window -> WindowCard(window, now, window.seconds == 18000L) }
            }
            if (snapshot.groups.all { it.windows.isEmpty() }) item { Notice("Der Anbieter meldet aktuell keine Zeitfenster.") }
            if (snapshot.availableResets != null || snapshot.resetCredits.isNotEmpty() || snapshot.creditsWarning != null) item { BankedCard(snapshot, now, active == "Codex" && state.creditsLoading) }
            snapshot.extraUsage?.let { extra -> item { ExtraUsageCard(extra) } }
            item { Text("${if (refreshing) "Aktualisiert… · " else ""}${age(now - snapshot.fetchedAt)} · ${date(snapshot.fetchedAt, true)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        }
    }
}

@Composable
private fun WindowCard(window: UsageWindow, now: Long, primary: Boolean) {
    val colors = MaterialTheme.colorScheme
    val accent = if (window.usedPercent >= 100) colors.error else if (primary) colors.primary else colors.tertiary
    val animated by animateFloatAsState((window.usedPercent / 100).toFloat().coerceIn(0f, 1f), label = "usage")
    SoftCard {
        Text(window.title, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(112.dp).semantics { contentDescription = "${window.title}: ${percent(window.usedPercent)} Prozent verbraucht" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val inset = 8.dp.toPx()
                    val bounds = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(accent.copy(alpha = .12f), 140f, 260f, false, Offset(inset, inset), bounds, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                    if (animated > 0) drawArc(accent, 140f, 260f * animated, false, Offset(inset, inset), bounds, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${percent(window.usedPercent)}%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("genutzt", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Nächster Reset", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(window.resetAt?.let { if (it <= now) "Fällig" else countdown(it - now) } ?: "Nicht gemeldet",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                window.resetAt?.let { Text(date(it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
            }
        }
        window.elapsed(now)?.let { elapsed ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UsageTrack(elapsed.toFloat(), colors.onSurfaceVariant.copy(alpha = .6f), Modifier.weight(1f).height(3.dp))
                Text("${percent(elapsed.toDouble() * 100)}% Zeit", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
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
                Text(when (credit.status) { "available" -> "Verfügbar"; "redeemed" -> "Eingelöst"; "paused" -> "Pausiert"; "expired" -> "Abgelaufen"; "unknown" -> "Verfügbarkeit nicht bestätigt"; else -> "Derzeit nicht verfügbar" }, style = MaterialTheme.typography.bodySmall)
                credit.expiresAt?.let { Text("Bis ${date(it)}${if (it <= now) " · Ablauf erreicht" else ""}", style = MaterialTheme.typography.bodySmall) }
            }
            usage.creditsWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable
private fun ExtraUsageCard(extra: ExtraUsage) {
    fun money(value: Double?) = extraAmountText(value, extra.currency, extra.decimals)
    SoftCard {
        Text("Zusatzverbrauch", style = MaterialTheme.typography.titleMedium)
        Text(when (extra.enabled) { true -> "Aktiviert"; false -> "Deaktiviert"; null -> "Aktivierung nicht gemeldet" }, style = MaterialTheme.typography.labelMedium)
        if (extra.enabled == true || extra.used != null || extra.limit != null) {
            Text(money(extra.used), style = MaterialTheme.typography.headlineSmall)
            Text("Monatslimit: ${money(extra.limit)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun BrandIcon(provider: String, modifier: Modifier = Modifier.size(28.dp)) {
    Icon(painterResource(if (provider == "Codex") R.drawable.ic_brand_codex else R.drawable.ic_brand_claude),
        contentDescription = null, modifier = modifier,
        tint = if (provider == "Codex") MaterialTheme.colorScheme.onSurface else Color(0xFFD58C70))
}

@Composable
internal fun UsageTrack(progress: Float, accent: Color, modifier: Modifier = Modifier.fillMaxWidth().height(8.dp)) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "track")
    Canvas(modifier) {
        drawLine(track, Offset(size.height / 2, center.y), Offset(size.width - size.height / 2, center.y), size.height, StrokeCap.Round)
        if (animated > 0f) drawLine(accent, Offset(size.height / 2, center.y),
            Offset((size.width - size.height) * animated + size.height / 2, center.y), size.height, StrokeCap.Round)
    }
}

@Composable
private fun ProviderBanner(name: String, usage: UsageSnapshot?, error: String?, loading: Boolean, now: Long, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, modifier = Modifier.fillMaxWidth(), shape = cardShape, color = colors.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandIcon(name, Modifier.size(28.dp))
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                usage?.plan?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
            }
            val windows = usage?.groups?.firstOrNull { it.name == name }?.windows.orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                windows.forEach { window -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(window.title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text("${percent(window.usedPercent)}%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    UsageTrack((window.usedPercent / 100).toFloat(), if (window.usedPercent >= 100) colors.error else if (window.seconds == 18000L) colors.primary else colors.tertiary, Modifier.fillMaxWidth().height(5.dp))
                    Text(window.resetAt?.let { if (it > now) countdown(it - now) else "Reset fällig" } ?: "Termin unbekannt", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                } }
            }
            if (windows.isEmpty()) Text(if (loading) "Verbrauch wird geladen…" else "Keine Zeitfenster gemeldet", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (error != null) "Abruf fehlgeschlagen" else if (loading) "Aktualisiert…" else usage?.let { "Genutzt · ${age(now - it.fetchedAt)}" } ?: "Noch keine Messung", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Details", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}

@Composable
private fun ProviderTabs(names: List<String>, active: String, select: (String) -> Unit, inset: Boolean = true) {
    SegmentedTabGroup(names, active, select, { it },
        modifier = if (inset) Modifier.padding(20.dp, 4.dp, 20.dp, 12.dp) else Modifier,
        fillEqually = true, icon = { ImageVector.vectorResource(if (it == "Codex") R.drawable.ic_brand_codex else R.drawable.ic_brand_claude) })
}

@Composable
private fun ResetScreen(state: UiState, now: Long, provider: String, select: (String) -> Unit, open: (String, String) -> Unit) {
    val names = buildList { if (state.connected) add("Codex"); if (state.claudeConnected) add("Claude Code") }
    val active = provider.takeIf { it in names } ?: names.firstOrNull() ?: return
    val consensus = ForecastConsensus.combine(active, state.forecasts, state.community, now)
    val usage = if (active == "Codex") state.usage else state.claudeUsage
    Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
        if (names.size > 1) ProviderTabs(names, active, select)
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionTitle("Reset-Chance") }
            item {
                SoftCard {
                    if (consensus.isEmpty()) {
                        if (state.forecastLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.forecastLoading) "Schätzungen werden geladen…" else "Keine aktuelle Schätzung", style = MaterialTheme.typography.titleMedium)
                    } else consensus.forEachIndexed { index, chance ->
                        if (index > 0) Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(horizon(chance.hours), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${chance.sources.size} ${if (chance.sources.size == 1) "Quelle" else "Quellen"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ProbabilityBar(chance.percent, Modifier.semantics { contentDescription = "${horizon(chance.hours)}: ${percent(chance.percent)} Prozent Reset-Chance, ${chance.sources.size} Quellen" })
                    }
                    Text("Öffentliche Schätzung", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            item {
                Surface(shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        MenuRow("Quellen & Berechnung", Icons.Rounded.QueryStats) { open(active, "Quellen") }
                        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        MenuRow("Reset-Verlauf", Icons.Rounded.History) { open(active, "Verlauf") }
                    }
                }
            }
            usage?.takeIf { it.availableResets != null || it.resetCredits.isNotEmpty() || it.creditsWarning != null }?.let { snapshot ->
                item { SectionTitle("Dein Vorrat") }
                item { BankedCard(snapshot, now, active == "Codex" && state.creditsLoading) }
            }
        }
    }
}

private fun horizon(hours: Int) = if (hours % 24 == 0 && hours > 48) "in ${hours / 24} Tagen" else "in $hours Stunden"

@Composable
private fun ProbabilityBar(value: Double, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val animated by animateFloatAsState((value / 100).toFloat().coerceIn(0f, 1f), label = "probability")
    Box(modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val corner = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx())
            drawRoundRect(colors.surfaceContainerHigh, cornerRadius = corner)
            if (animated >= .04f) drawRoundRect(colors.primary.copy(alpha = .18f), size = Size(size.width * animated, size.height), cornerRadius = corner)
            val y = size.height - 5.dp.toPx()
            drawLine(colors.primary.copy(alpha = .12f), Offset(16.dp.toPx(), y), Offset(size.width - 16.dp.toPx(), y), 2.dp.toPx(), StrokeCap.Round)
            if (animated > 0) drawLine(colors.primary, Offset(16.dp.toPx(), y), Offset(16.dp.toPx() + (size.width - 32.dp.toPx()) * animated, y), 2.dp.toPx(), StrokeCap.Round)
        }
        Text("${percent(value)}%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MenuRow(title: String, icon: ImageVector, open: () -> Unit) {
    Surface(onClick = open, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp, 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResetDetailsScreen(state: UiState, now: Long, model: UsageViewModel, provider: String, destination: String) {
    val uri = LocalUriHandler.current
    val sources = historySources(provider)
    var historySource by rememberSaveable(provider) { mutableStateOf(if (provider == "Codex") "api:${state.source.name}" else sources.firstOrNull()?.id.orEmpty()) }
    LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (destination == "Quellen") {
            item { SoftCard { Disclosure("So wird gerechnet") {
                Text("Pro Zeitraum zählt jede aktuelle Quelle einmal. Der angezeigte Wert ist ihr einfacher Durchschnitt, keine Addition und keine kalibrierte Wahrscheinlichkeit. Zeiträume und Anbieter bleiben getrennt.", style = MaterialTheme.typography.bodySmall)
                Text("Fehlgeschlagene Abrufe und Werte mit Abruf- oder Quellenstand älter als 2 Stunden zählen nicht. Ohne Quellenstand gilt der Abrufzeitpunkt; das Alter des Modells bleibt unbekannt.", style = MaterialTheme.typography.bodySmall)
            } } }
            if (provider == "Codex") ResetSource.entries.forEach { source -> item(key = source.name) {
                val result = state.forecasts[source]
                val forecast = result?.forecast
                SourceCard(source.label, source.site, forecast?.chances.orEmpty(), forecast?.fetchedAt, forecast?.publishedAt, result?.error, now, state.forecastLoading) {
                    forecast?.confidence?.let { Text("Vertrauen: $it", style = MaterialTheme.typography.bodySmall) }
                    forecast?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    forecast?.accuracy?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            } }
            CommunitySource.entries.filter { it.provider == provider }.forEach { source -> item(key = source.name) {
                val result = state.community[source]
                val report = result?.report
                SourceCard(source.label, source.site, report?.let { listOf(it.chance) }.orEmpty(), report?.fetchedAt, report?.publishedAt, result?.error, now, state.forecastLoading) {
                    report?.expectedAt?.let { Text("Geschätzter Termin: ${date(it)}${if (it < now) " · vergangen" else ""}", style = MaterialTheme.typography.bodySmall) }
                }
            } }
        } else {
            item {
                SegmentedTabGroup(sources, sources.firstOrNull { it.id == historySource } ?: sources.first(), { source ->
                    historySource = source.id
                    ResetSource.entries.firstOrNull { "api:${it.name}" == source.id }?.let(model::setSource)
                }, { it.label })
                if (historySource.startsWith("api:")) {
                    state.feedError?.let { Notice(it, true) }
                    if (state.feedLoading && state.feed?.source?.name != historySource.substringAfter(':')) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            val sourceLabel = sources.firstOrNull { it.id == historySource }?.label.orEmpty()
            val events = historyEvents(provider, historySource, state.feed, state.community)
            if (events.isEmpty()) item { SoftCard { Text("Noch keine Meldungen", style = MaterialTheme.typography.titleMedium) } }
            items(events, key = { it.id + ":" + it.at }) { event -> SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(event.kind, style = MaterialTheme.typography.titleSmall)
                        Text(date(event.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    event.url?.let { link -> IconButton(onClick = { uri.openUri(link) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Originalquelle öffnen") } }
                }
                if (event.summary.isNotBlank()) Disclosure("Meldung") { Text(event.summary, style = MaterialTheme.typography.bodySmall) }
                Text(sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }

        }
    }
}

@Composable
private fun SourceCard(label: String, url: String, chances: List<ForecastChance>, fetched: Long?, published: Long?, error: String?, now: Long, loading: Boolean,
    details: @Composable ColumnScope.() -> Unit) {
    val uri = LocalUriHandler.current
    val fresh = fetched != null && error == null && ForecastConsensus.isFresh(fetched, published, now)
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { uri.openUri(url) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "${label} öffnen", Modifier.size(20.dp)) }
        }
        Text(when { error != null -> "Abruf fehlgeschlagen · nicht eingerechnet"; fetched == null -> if (loading) "Wird geladen…" else "Noch keine Daten"; !fresh -> "Veraltet · nicht eingerechnet"; published == null -> "Eingerechnet · Modellstand unbekannt"; else -> "Eingerechnet" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        chances.forEach { chance -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(horizon(chance.hours), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${percent(chance.percent)}%", style = MaterialTheme.typography.titleMedium)
        } }
        Disclosure("Details") {
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Quellenstand: ${published?.let { date(it, true) } ?: "unbekannt"}", style = MaterialTheme.typography.labelSmall)
            fetched?.let { Text("Abgerufen: ${date(it, true)}", style = MaterialTheme.typography.labelSmall) }
            chances.forEach { chance -> if (chance.low != null && chance.high != null) Text("${horizon(chance.hours)}: ${percent(chance.low)}–${percent(chance.high)}% Quellenintervall", style = MaterialTheme.typography.labelSmall) }
            details()
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, model: UsageViewModel, accounts: () -> Unit, notifications: () -> Unit) {
    val uri = LocalUriHandler.current
    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp, 8.dp, 24.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SoftCard {
            Text("Deine Accounts", style = MaterialTheme.typography.titleMedium)
            if (state.connected) Text("Codex verbunden", style = MaterialTheme.typography.bodyMedium)
            if (state.claudeConnected) Text("Claude Code verbunden", style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = accounts) { Text("Anmeldungen verwalten") }
        }
        Surface(shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) { MenuRow("Benachrichtigungen & Aktualisierung", Icons.Rounded.Notifications, notifications) }
        SoftCard {
            Text("Darstellung", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Hell", ThemeMode.DARK to "Dunkel").forEach { (theme, label) -> FilterChip(state.theme == theme, { model.setTheme(theme) }, label = { Text(label) }) }
            }
            SwitchRow("Android-Farben", "Dein Hintergrund bestimmt die Palette", state.dynamicColor, model::setDynamic)
            SwitchRow("Tiefschwarz", "Für den dunklen Modus", state.amoled, model::setAmoled)
        }
        Disclosure("Über AI Usage · ${BuildConfig.VERSION_NAME}") {
            Text("Basis: AniHyou, GPL-3.0. Designreferenz: AniSync. Google Sans Flex: OFL-1.1. Anbieterlogos kennzeichnen die verbundenen Dienste.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/madebycli/AiUsage-Android") }) { Text("Quellcode") }
            Text("Inoffizielle App, unabhängig von OpenAI und Anthropic.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/Marco-9456/AniSync") }) { Text("AniSync") }
            Text("Jede Aktualisierung fragt den Anbieter ab. Serverwerte können verzögert sein; bei Fehlern bleiben alte Messungen sichtbar.", style = MaterialTheme.typography.bodySmall)
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
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (close != null) TextButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Text("Zurück") }
        if (!state.connected && !state.claudeConnected) {
            Spacer(Modifier.height(20.dp))
            Text("AI Usage", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) { BrandIcon("Codex", Modifier.size(40.dp)) } }
                Spacer(Modifier.width(16.dp))
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) { BrandIcon("Claude Code", Modifier.size(40.dp)) } }
            }
            Text("Dein Verbrauch.\nDein Überblick.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("Verbinde deine Tools. Alles bleibt auf deinem Gerät.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
                SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandIcon("Codex")
                Text("Codex", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.connected) Icon(Icons.Rounded.CheckCircle, "Verbunden", tint = MaterialTheme.colorScheme.primary)
            }
            if (state.connected) TextButton(onClick = { confirm = "Codex" }) { Text("Verbindung trennen") }
            else FilledTonalButton(onClick = model::connect, enabled = !state.loginLoading && !state.disconnecting, modifier = Modifier.fillMaxWidth()) { Text("Mit Codex verbinden") }
        }
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandIcon("Claude Code")
                Text("Claude Code", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.claudeConnected) Icon(Icons.Rounded.CheckCircle, "Verbunden", tint = MaterialTheme.colorScheme.tertiary)
            }
            if (state.claudeConnected) {
                Text(if (state.claudeUsage == null) "Angemeldet · Verbrauch noch nicht geladen" else "Angemeldet · Verbrauch geladen", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { confirm = "Claude Code" }) { Text("Verbindung trennen") }
            }
            FilledTonalButton(onClick = model::beginClaudeLogin, enabled = !state.claudeRefreshing && !state.claudeDisconnecting,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.claudeConnected) "Am Handy neu anmelden" else "Mit Claude Code verbinden") }
            TextButton(onClick = { claudeForm = !claudeForm }, enabled = !state.claudeDisconnecting) { Text(if (claudeForm) "Import schließen" else "Vorhandene Anmeldung importieren") }
            if (claudeForm) {
                Text("Importiere deine Claude-Code-Anmeldung vom Computer oder füge einen OAuth-Token ein.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.claudeRefreshing) { Icon(Icons.Rounded.UploadFile, null); Text("Datei auswählen") }
                OutlinedTextField(secret, { secret = it.take(65_536) }, label = { Text("OAuth-Token oder Zugangsdaten-JSON") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(), maxLines = 3)
                Button(onClick = { model.importClaude(secret); secret = "" }, enabled = secret.isNotBlank() && !state.claudeRefreshing, modifier = Modifier.fillMaxWidth()) { Text(if (state.claudeRefreshing) "Wird verbunden…" else "Anmeldung importieren") }
                Disclosure("Wo finde ich die Anmeldung?") {
                    Text("Claude Code: ~/.claude/.credentials.json (Windows: %USERPROFILE%\\.claude\\.credentials.json). Benötigt eine Anmeldung mit Profilzugriff. claude setup-token und API-Keys eignen sich nicht für die Abo-Verbrauchsanzeige.", style = MaterialTheme.typography.bodySmall)
                    Text("Die App liest die Anmeldung nur und erneuert keinen kopierten Refresh-Token. Bei Ablauf erneut importieren. Tokens bleiben verschlüsselt auf diesem Gerät.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.claudeRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.claudeError?.let { Notice(it, true) }
            if (state.claudeConnected && state.claudeError != null) OutlinedButton(onClick = model::refreshClaude,
                enabled = !state.claudeRefreshing && !state.claudeDisconnecting, modifier = Modifier.fillMaxWidth()) { Text("Verbrauch erneut laden") }
            fileError?.let { Notice(it, true) }
            state.claudeError?.takeIf { !state.claudeConnected }?.let { TextButton(onClick = model::disconnectClaude,
                enabled = !state.claudeDisconnecting) { Text("Lokale Claude-Daten zurücksetzen") } }
        }
        state.usageError?.takeIf { !state.connected }?.let { Notice(it, true); TextButton(onClick = model::disconnect) { Text("Lokale Codex-Daten zurücksetzen") } }
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, title = { Text("$confirm trennen?") }, text = { Text("Die Anmeldung und Messungen dieses Anbieters werden auf dem Gerät gelöscht.") }, confirmButton = { TextButton(onClick = { if (confirm == "Codex") model.disconnect() else model.disconnectClaude(); confirm = null }) { Text("Trennen") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } })
}

@Composable
private fun ClaudeLoginScreen(state: UiState, model: UsageViewModel) {
    val uri = LocalUriHandler.current
    var code by remember(state.claudeLoginUrl) { mutableStateOf("") }
    var browserError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(8.dp))
        FlowerMark()
        Text("Einmal verbinden.\nAlles im Blick.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Melde dich bei Claude an und gib den Zugriff frei. Kopiere anschließend den angezeigten Code und kehre hierher zurück.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.primary)
                Text("1 · Bei Claude anmelden", style = MaterialTheme.typography.titleMedium)
            }
            Text("Google oder E-Mail funktionieren im Browser. Du brauchst keinen Computer.", style = MaterialTheme.typography.bodySmall)
            if (state.claudeLoginUrl != null) Button(onClick = {
                try { uri.openUri(state.claudeLoginUrl); browserError = null }
                catch (_: Exception) { browserError = "Browser konnte nicht geöffnet werden. Bitte einen Browser auf dem Handy aktivieren." }
            }, enabled = !state.claudeLoginBusy, modifier = Modifier.fillMaxWidth()) { Text("Claude im Browser öffnen") }
            else if (!state.claudeLoginBusy) Button(onClick = model::beginClaudeLogin,
                modifier = Modifier.fillMaxWidth()) { Text("Neue Anmeldung starten") }
        }
        if (state.claudeLoginUrl != null) SoftCard {
            Text("2 · Code einfügen", style = MaterialTheme.typography.titleMedium)
            Text("Den vollständigen Code kopieren, inklusive # und dem Text dahinter. Dieser Anmeldeversuch gilt 10 Minuten.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(code, { code = it.take(4096) }, enabled = !state.claudeLoginBusy,
                label = { Text("Anmeldecode") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.completeClaudeLogin(code); code = "" }, enabled = code.isNotBlank() && !state.claudeLoginBusy,
                modifier = Modifier.fillMaxWidth()) { Text("Anmeldung abschließen") }
        }
        if (state.claudeLoginBusy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Anmeldung wird vorbereitet oder übernommen…", style = MaterialTheme.typography.bodySmall) }
        state.claudeLoginError?.let { Notice(it, true) }
        browserError?.let { Notice(it, true) }
        Disclosure("Über diese Anmeldung") {
            Text("Experimenteller Browser-Login über die Claude-Code-Freigabe. Diese umfasst Profil- und Modellzugriff. AI Usage ruft nur deine Verbrauchsdaten ab. Die Anmeldung wird verschlüsselt auf diesem Handy gespeichert und hier erneuert.", style = MaterialTheme.typography.bodySmall)
        }
    }
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
internal fun SoftCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = cardShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
@Composable
internal fun SectionTitle(text: String) {
    Row(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.size(4.dp, 22.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}
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
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
