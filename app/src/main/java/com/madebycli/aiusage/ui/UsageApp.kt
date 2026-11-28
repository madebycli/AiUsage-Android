@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.madebycli.aiusage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.madebycli.aiusage.AppTab
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.R
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

/** Pages above the tab bar. Routes are strings so they survive process death: `account|<id>`. */
private object Route {
    const val SETTINGS = "settings"
    fun account(id: String) = "account|$id"
    fun reset(provider: String, page: String) = "reset|$provider|$page"
    fun accountId(route: String): String? = route.takeIf { it.startsWith("account|") }?.substringAfter('|')
}

@Composable
fun UsageApp(state: UiState, model: UsageViewModel, notificationAccount: String? = null, notificationEvent: Long = 0, launchTab: String? = null) {
    var tab by rememberSaveable { mutableStateOf(state.nav.initial) }
    var resetProvider by rememberSaveable { mutableStateOf(Providers.CODEX) }
    var statsScope by rememberSaveable { mutableStateOf(ALL_ACCOUNTS) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var handledNotification by rememberSaveable { mutableLongStateOf(0) }
    var handledSignIn by rememberSaveable { mutableLongStateOf(0) }
    val stack = rememberSaveable(saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })) { mutableStateListOf<String>() }
    val route = stack.lastOrNull()
    val now = rememberNow()
    val mainScroll = TopAppBarDefaults.pinnedScrollBehavior()
    val detailScroll = TopAppBarDefaults.pinnedScrollBehavior()
    val loginProvider = state.loginProvider
    val visibleTabs = state.nav.visible

    fun show(vararg routes: String) { stack.clear(); stack.addAll(routes) }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else if (sorting) sorting = false else tab = AppTab.USAGE }

    BackHandler(enabled = loginProvider != null || (state.anyConnected && (stack.isNotEmpty() || sorting || tab != AppTab.USAGE))) {
        if (loginProvider != null) model.cancelLogin(loginProvider) else back()
    }
    LaunchedEffect(visibleTabs) { if (tab !in visibleTabs) tab = AppTab.USAGE }
    LaunchedEffect(tab) { model.setLastTab(tab); if (tab != AppTab.USAGE) sorting = false }
    LaunchedEffect(state.accounts.size) { if (state.accounts.size < 2) sorting = false }
    LaunchedEffect(state.accounts.map { it.id }) {
        stack.removeAll { entry -> Route.accountId(entry)?.let { state.account(it) == null } == true }
        if (statsScope != ALL_ACCOUNTS && state.account(statsScope) == null) statsScope = ALL_ACCOUNTS
    }
    LaunchedEffect(state.anyConnected, state.initializing, state.accounts.size) { model.syncBackground() }
    LaunchedEffect(tab, state.accounts.size) { if (tab == AppTab.RESETS && state.anyConnected) model.loadResetData() }
    LaunchedEffect(state.signedIn) {
        val event = state.signedIn
        if (event != null && event.sequence > handledSignIn && state.account(event.accountId) != null) {
            handledSignIn = event.sequence
            tab = AppTab.USAGE
            show(Route.account(event.accountId))
        }
    }
    LaunchedEffect(notificationEvent, notificationAccount, launchTab, state.accounts.size) {
        if (notificationEvent > 0 && notificationEvent != handledNotification && notificationAccount != null && state.account(notificationAccount) != null) {
            handledNotification = notificationEvent
            tab = AppTab.USAGE
            show(Route.account(notificationAccount))
        } else if (notificationEvent > 0 && notificationEvent != handledNotification && notificationAccount == null && launchTab != null) {
            // A widget opens its own tab, if that tab is shown in the navigation.
            handledNotification = notificationEvent
            AppTab.entries.firstOrNull { it.name == launchTab }?.takeIf { it in visibleTabs }?.let { stack.clear(); sorting = false; tab = it }
        }
    }

    val screen = when {
        state.initializing -> Screen("init", 0)
        loginProvider != null -> Screen("login:$loginProvider", stack.size + 1)
        !state.anyConnected -> Screen("welcome", 0)
        route != null -> Screen("route:$route", stack.size)
        else -> Screen("main", 0)
    }

    SharedTransitionLayout {
        val shared = this
        // A solid base under every page and transition: nothing can shine through while pages move.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AnimatedContent(screen, transitionSpec = windowTransition(), label = "pages") { target ->
                OrionSharedScreen(shared, this) {
                    val key = target.key
                    when {
                        key == "init" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                        key.startsWith("login:") -> {
                            val provider = key.removePrefix("login:")
                            DefaultScaffoldWithSmallTopAppBar(
                                title = stringResource(R.string.connect_title, Providers.label(provider)), scrollBehavior = detailScroll,
                                navigationIcon = { IconButton(onClick = { model.cancelLogin(provider) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } }
                            ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { ConnectScreen(provider, state, model) } }
                        }
                        key == "welcome" -> WelcomeScreen(state, model)
                        key.startsWith("route:") -> {
                            val current = key.removePrefix("route:")
                            when {
                                current == Route.SETTINGS -> SettingsScreen(state, model, onBack = ::back,
                                    onSortAccounts = { tab = AppTab.USAGE; stack.clear(); sorting = true })
                                current.startsWith("reset|") -> {
                                    val (_, provider, page) = current.split('|')
                                    ResetDetailsPage(state, now, model, provider, page, ::back)
                                }
                                Route.accountId(current) != null -> {
                                    val entry = state.account(Route.accountId(current))
                                    if (entry != null) AccountPage(entry, state, now, model, ::back,
                                        stats = if (AppTab.STATS in visibleTabs) ({ statsScope = entry.id; tab = AppTab.STATS; stack.clear() }) else null)
                                    else Box(Modifier.fillMaxSize())
                                }
                                else -> Box(Modifier.fillMaxSize())
                            }
                        }
                        else -> MainScaffold(state, model, tab, { tab = it }, sorting, { sorting = it }, mainScroll, now,
                            openSettings = { stack.add(Route.SETTINGS) }, openAccount = { stack.add(Route.account(it)) },
                            openReset = { provider, page -> stack.add(Route.reset(provider, page)) },
                            resetProvider = resetProvider, selectResetProvider = { resetProvider = it },
                            statsScope = statsScope, selectStatsScope = { statsScope = it })
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(state: UiState, model: UsageViewModel, tab: AppTab, select: (AppTab) -> Unit, sorting: Boolean, setSorting: (Boolean) -> Unit,
    scroll: TopAppBarScrollBehavior, now: Long, openSettings: () -> Unit, openAccount: (String) -> Unit, openReset: (String, String) -> Unit,
    resetProvider: String, selectResetProvider: (String) -> Unit, statsScope: String, selectStatsScope: (String) -> Unit) {
    val visibleTabs = state.nav.visible
    // The bar stays fixed on the main tabs: its scroll behavior is not connected to the content.
    DefaultScaffoldWithSmallTopAppBar(
        title = if (sorting) stringResource(R.string.accounts_order) else if (tab == AppTab.USAGE) stringResource(R.string.app_name) else stringResource(tab.label),
        scrollBehavior = scroll,
        actions = {
            if (sorting) {
                FilledTonalButton(onClick = { setSorting(false) }) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.done)) }
            } else {
                if (tab == AppTab.USAGE && state.accounts.size > 1) IconButton(onClick = { setSorting(true) }) { Icon(Icons.Rounded.SwapVert, stringResource(R.string.accounts_order)) }
                IconButton(onClick = openSettings, modifier = Modifier.orionSharedBounds(null)) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings)) }
                RefreshButton(state.refreshing, onClick = model::refresh)
            }
            Spacer(Modifier.width(16.dp))
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.panel) {
                visibleTabs.forEach { destination ->
                    NavigationBarItem(selected = tab == destination, onClick = { select(destination) },
                        icon = { Icon(destination.icon, null) }, label = { Text(stringResource(destination.label), maxLines = 1) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(tab.ordinal, transitionSpec = tabTransition(visibleTabs.map { it.ordinal }), label = "tabs") { shown ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    when (AppTab.entries[shown]) {
                        AppTab.USAGE -> UsageScreen(state, now, sorting, openAccount, model::reorderAccounts)
                        AppTab.PLAN -> PlanTab(state, now, openAccount)
                        AppTab.STATS -> StatsTab(state, now, statsScope, selectStatsScope)
                        AppTab.RESETS -> ResetScreen(state, now, resetProvider, selectResetProvider, openReset)
                    }
                }
            }
        }
    }
}
