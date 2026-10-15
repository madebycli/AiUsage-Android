@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.composables.common.SmallCircularProgressIndicator
import com.madebycli.aiusage.ProviderState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.data.*

@Composable
internal fun UsageScreen(state: UiState, now: Long, open: (String) -> Unit) {
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.connectedProviders.forEach { name -> item(key = "banner:$name") { ProviderBanner(name, state.provider(name), now) { open(name) } } }
    }
}

@Composable
internal fun ProviderBanner(name: String, provider: ProviderState, now: Long, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val usage = provider.usage
    val windows = usage?.windows(name).orEmpty()
    Surface(onClick = open, modifier = Modifier.fillMaxWidth(), shape = cardShape, color = colors.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandIcon(name)
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                usage?.plan?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
            }
            if (windows.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                windows.forEach { window -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(window.title, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text("${percent(window.usedPercent)}%", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    UsageTrack((window.usedPercent / 100).toFloat(), windowColor(window), Modifier.fillMaxWidth().height(5.dp))
                    Text(window.resetAt?.let { if (it > now) "Reset in ${countdown(it - now)}" else "Reset fällig" } ?: "Termin unbekannt",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                } }
            } else Text(if (provider.refreshing) "Verbrauch wird geladen…" else "Noch keine Messung", style = MaterialTheme.typography.bodyMedium)
            provider.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    provider.refreshing -> "Aktualisiert…"
                    usage != null -> "Zuletzt ${age(now - usage.fetchedAt)}"
                    else -> ""
                }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Details", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}

@Composable
private fun windowColor(window: UsageWindow) = MaterialTheme.colorScheme.let {
    if (window.usedPercent >= 100) it.error else if (window.seconds == FIVE_HOURS) it.primary else it.tertiary
}

@Composable
internal fun ProviderUsageScreen(state: UiState, now: Long, name: String, stats: (() -> Unit)?) {
    val provider = state.provider(name)
    val usage = provider.usage
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(usage?.plan?.replaceFirstChar { it.titlecase() } ?: "Verbunden", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        provider.error?.let { item { Notice(it, true) } }
        if (name == Providers.CODEX) state.storageWarning?.let { item { Notice(it) } }
        if (usage == null) item { SoftCard {
            if (provider.refreshing) LoadingIndicator()
            Text(if (provider.refreshing) "Verbrauch wird geladen" else "Verbrauch noch nicht geladen", style = MaterialTheme.typography.titleMedium)
            Text("Oben aktualisieren, um es erneut zu versuchen.", style = MaterialTheme.typography.bodySmall)
        } }
        usage?.let { snapshot ->
            val warnings = snapshot.warnings.filterNot { it == snapshot.creditsWarning }
            if (warnings.isNotEmpty()) item { Notice(warnings.joinToString("\n")) }
            snapshot.groups.forEachIndexed { index, group ->
                if (group.name != name) item(key = "title:$index") { SectionTitle(group.name) }
                items(group.windows, key = { "$index:${it.seconds}" }) { window -> WindowCard(window, now) }
            }
            if (snapshot.groups.all { it.windows.isEmpty() }) item { Notice("Der Anbieter meldet aktuell keine Zeitfenster.") }
            if (snapshot.availableResets != null || snapshot.resetCredits.isNotEmpty() || snapshot.creditsWarning != null)
                item { BankedCard(snapshot, now, name == Providers.CODEX && state.creditsLoading) }
            snapshot.extraUsage?.let { item { ExtraUsageCard(it) } }
            snapshot.accountCredits?.let { credits -> item { SoftCard {
                Text("Credit-Guthaben", style = MaterialTheme.typography.titleMedium)
                Text(if (credits.unlimited == true) "Unbegrenzt" else credits.balance?.let { "${number(it)} Credits" } ?: "Nicht gemeldet",
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (credits.available == false) Text("Derzeit nicht verfügbar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } } }
            snapshot.spending?.let { spending -> item { SoftCard {
                Text("Zusätzliches Kontingent", style = MaterialTheme.typography.titleMedium)
                spending.remainingPercent?.let { Text("${percent(it)}% frei", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                Text("Verbrauch ${spending.used?.let { number(it) } ?: "nicht gemeldet"} · Limit ${spending.limit?.let { number(it) } ?: "nicht gemeldet"}", style = MaterialTheme.typography.bodySmall)
                spending.resetAt?.let { Text("Reset ${date(it)}", style = MaterialTheme.typography.labelMedium) }
            } } }
        }
        if (stats != null) item { MenuCard { MenuRow("Statistiken", Icons.Rounded.BarChart, stats) } }
        usage?.let { item { Text("${if (provider.refreshing) "Aktualisiert… · " else ""}Stand ${date(it.fetchedAt, true)} (${age(now - it.fetchedAt)})",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

@Composable
private fun WindowCard(window: UsageWindow, now: Long) {
    val colors = MaterialTheme.colorScheme
    val accent = windowColor(window)
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
                UsageTrack(elapsed, colors.onSurfaceVariant.copy(alpha = .6f), Modifier.weight(1f).height(3.dp))
                Text("${percent(elapsed.toDouble() * 100)}% der Zeit", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun BankedCard(usage: UsageSnapshot, now: Long, loading: Boolean) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.ConfirmationNumber, null, tint = MaterialTheme.colorScheme.primary)
            Text(usage.availableResets?.let { "$it ${if (it == 1) "Reset verfügbar" else "Resets verfügbar"}" } ?: "Resets", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        if (loading) Text("Details werden geladen…", style = MaterialTheme.typography.labelSmall)
        usage.resetCredits.distinctBy { listOf(it.status, it.grantedAt, it.expiresAt, it.title) }.forEachIndexed { index, credit ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.RestartAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(creditStatus(credit.status), style = MaterialTheme.typography.labelLarge)
                    credit.expiresAt?.let { Text("Ablauf ${date(it)}${if (it <= now && credit.status != "expired") " · erreicht" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        usage.creditsWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun creditStatus(status: String) = when (status) {
    "available" -> "Reset"
    "redeemed" -> "Reset · eingelöst"
    "paused" -> "Reset · pausiert"
    "expired" -> "Reset · abgelaufen"
    "unknown" -> "Reset · Verfügbarkeit unbekannt"
    else -> "Reset · derzeit nicht verfügbar"
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

/** Spinner for the app bar; shows the refresh icon while idle. */
@Composable
internal fun RefreshButton(refreshing: Boolean, label: String = "Jetzt aktualisieren", onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        if (refreshing) SmallCircularProgressIndicator() else Icon(Icons.Rounded.Refresh, label)
    }
}
