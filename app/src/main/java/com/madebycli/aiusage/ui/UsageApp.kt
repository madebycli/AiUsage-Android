@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package com.madebycli.aiusage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.composables.DefaultScaffoldWithSmallTopAppBar
import com.madebycli.aiusage.AppTab
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.UsageViewModel

/** Pages above the tab bar. Routes are strings so they survive process death: `account|<id>`. */
private object Route {
    const val SETTINGS = "settings"
    const val ACCOUNTS = "accounts"
    const val NOTIFICATIONS = "notifications"
    const val NAVIGATION = "navigation"
    fun account(id: String) = "account|$id"
    fun reset(provider: String, page: String) = "reset|$provider|$page"
    fun accountId(route: String): String? = route.takeIf { it.startsWith("account|") }?.substringAfter('|')
}

@Composable
fun UsageApp(state: UiState, model: UsageViewModel, notificationAccount: String? = null, notificationEvent: Long = 0) {
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
    LaunchedEffect(notificationEvent, notificationAccount, state.accounts.size) {
        if (notificationEvent > 0 && notificationEvent != handledNotification && notificationAccount != null && state.account(notificationAccount) != null) {
            handledNotification = notificationEvent
            tab = AppTab.USAGE
            show(Route.account(notificationAccount))
        }
    }

    @Composable fun Page(title: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) =
        DefaultScaffoldWithSmallTopAppBar(
            title = title, scrollBehavior = detailScroll,
            modifier = Modifier.nestedScroll(detailScroll.nestedScrollConnection),
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
            actions = actions
        ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { content() } }

    val screen = when {
        state.initializing -> Screen("init", 0)
        loginProvider != null -> Screen("login:$loginProvider", stack.size + 1)
        !state.anyConnected -> Screen("welcome", 0)
        route != null -> Screen("route:$route", stack.size)
        else -> Screen("main", 0)
    }

    AnimatedContent(screen, transitionSpec = pageTransition(), label = "pages") { target ->
        val key = target.key
        when {
            key == "init" -> Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            key.startsWith("login:") -> {
                val provider = key.removePrefix("login:")
                DefaultScaffoldWithSmallTopAppBar(
                    title = "$provider verbinden", scrollBehavior = detailScroll,
                    navigationIcon = { IconButton(onClick = { model.cancelLogin(provider) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } }
                ) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { ConnectScreen(provider, state, model) } }
            }
            key == "welcome" -> Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { AccountsScreen(state, model) } }
            key.startsWith("route:") -> {
                val current = key.removePrefix("route:")
                when {
                    current == Route.SETTINGS -> Page("Einstellungen") {
                        SettingsScreen(state, model, { stack.add(Route.ACCOUNTS) }, { stack.add(Route.NOTIFICATIONS) }, { stack.add(Route.NAVIGATION) })
                    }
                    current == Route.ACCOUNTS -> Page("Anmeldungen") { AccountsScreen(state, model, sort = { tab = AppTab.USAGE; stack.clear(); sorting = true }) }
                    current == Route.NOTIFICATIONS -> Page("Benachrichtigungen") { NotificationSettings(state, model) }
                    current == Route.NAVIGATION -> Page("Navigation & Start") { NavigationSettings(state, model) }
                    current.startsWith("reset|") -> {
                        val (_, provider, page) = current.split('|')
                        Page("$page · $provider", { RefreshButton(state.refreshing, onClick = model::refresh); Spacer(Modifier.width(16.dp)) }) {
                            ResetDetailsScreen(state, now, model, provider, page)
                        }
                    }
                    Route.accountId(current) != null -> {
                        val entry = state.account(Route.accountId(current))
                        if (entry != null) AccountPage(entry, state, now, model, detailScroll, ::back,
                            stats = if (AppTab.STATS in visibleTabs) ({ statsScope = entry.id; tab = AppTab.STATS; stack.clear() }) else null)
                        else Box(Modifier.fillMaxSize())
                    }
                    else -> Box(Modifier.fillMaxSize())
                }
            }
            else -> {
                // The bar stays fixed: its scroll behavior is not connected to the content, so it never collapses.
                DefaultScaffoldWithSmallTopAppBar(
                    title = if (sorting) "Reihenfolge" else tab.title.let { if (tab == AppTab.USAGE) "AI Usage" else it },
                    accentTitle = true,
                    scrollBehavior = mainScroll,
                    actions = {
                        if (sorting) {
                            FilledTonalButton(onClick = { sorting = false }) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fertig") }
                        } else {
                            if (tab == AppTab.USAGE && state.accounts.size > 1) IconButton(onClick = { sorting = true }) { Icon(Icons.Rounded.SwapVert, "Reihenfolge ändern") }
                            IconButton(onClick = { stack.add(Route.SETTINGS) }) { Icon(Icons.Rounded.Settings, "Einstellungen") }
                            RefreshButton(state.refreshing, onClick = model::refresh)
                        }
                        Spacer(Modifier.width(16.dp))
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            visibleTabs.forEach { destination ->
                                NavigationBarItem(selected = tab == destination, onClick = { tab = destination },
                                    icon = { Icon(destination.icon, null) }, label = { Text(destination.title) })
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                        AnimatedContent(tab.ordinal, transitionSpec = tabTransition(visibleTabs.map { it.ordinal }), label = "tabs") { shown ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                when (AppTab.entries[shown]) {
                                    AppTab.RESETS -> ResetScreen(state, now, resetProvider, { resetProvider = it }) { provider, page -> stack.add(Route.reset(provider, page)) }
                                    AppTab.STATS -> StatsTab(state, now, statsScope) { statsScope = it }
                                    AppTab.USAGE -> UsageScreen(state, now, sorting, { stack.add(Route.account(it)) }, { stack.add(Route.ACCOUNTS) }, model::reorderAccounts)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One account: its limits and, in the menu, rename, sign in again and disconnect. */
@Composable
private fun AccountPage(entry: com.madebycli.aiusage.AccountState, state: UiState, now: Long, model: UsageViewModel, scroll: TopAppBarScrollBehavior, back: () -> Unit, stats: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    DefaultScaffoldWithSmallTopAppBar(
        title = entry.title, scrollBehavior = scroll,
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
        actions = {
            RefreshButton(entry.refreshing) { model.refresh(entry.id) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mehr") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Neu anmelden") }, onClick = { menu = false; model.connect(entry.provider, entry.id) })
                    DropdownMenuItem(text = { Text("Trennen", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirm = true })
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) { AccountDetailScreen(state, now, entry, stats) }
    }
    if (renaming) RenameDialog(entry, { renaming = false }) { model.renameAccount(entry.id, it); renaming = false }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("${entry.title} trennen?") },
        text = { Text("Die Anmeldung und die gespeicherten Messungen und Statistiken dieses Accounts werden auf dem Gerät gelöscht. Andere Accounts bleiben unberührt.") },
        confirmButton = { TextButton(onClick = { confirm = false; model.removeAccount(entry.id) }) { Text("Trennen", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } })
}
