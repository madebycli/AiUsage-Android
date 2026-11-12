package com.madebycli.aiusage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.madebycli.aiusage.AppTab
import com.madebycli.aiusage.BuildConfig
import com.madebycli.aiusage.ThemeMode
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

internal val AppTab.icon: ImageVector get() = when (this) {
    AppTab.USAGE -> Icons.Rounded.DonutLarge
    AppTab.STATS -> Icons.Rounded.BarChart
    AppTab.RESETS -> Icons.Rounded.History
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsScreen(state: UiState, model: UsageViewModel, accounts: () -> Unit, notifications: () -> Unit, navigation: () -> Unit,
    appearance: () -> Unit, widgets: () -> Unit, about: () -> Unit) {
    var search by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    androidx.activity.compose.BackHandler(search) { search = false; query = "" }
    val tiles = listOf(
        Triple(DesignPage.ACCOUNTS, "${state.accounts.size} Accounts", accounts),
        Triple(DesignPage.APPEARANCE, "Theme, Android-Farben, Tiefschwarz", appearance),
        Triple(DesignPage.NOTIFICATIONS, "Resets, Berechtigungen, Aktualisierung", notifications),
        Triple(DesignPage.NAVIGATION, "Untere Leiste, Startseite, Standardansichten", navigation),
        Triple(DesignPage.WIDGETS, "Vorlagen und Homescreen-Anzeige", widgets),
        Triple(DesignPage.ABOUT, "Version, Quellcode und Lizenzen", about))
    Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHero(DesignPage.SETTINGS)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Deine App", style = MaterialTheme.typography.titleSmallEmphasized, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
            FilledTonalIconButton(onClick = { search = !search; if (!search) query = "" }, shapes = IconButtonDefaults.shapes()) { Icon(if (search) Icons.Rounded.Close else Icons.Rounded.Search, "Einstellungen suchen") }
        }
        androidx.compose.animation.AnimatedVisibility(search, enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
            TextField(query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text("Einstellungen suchen") },
                shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth())
        }
        val found = tiles.filter { (page, desc, _) -> (page.title + " " + desc).contains(query.trim(), true) }
        if (found.isEmpty()) Notice("Keine passenden Einstellungen")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 320.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                found.chunked(columns).forEachIndexed { row, group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        group.forEachIndexed { i, (page, desc, click) ->
                            androidx.compose.runtime.key(page) {
                                OrionBento(page.title, desc, page.icon, page.accent,
                                    page.shape, click,
                                    Modifier.weight(1f).height(if (columns == 1) 220.dp else 180.dp).staggeredEntrance(row * columns + i, page), sharedKey = "settings:${page.name}")
                            }
                        }
                        if (group.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
