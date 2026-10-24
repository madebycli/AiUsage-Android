package com.madebycli.aiusage.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.*
import com.madebycli.aiusage.data.StatsMetric

/** Which pages the bottom bar shows and in which order, where the app opens, and the default statistics view. */
@Composable
internal fun NavigationSettings(state: UiState, model: UsageViewModel) {
    val nav = state.nav
    fun move(tab: AppTab, by: Int) {
        val order = nav.order.toMutableList()
        val from = order.indexOf(tab)
        val to = (from + by).coerceIn(0, order.lastIndex)
        if (from != to) { order.add(to, order.removeAt(from)); model.setTabOrder(order) }
    }
    LazyColumn(Modifier.widthIn(max = maxContentWidth).fillMaxWidth(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "preview") { BarPreview(nav) }
        item(key = "bar-title") { SectionTitle("Untere Leiste") }
        items(nav.order, key = { it.name }) { tab ->
            val visible = tab in nav.visible
            SoftCard(PaddingValues(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), Modifier.animateItem()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(tab.icon, null, tint = if (visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(tab.title, style = MaterialTheme.typography.titleSmall)
                        Text(if (tab == AppTab.USAGE) "Immer sichtbar" else if (visible) "Sichtbar" else "Ausgeblendet",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { move(tab, -1) }, enabled = nav.order.indexOf(tab) > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "${tab.title} nach vorn") }
                    IconButton(onClick = { move(tab, 1) }, enabled = nav.order.indexOf(tab) < nav.order.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "${tab.title} nach hinten") }
                    Switch(visible, { model.setTabVisible(tab, it) }, enabled = tab != AppTab.USAGE)
                }
            }
        }
        item(key = "start-title") { SectionTitle("Startseite") }
        item(key = "start") {
            SoftCard(PaddingValues(vertical = 8.dp)) {
                StartScreen.entries.forEach { option ->
                    val available = option.tab == null || option.tab in nav.visible
                    Row(Modifier.fillMaxWidth().selectable(nav.start == option, enabled = available, role = Role.RadioButton) { model.setStartScreen(option) }
                        .padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(nav.start == option, null, enabled = available, modifier = Modifier.padding(12.dp))
                        Column {
                            Text(option.title, style = MaterialTheme.typography.bodyLarge, color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (option == StartScreen.LAST) Text("Die Seite, auf der du die App zuletzt verlassen hast", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!available) Text("Ausgeblendet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item(key = "start-note") {
            Text("Gilt nur nach einem vollständigen Neustart der App. Kommt die App aus dem Hintergrund zurück, bleibt sie dort, wo du warst.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "default-title") { SectionTitle("Statistik-Standard") }
        item(key = "defaults") {
            SoftCard {
                Text("Messwert", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SegmentedTabGroup(StatsMetric.entries, nav.statsMetric, model::setStatsMetric, { it.title }, fillEqually = true)
                Text("Zeitraum", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SegmentedTabGroup(StatsRange.entries, nav.statsRange, model::setStatsRange, { it.title }, fillEqually = true)
            }
        }
    }
}

@Composable
private fun BarPreview(nav: NavSettings) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).animateContentSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
            nav.visible.forEach { tab ->
                val selected = tab == nav.initial
                val pill by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, label = "pill")
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.background(pill, RoundedCornerShape(16.dp)).padding(horizontal = 20.dp, vertical = 4.dp)) { Icon(tab.icon, null) }
                    Text(tab.title, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
