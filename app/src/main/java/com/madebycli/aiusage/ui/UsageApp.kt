@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithMediumTopAppBar
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

private enum class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    USAGE("Verbrauch", Icons.Rounded.DonutLarge), STATS("Statistiken", Icons.Rounded.BarChart), RESETS("Resets", Icons.Rounded.History)
}

/** Pages above the tab bar. Routes are strings so they survive process death: `detail|Codex`. */
private object Route {
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
    const val NOTIFICATIONS = "notifications"
    fun detail(provider: String) = "detail|$provider"
    fun reset(provider: String, page: String) = "reset|$provider|$page"
    fun provider(route: String): String? = route.substringAfter('|', "").substringBefore('|').takeIf { it.isNotEmpty() }
}

@Composable
fun UsageApp(state: UiState, model: UsageViewModel, notificationProvider: String? = null, notificationEvent: Long = 0) {
    var tab by rememberSaveable { mutableStateOf(Tab.USAGE) }
    var resetProvider by rememberSaveable { mutableStateOf(Providers.CODEX) }
    var statsProvider by rememberSaveable { mutableStateOf(Providers.CODEX) }
    var handledNotification by rememberSaveable { mutableLongStateOf(0) }
    val stack = rememberSaveable(saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    val route = stack.lastOrNull()
    val now = rememberNow()
    val mainScroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val detailScroll = TopAppBarDefaults.pinnedScrollBehavior()
    val loginProvider = state.loginProvider

    fun show(vararg routes: String) { stack.clear(); stack.addAll(routes) }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.USAGE }

    BackHandler(enabled = loginProvider != null || (state.anyConnected && (stack.isNotEmpty() || tab != Tab.USAGE))) {
        if (loginProvider != null) model.cancelLogin(loginProvider) else back()
    }
    LaunchedEffect(state.statsEnabled) { if (!state.statsEnabled && tab == Tab.STATS) tab = Tab.USAGE }
    LaunchedEffect(state.codex.connected, state.claude.connected) {
        stack.removeAll { route -> Route.provider(route)?.let { !state.provider(it).connected } == true }
    }
    LaunchedEffect(state.codex.connected, state.claude.connected, state.initializing) { model.syncBackground() }
    LaunchedEffect(tab, state.codex.connected, state.claude.connected) { if (tab == Tab.RESETS && state.anyConnected) model.loadResetData() }
    LaunchedEffect(state.codex.loginResult, state.claude.loginResult) {
        val signedIn = Providers.all.firstOrNull { state.provider(it).loginResult > 0 && state.provider(it).connected }
        if (signedIn != null) { tab = Tab.USAGE; show(Route.detail(signedIn)) }
    }
    LaunchedEffect(notificationEvent, notificationProvider, state.codex.connected, state.claude.connected) {
        if (notificationEvent > 0 && notificationEvent != handledNotification && notificationProvider != null &&
            notificationProvider in Providers.all && state.provider(notificationProvider).connected) {
            handledNotification = notificationEvent
            tab = Tab.USAGE
            show(Route.detail(notificationProvider))
        }
    }

    val refreshAction: @Composable RowScope.() -> Unit = {
        RefreshButton(state.refreshing, onClick = model::refresh)
        Spacer(Modifier.width(16.dp))
    }
    @Composable fun Page(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) =
        DefaultScaffoldWithSmallTopAppBar(
            title = title, scrollBehavior = detailScroll,
            modifier = Modifier.nestedScroll(detailScroll.nestedScrollConnection),
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
            actions = actions
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { content() } }

    when {
        state.initializing -> Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() } }
        loginProvider != null -> DefaultScaffoldWithSmallTopAppBar(
            title = "$loginProvider verbinden", scrollBehavior = detailScroll,
            navigationIcon = { IconButton(onClick = { model.cancelLogin(loginProvider) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } }
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { ConnectScreen(loginProvider, state, model) } }
        !state.anyConnected -> Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { AccountsScreen(state, model) } }
        route == Route.SETTINGS -> Page("Einstellungen") { SettingsScreen(state, model, { stack.add(Route.ACCOUNTS) }, { stack.add(Route.NOTIFICATIONS) }) }
        route == Route.ACCOUNTS -> Page("Anmeldungen") { AccountsScreen(state, model) }
        route == Route.NOTIFICATIONS -> Page("Benachrichtigungen") { NotificationSettings(state, model) }
        route?.startsWith("reset|") == true -> {
            val (_, provider, page) = route.split('|')
            Page("$page · $provider", refreshAction) { ResetDetailsScreen(state, now, model, provider, page) }
        }
        route?.startsWith("detail|") == true -> {
            val provider = Route.provider(route)!!
            Page(provider, refreshAction) { ProviderUsageScreen(state, now, provider, stats = if (state.statsEnabled) ({ statsProvider = provider; tab = Tab.STATS; stack.clear() }) else null) }
        }
        else -> DefaultScaffoldWithMediumTopAppBar(
            title = when (tab) { Tab.USAGE -> "AI Usage"; Tab.STATS -> "Statistiken"; Tab.RESETS -> "Resets" },
            accentTitle = true,
            modifier = Modifier.nestedScroll(mainScroll.nestedScrollConnection),
            scrollBehavior = mainScroll,
            actions = {
                if (tab == Tab.STATS) RefreshButton(state.refreshing || state.provider(statsProvider).statsLoading, "Statistiken aktualisieren") {
                    model.refreshStats(statsProvider, force = true); model.refresh()
                } else RefreshButton(state.refreshing, onClick = model::refresh)
                IconButton(onClick = { stack.add(Route.SETTINGS) }) { Icon(Icons.Rounded.Settings, "Einstellungen") }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.filter { it != Tab.STATS || state.statsEnabled }.forEach { destination ->
                        NavigationBarItem(selected = tab == destination, onClick = { tab = destination },
                            icon = { Icon(destination.icon, null) }, label = { Text(destination.title) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                when (tab) {
                    Tab.RESETS -> ResetScreen(state, now, resetProvider, { resetProvider = it }) { provider, page -> stack.add(Route.reset(provider, page)) }
                    Tab.STATS -> StatsTab(state, model, now, statsProvider) { statsProvider = it }
                    Tab.USAGE -> UsageScreen(state, now) { stack.add(Route.detail(it)) }
                }
            }
        }
    }
}
