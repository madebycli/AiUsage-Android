@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithMediumTopAppBar
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

private enum class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    USAGE("Verbrauch", Icons.Rounded.DonutLarge), RESETS("Resets", Icons.Rounded.History), SETTINGS("Einstellungen", Icons.Rounded.Tune)
}

/** Pages above the tab bar. Routes are strings so they survive process death: `detail|Codex`. */
private object Route {
    const val ACCOUNTS = "accounts"
    const val NOTIFICATIONS = "notifications"
    fun detail(provider: String) = "detail|$provider"
    fun stats(provider: String) = "stats|$provider"
    fun reset(provider: String, page: String) = "reset|$provider|$page"
    fun provider(route: String): String? = route.substringAfter('|', "").substringBefore('|').takeIf { it.isNotEmpty() }
}

@Composable
fun UsageApp(state: UiState, model: UsageViewModel, notificationProvider: String? = null, notificationEvent: Long = 0) {
    var tab by rememberSaveable { mutableStateOf(Tab.USAGE) }
    var resetProvider by rememberSaveable { mutableStateOf(Providers.CODEX) }
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
        route == Route.ACCOUNTS -> Page("Anmeldungen") { AccountsScreen(state, model) }
        route == Route.NOTIFICATIONS -> Page("Benachrichtigungen") { NotificationSettings(state, model) }
        route?.startsWith("reset|") == true -> {
            val (_, provider, page) = route.split('|')
            Page("$page · $provider", refreshAction) { ResetDetailsScreen(state, now, model, provider, page) }
        }
        route?.startsWith("stats|") == true -> {
            val provider = Route.provider(route)!!
            Page("Statistiken · $provider", {
                RefreshButton(state.provider(provider).statsLoading || state.provider(provider).refreshing, "Statistiken aktualisieren") {
                    model.refreshStats(provider, force = true); model.refresh(provider)
                }
                Spacer(Modifier.width(16.dp))
            }) { UsageStatsScreen(provider, state, model, now) }
        }
        route?.startsWith("detail|") == true -> {
            val provider = Route.provider(route)!!
            Page(provider, refreshAction) { ProviderUsageScreen(state, now, provider) { stack.add(Route.stats(provider)) } }
        }
        else -> DefaultScaffoldWithMediumTopAppBar(
            title = when (tab) { Tab.RESETS -> "Resets"; Tab.SETTINGS -> "Einstellungen"; Tab.USAGE -> "AI Usage" },
            accentTitle = true,
            modifier = Modifier.nestedScroll(mainScroll.nestedScrollConnection),
            scrollBehavior = mainScroll, actions = refreshAction,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { destination ->
                        NavigationBarItem(selected = tab == destination, onClick = { tab = destination },
                            icon = { Icon(destination.icon, null) }, label = { Text(destination.title) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                when (tab) {
                    Tab.RESETS -> ResetScreen(state, now, resetProvider, { resetProvider = it }) { provider, page -> stack.add(Route.reset(provider, page)) }
                    Tab.SETTINGS -> SettingsScreen(state, model, { stack.add(Route.ACCOUNTS) }, { stack.add(Route.NOTIFICATIONS) })
                    Tab.USAGE -> UsageScreen(state, now, { stack.add(Route.detail(it)) }, { stack.add(Route.stats(it)) })
                }
            }
        }
    }
}
