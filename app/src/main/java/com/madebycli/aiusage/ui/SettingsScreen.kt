/* Settings hub and pages laid out as in Iris (madebycli/iris-android, presentation/settings), which adapts
 * Orion Store ui/Settings.kt Hub, Bento and Page, RookieEnough and contributors, GPL-3.0, e561e91.
 * Hub tile and page share bounds; the page opens on a tinted hero that scrolls away with the content.
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.madebycli.aiusage.ui

import android.Manifest
import android.app.Activity
import android.app.LocaleManager
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.madebycli.aiusage.*
import com.madebycli.aiusage.R
import com.madebycli.aiusage.background.*
import com.madebycli.aiusage.data.Fmt
import com.madebycli.aiusage.widget.*
import java.time.Instant

internal val AppTab.icon: ImageVector get() = when (this) {
    AppTab.USAGE -> Icons.Rounded.DonutLarge
    AppTab.PLAN -> Icons.Rounded.ViewTimeline
    AppTab.STATS -> Icons.Rounded.BarChart
    AppTab.RESETS -> Icons.Rounded.History
}

/** One settings category: hub tile and page share title, accent and shape (Orion's CATS table). */
internal enum class SettingsPage(val title: Int, val icon: ImageVector, val accent: Color, val shape: RoundedPolygon) {
    ACCOUNTS(R.string.page_accounts, Icons.Rounded.Key, OrionAccents.Pink, MaterialShapes.Cookie6Sided),
    BACKGROUND(R.string.page_background, Icons.Rounded.Sync, OrionAccents.Teal, MaterialShapes.Pentagon),
    NOTIFICATIONS(R.string.page_notifications, Icons.Rounded.Notifications, OrionAccents.Orange, MaterialShapes.Sunny),
    APPEARANCE(R.string.page_appearance, Icons.Rounded.Palette, OrionAccents.Purple, MaterialShapes.Flower),
    NAVIGATION(R.string.page_navigation, Icons.Rounded.Tune, OrionAccents.Green, MaterialShapes.Gem),
    WIDGETS(R.string.page_widgets, Icons.Rounded.Widgets, OrionAccents.Sky, MaterialShapes.Clover4Leaf),
    ABOUT(R.string.page_about, Icons.Rounded.Info, OrionAccents.Indigo, MaterialShapes.SoftBurst)
}

/** Accent hues of Orion ui/Settings.kt and ui/Xtra.kt; [orionTonal] turns them into light and dark pairs. */
internal object OrionAccents {
    val Blue = Color(0xFF3B82F6); val Purple = Color(0xFFA855F7); val Sky = Color(0xFF38BDF8); val Orange = Color(0xFFF97316)
    val Green = Color(0xFF22C55E); val Pink = Color(0xFFEC4899); val Teal = Color(0xFF14B8A6); val Yellow = Color(0xFFEAB308)
    val Indigo = Color(0xFF6366F1); val Cyan = Color(0xFF06B6D4)
}

@Composable
private fun SettingsPage.description(state: UiState): String = when (this) {
    SettingsPage.ACCOUNTS -> pluralStringResource(R.plurals.accounts_count, state.accounts.size, state.accounts.size)
    SettingsPage.BACKGROUND -> stringResource(R.string.page_background_desc)
    SettingsPage.NOTIFICATIONS -> stringResource(R.string.page_notifications_desc)
    SettingsPage.APPEARANCE -> stringResource(R.string.page_appearance_desc)
    SettingsPage.NAVIGATION -> stringResource(R.string.page_navigation_desc)
    SettingsPage.WIDGETS -> stringResource(R.string.page_widgets_desc)
    SettingsPage.ABOUT -> stringResource(R.string.page_about_desc, BuildConfig.VERSION_NAME)
}

/** The hub and its pages. Hub and pages cross-fade while the tile grows into its page (shared bounds). */
@Composable
internal fun SettingsScreen(state: UiState, model: UsageViewModel, onBack: () -> Unit, onSortAccounts: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = rememberSaveableStateHolder()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    fun back() { if (open != null) open = null else onBack() }
    BackHandler { back() }
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        val shared = this
        AnimatedContent(open, transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }, label = "settings") { target ->
            val visibility = this
            saved.SaveableStateProvider(target ?: "hub") {
                OrionSharedScreen(shared, visibility) {
                    val page = SettingsPage.entries.firstOrNull { it.name == target }
                    if (page == null) SettingsHub(state, { open = it.name }, ::back)
                    else SettingsPageScaffold(page.icon, stringResource(page.title), page.description(state), page.accent, page.shape, ::back,
                        sharedKey = "settings:${page.name}", modifier = Modifier.testTag("settings-page-${page.name}")) {
                        when (page) {
                            SettingsPage.ACCOUNTS -> accountsPage(state, model, onSortAccounts)
                            SettingsPage.BACKGROUND -> backgroundPage(model)
                            SettingsPage.NOTIFICATIONS -> notificationsPage(state, model)
                            SettingsPage.APPEARANCE -> appearancePage(state, model)
                            SettingsPage.NAVIGATION -> navigationPage(state, model)
                            SettingsPage.WIDGETS -> widgetsPage()
                            SettingsPage.ABOUT -> aboutPage()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHub(state: UiState, open: (SettingsPage) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(query.isNotEmpty()) { query = "" }
    val descriptions = SettingsPage.entries.associateWith { it.description(state) }
    val titles = SettingsPage.entries.associateWith { stringResource(it.title) }
    @Composable fun Slot(page: SettingsPage, modifier: Modifier, compact: Boolean = false) =
        OrionBento(titles.getValue(page), descriptions.getValue(page), page.icon, page.accent, page.shape, { open(page) }, modifier, compact, sharedKey = "settings:${page.name}")
    SettingsPageScaffold(Icons.Rounded.Settings, stringResource(R.string.settings), "", MaterialTheme.colorScheme.primary, MaterialShapes.Cookie9Sided, onBack,
        modifier = Modifier.testTag("settings-hub")) {
        settingsItem("search", 0) {
            TextField(query, { query = it }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, null) } },
                placeholder = { Text(stringResource(R.string.settings_search)) }, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.panelRaised, unfocusedContainerColor = MaterialTheme.colorScheme.panelRaised))
        }
        if (query.isNotBlank()) {
            val results = SettingsPage.entries.filter { (titles.getValue(it) + " " + descriptions.getValue(it)).contains(query.trim(), true) }
            settingsItem("results") {
                results.chunked(2).forEach { pair ->
                    Row(Modifier.height(132.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { Slot(it, Modifier.weight(1f).fillMaxHeight(), compact = true) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (results.isEmpty()) Text(stringResource(R.string.settings_none_found), Modifier.fillMaxWidth().padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            settingsItem("data", 1) {
                val singleColumn = LocalDensity.current.fontScale > 1.3f
                SettingsSectionLabel(stringResource(R.string.settings_group_data))
                if (singleColumn) listOf(SettingsPage.ACCOUNTS, SettingsPage.BACKGROUND, SettingsPage.NOTIFICATIONS).forEach { Slot(it, Modifier.fillMaxWidth().height(156.dp)) }
                else {
                    Slot(SettingsPage.ACCOUNTS, Modifier.fillMaxWidth().height(156.dp))
                    Row(Modifier.height(132.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Slot(SettingsPage.BACKGROUND, Modifier.weight(1f).fillMaxHeight(), compact = true)
                        Slot(SettingsPage.NOTIFICATIONS, Modifier.weight(1f).fillMaxHeight(), compact = true)
                    }
                }
            }
            settingsItem("app", 2) {
                val singleColumn = LocalDensity.current.fontScale > 1.3f
                SettingsSectionLabel(stringResource(R.string.settings_group_app))
                if (singleColumn) listOf(SettingsPage.APPEARANCE, SettingsPage.NAVIGATION, SettingsPage.WIDGETS, SettingsPage.ABOUT).forEach { Slot(it, Modifier.fillMaxWidth().height(156.dp)) }
                else {
                    Slot(SettingsPage.APPEARANCE, Modifier.fillMaxWidth().height(156.dp))
                    Row(Modifier.height(268.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Slot(SettingsPage.NAVIGATION, Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Slot(SettingsPage.WIDGETS, Modifier.fillMaxWidth().weight(1f), compact = true)
                            Slot(SettingsPage.ABOUT, Modifier.fillMaxWidth().weight(1f), compact = true)
                        }
                    }
                }
            }
        }
    }
}

// ---- Accounts ------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.accountsPage(state: UiState, model: UsageViewModel, sort: () -> Unit) {
    settingsItem("accounts") {
        var expanded by rememberSaveable { mutableStateOf<String?>(null) }
        var confirm by remember { mutableStateOf<String?>(null) }
        var renaming by remember { mutableStateOf<String?>(null) }
        if (state.accounts.isNotEmpty()) SettingsGroup {
            state.accounts.forEach { entry ->
                val subtitle = listOfNotNull(Providers.label(entry.provider), entry.plan, entry.account.detail?.takeIf { it != entry.title }).joinToString(" · ")
                SettingsItem(entry.title, { expanded = if (expanded == entry.id) null else entry.id }, subtitle = subtitle,
                    leading = { ProviderBadge(entry.provider) }) {
                    if (entry.error != null) Icon(Icons.Rounded.ErrorOutline, stringResource(R.string.accounts_state_error), tint = MaterialTheme.colorScheme.error)
                    else Icon(if (expanded == entry.id) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(expanded == entry.id || entry.error != null, enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                    exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut()) {
                    SettingsBlock {
                        entry.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (entry.error != null) FilledTonalButton({ model.refresh(entry.id) }, enabled = !entry.refreshing) { Text(stringResource(R.string.accounts_reload)) }
                            FilledTonalButton({ model.connect(entry.provider, entry.id) }, enabled = !state.login(entry.provider).busy) { Text(stringResource(R.string.accounts_sign_in_again)) }
                            OutlinedButton({ renaming = entry.id }) { Text(stringResource(R.string.accounts_rename)) }
                            TextButton({ confirm = entry.id }) { Text(stringResource(R.string.accounts_disconnect), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            if (state.accounts.size > 1) SettingsItem(stringResource(R.string.accounts_order), sort, icon = Icons.Rounded.SwapVert)
        }
        SettingsSectionLabel(stringResource(R.string.accounts_add))
        SettingsGroup {
            Providers.all.forEach { provider ->
                val busy = state.login(provider).busy
                SettingsItem(if (state.accountsOf(provider).isEmpty()) stringResource(R.string.accounts_connect, Providers.label(provider))
                    else stringResource(R.string.accounts_add_another, Providers.label(provider)), { model.connect(provider) }, enabled = !busy,
                    subtitle = Providers.experimental(provider)?.let { stringResource(it) }, leading = { ProviderBadge(provider) }) {
                    if (busy) CircularWavyProgressIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.Add, null)
                }
            }
        }
        confirm?.let { id ->
            val entry = state.account(id)
            if (entry == null) confirm = null
            else AlertDialog(onDismissRequest = { confirm = null }, title = { Text(stringResource(R.string.accounts_disconnect_title, entry.title)) },
                text = { Text(stringResource(R.string.accounts_disconnect_text)) },
                confirmButton = { TextButton({ model.removeAccount(id); confirm = null }) { Text(stringResource(R.string.accounts_disconnect), color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } })
        }
        renaming?.let { id ->
            val entry = state.account(id)
            if (entry == null) renaming = null else RenameDialog(entry, { renaming = null }) { model.renameAccount(id, it); renaming = null }
        }
    }
}

/** Brand mark on a tonal shape, as the leading element of account rows. */
@Composable
internal fun ProviderBadge(provider: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val (bg, _) = orionTonal(providerAccent(provider))
    Box(Modifier.size(size).background(bg, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
        BrandIcon(provider, Modifier.size(size * .55f))
    }
}

@Composable
internal fun RenameDialog(entry: AccountState, dismiss: () -> Unit, save: (String?) -> Unit) {
    var text by remember(entry.id) { mutableStateOf(entry.account.label.orEmpty()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.accounts_rename_title)) },
        text = {
            OutlinedTextField(text, { text = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.accounts_name)) },
                placeholder = { Text(entry.account.detail ?: Providers.label(entry.provider)) }, supportingText = { Text(stringResource(R.string.accounts_name_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton({ save(text.ifBlank { null }) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.cancel)) } })
}

// ---- Background ----------------------------------------------------------------------------------------

@Composable
internal fun intervalLabel(minutes: Long): String =
    if (minutes < 60) stringResource(R.string.interval_minutes, minutes.toInt()) else stringResource(R.string.interval_hours, (minutes / 60).toInt())

private fun androidx.compose.foundation.lazy.LazyListScope.backgroundPage(model: UsageViewModel) {
    settingsItem("background") {
        val context = LocalContext.current
        val res = LocalResources.current
        var revision by remember { mutableIntStateOf(0) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
        val prefs = remember { context.getSharedPreferences("alerts", 0) }
        val enabled = remember(revision) { AccountRefresh.enabled(context) }
        val minutes = remember(revision) { AccountRefresh.minutes(context) }
        val log = remember(revision) { BackgroundLog.read(context) }
        val now = Instant.now().epochSecond
        val exact = remember(revision) { ResetAlarms.canScheduleExact(context) }
        val unrestricted = remember(revision) { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName) }
        val nextEnd = remember(revision) { prefs.getLong("tick.target", 0).takeIf { it > now } }
        SettingsGroup {
            SwitchSettingsItem(stringResource(R.string.background_refresh), enabled, {
                prefs.edit().putBoolean("daily", it).apply(); revision++; model.syncBackground()
            }, subtitle = stringResource(R.string.background_refresh_desc), icon = Icons.Rounded.Sync)
            AnimatedVisibility(enabled) {
                SettingsBlock {
                    Text(stringResource(R.string.background_interval), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ConnectedChoice(AccountRefresh.choices, minutes, { AccountRefresh.setMinutes(context, it); revision++; model.syncBackground() }) { intervalLabel(it) }
                }
            }
        }
        SettingsSectionLabel(stringResource(R.string.background_status))
        SettingsGroup {
            SettingsBlock {
                val last = log.firstOrNull { it.trigger != SyncTrigger.APP }
                StatusLine(stringResource(R.string.background_last), last?.let { Fmt.ago(res, now - it.at) } ?: stringResource(R.string.background_none))
                StatusLine(stringResource(R.string.background_next_end), nextEnd?.let {
                    Fmt.clock(res, it - if (exact) ResetTicks.LEAD_SECONDS else ResetTicks.INEXACT_LEAD_SECONDS, now)
                } ?: stringResource(R.string.background_next_none))
            }
            SettingsItem(stringResource(R.string.exact_alarms), if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ({ openExactAlarms(context) }) else null,
                subtitle = stringResource(if (exact) R.string.exact_alarms_on else R.string.exact_alarms_off), icon = Icons.Rounded.Alarm) {
                if (!exact) Icon(Icons.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsItem(stringResource(R.string.battery), if (!unrestricted) ({ openBatterySettings(context) }) else null,
                subtitle = stringResource(if (unrestricted) R.string.battery_unrestricted else R.string.battery_optimized), icon = Icons.Rounded.BatterySaver) {
                if (!unrestricted) Icon(Icons.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsSectionLabel(stringResource(R.string.log))
        var all by rememberSaveable { mutableStateOf(false) }
        val accountNames = remember { com.madebycli.aiusage.data.AccountRegistry(context).list().associate { it.id to (it.label ?: it.detail ?: Providers.label(it.provider)) } }
        SettingsGroup {
            if (log.isEmpty()) SettingsBlock { Text(stringResource(R.string.log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            (if (all) log else log.take(12)).forEach { entry -> LogRow(entry, accountNames, now) }
            if (log.size > 12 && !all) SettingsItem(stringResource(R.string.log_show_all, log.size), { all = true }, icon = Icons.Rounded.ExpandMore)
        }
    }
}

@Composable
private fun LogRow(entry: BackgroundLog.Entry, names: Map<String, String>, now: Long) {
    val res = LocalResources.current
    val cs = MaterialTheme.colorScheme
    val trigger = stringResource(when (entry.trigger) {
        SyncTrigger.PERIODIC -> R.string.trigger_periodic; SyncTrigger.CYCLE_END -> R.string.trigger_cycle_end
        SyncTrigger.AFTER_RESET -> R.string.trigger_after_reset; SyncTrigger.APP -> R.string.trigger_app
        SyncTrigger.WIDGET -> R.string.trigger_widget; SyncTrigger.BOOT -> R.string.trigger_boot; SyncTrigger.RETRY -> R.string.trigger_retry
    })
    Surface(color = cs.panel, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).background(if (entry.ok) OrionAccents.Green else cs.error, androidx.compose.foundation.shape.CircleShape))
            Column(Modifier.weight(1f)) {
                Text("${Fmt.clock(res, entry.at, now)}  ·  $trigger", style = MaterialTheme.typography.titleSmall)
                entry.results.forEach { result ->
                    Text("${names[result.account] ?: result.account.take(6)}: " +
                        if (result.ok) stringResource(R.string.log_ok) else stringResource(R.string.log_failed, result.reason ?: "?"),
                        style = MaterialTheme.typography.bodySmall, color = if (result.ok) cs.onSurfaceVariant else cs.error)
                }
            }
        }
    }
}

private fun openExactAlarms(context: Context) {
    try { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
    catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
}

private fun openBatterySettings(context: Context) {
    try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
}

// ---- Notifications -------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.notificationsPage(state: UiState, model: UsageViewModel) {
    settingsItem("notifications") {
        val context = LocalContext.current
        val res = LocalResources.current
        val prefs = remember { ResetAlerts.prefs(context) }
        var revision by remember { mutableIntStateOf(0) }
        val values = remember(revision) { prefs.all }
        fun checked(key: String, default: Boolean) = values[key] as? Boolean ?: default
        fun update(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); revision++; model.rescheduleAlerts() }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> update("enabled", granted) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++; model.rescheduleAlerts() }
        fun systemSettings() { ResetAlerts.channels(context); context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        fun request() {
            val granted = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
            if (!granted && (!prefs.getBoolean("permissionAsked", false) || activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)) {
                prefs.edit().putBoolean("permissionAsked", true).apply(); ResetAlerts.channels(context); permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (ResetAlerts.permitted(context)) update("enabled", true) else { update("enabled", true); systemSettings() }
        }
        val allowed = ResetAlerts.permitted(context)
        val now = Instant.now().epochSecond
        SettingsGroup {
            SwitchSettingsItem(stringResource(R.string.alerts_enabled), checked("enabled", false), { on -> if (on && !allowed) request() else update("enabled", on) },
                subtitle = stringResource(R.string.alerts_enabled_desc), icon = Icons.Rounded.NotificationsActive)
            SettingsItem(stringResource(R.string.alerts_permission), if (allowed) null else ({ request() }),
                subtitle = stringResource(if (allowed) R.string.alerts_permission_granted else R.string.alerts_permission_missing), icon = Icons.Rounded.VerifiedUser) {
                if (!allowed) FilledTonalButton({ request() }) { Text(stringResource(R.string.alerts_permission_allow)) }
            }
            SwitchSettingsItem(stringResource(R.string.alerts_used_only), checked("usedOnly", true), { update("usedOnly", it) },
                subtitle = stringResource(R.string.alerts_used_only_desc), icon = Icons.Rounded.FilterAlt)
            SwitchSettingsItem(stringResource(R.string.alerts_early), checked("spontaneous", true), { update("spontaneous", it) },
                subtitle = stringResource(R.string.alerts_early_desc), icon = Icons.Rounded.Bolt)
            SettingsBlock {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ ResetNotifier.sendTest(context) }, enabled = allowed) { Text(stringResource(R.string.alerts_test)) }
                    OutlinedButton({ systemSettings() }) { Text(stringResource(R.string.alerts_android_settings)) }
                }
            }
        }
        state.connectedProviders.forEach { provider ->
            SettingsSectionLabel(Providers.label(provider))
            SettingsGroup {
                ResetAlerts.rules.filter { it.provider == provider }.forEach { rule ->
                    SwitchSettingsItem(windowTitle(rule.seconds), checked("rule.${rule.key}", true), { update("rule.${rule.key}", it) },
                        icon = if (rule.seconds == com.madebycli.aiusage.data.FIVE_HOURS) Icons.Rounded.Timelapse else Icons.Rounded.DateRange)
                    SettingsBlock {
                        val lead = values["lead.${rule.key}"] as? Int ?: 0
                        ConnectedChoice(listOf(0, 5, 15, 30, 60), lead, { prefs.edit().putInt("lead.${rule.key}", it).apply(); revision++; model.rescheduleAlerts() }) {
                            if (it == 0) stringResource(R.string.alerts_at_reset) else stringResource(R.string.interval_minutes, it)
                        }
                        state.accountsOf(provider).forEach { entry ->
                            val next = ResetAlerts.nextReminder(context, rule.slot(entry.account))
                            StatusLine(entry.title, next?.let { "${Fmt.clock(res, it, now)} · ${Fmt.remaining(res, it - now)}" } ?: stringResource(R.string.alerts_none_planned))
                        }
                        TextButton({
                            ResetAlerts.channels(context)
                            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, rule.channel))
                        }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.alerts_sound)) }
                    }
                }
            }
        }
    }
}

/** "5 hours", "Weekly" or the plain length of another window. */
@Composable
internal fun windowTitle(seconds: Long): String = when {
    seconds == com.madebycli.aiusage.data.FIVE_HOURS -> stringResource(R.string.window_five)
    seconds == com.madebycli.aiusage.data.ONE_WEEK -> stringResource(R.string.window_week)
    seconds % 86_400 == 0L -> pluralStringResource(R.plurals.window_days, (seconds / 86_400).toInt(), (seconds / 86_400).toInt())
    else -> pluralStringResource(R.plurals.window_hours, (seconds / 3_600).toInt().coerceAtLeast(1), (seconds / 3_600).toInt().coerceAtLeast(1))
}

// ---- Appearance ----------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.appearancePage(state: UiState, model: UsageViewModel) {
    settingsItem("appearance") {
        SettingsGroup {
            SettingsBlock {
                Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to Icons.Rounded.BrightnessAuto, ThemeMode.LIGHT to Icons.Rounded.LightMode, ThemeMode.DARK to Icons.Rounded.DarkMode).forEach { (mode, icon) ->
                        SettingsChoiceTile(state.theme == mode, { model.setTheme(mode) }, Modifier.weight(1f)) {
                            Column(Modifier.padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(icon, null)
                                Text(stringResource(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark }),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                SwitchSettingsItem(stringResource(R.string.dynamic_colors), state.dynamicColor, model::setDynamic, subtitle = stringResource(R.string.dynamic_colors_desc), icon = Icons.Rounded.Wallpaper)
            SwitchSettingsItem(stringResource(R.string.black_background), state.amoled, model::setAmoled, subtitle = stringResource(R.string.black_background_desc), icon = Icons.Rounded.Contrast)
        }
        SettingsSectionLabel(stringResource(R.string.home_style))
        SettingsGroup {
            SettingsBlock {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeStyle.entries.forEach { style ->
                        SettingsChoiceTile(state.homeStyle == style, { model.setHomeStyle(style) }, Modifier.weight(1f)) {
                            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                HomeStylePreview(style)
                                Text(stringResource(if (style == HomeStyle.COMPACT) R.string.home_style_compact else R.string.home_style_playful), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SettingsSectionLabel(stringResource(R.string.language))
            val context = LocalContext.current
            val manager = remember { context.getSystemService(LocaleManager::class.java) }
            var current by remember { mutableStateOf(manager.applicationLocales.toLanguageTags()) }
            SettingsGroup {
                listOf("" to stringResource(R.string.language_system), "en" to "English", "de" to "Deutsch").forEach { (tag, name) ->
                    RadioSettingsItem(name, current == tag || (tag.isNotEmpty() && current.startsWith(tag)), {
                        manager.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag); current = tag
                    }, icon = if (tag.isEmpty()) Icons.Rounded.Translate else null)
                }
            }
        }
    }
}

/** A small sketch of a home layout for the picker. */
@Composable
private fun HomeStylePreview(style: HomeStyle) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(84.dp).background(cs.background, RoundedCornerShape(14.dp)).padding(8.dp)) {
        if (style == HomeStyle.COMPACT) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) {
                Column(Modifier.fillMaxWidth().background(cs.panel, RoundedCornerShape(10.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth(.4f).height(5.dp).background(cs.onSurfaceVariant.copy(.5f), RoundedCornerShape(3.dp)))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f).height(4.dp).background(cs.primary, RoundedCornerShape(2.dp)))
                        Box(Modifier.weight(1f).height(4.dp).background(cs.tertiary, RoundedCornerShape(2.dp)))
                    }
                }
            }
        } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            listOf(MaterialShapes.Cookie9Sided to cs.primary, MaterialShapes.Flower to cs.tertiary).forEach { (shape, color) ->
                Box(Modifier.size(48.dp).background(color.copy(.25f), shape.toShape()), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.size(48.dp, 24.dp).background(color, shape.toShape()))
                }
            }
        }
    }
}

// ---- Navigation ----------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.navigationPage(state: UiState, model: UsageViewModel) {
    settingsItem("navigation") {
        val nav = state.nav
        fun move(tab: AppTab, by: Int) {
            val order = nav.order.toMutableList(); val from = order.indexOf(tab); val to = (from + by).coerceIn(0, order.lastIndex)
            if (from != to) { order.add(to, order.removeAt(from)); model.setTabOrder(order) }
        }
        BarPreview(nav)
        SettingsSectionLabel(stringResource(R.string.nav_bar))
        SettingsGroup {
            nav.order.forEach { tab ->
                val visible = tab in nav.visible
                val name = stringResource(tab.label)
                SettingsItem(name, null, subtitle = stringResource(if (tab == AppTab.USAGE) R.string.nav_always else if (visible) R.string.nav_shown else R.string.nav_hidden),
                    icon = tab.icon, enabled = visible) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ move(tab, -1) }, enabled = nav.order.indexOf(tab) > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.nav_move_up, name)) }
                        IconButton({ move(tab, 1) }, enabled = nav.order.indexOf(tab) < nav.order.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.nav_move_down, name)) }
                        Switch(visible, { model.setTabVisible(tab, it) }, enabled = tab != AppTab.USAGE, colors = appSwitchColors())
                    }
                }
            }
        }
        SettingsSectionLabel(stringResource(R.string.nav_start))
        SettingsGroup {
            StartScreen.entries.forEach { option ->
                val available = option.tab == null || option.tab in nav.visible
                RadioSettingsItem(option.tab?.let { stringResource(it.label) } ?: stringResource(R.string.nav_start_last), nav.start == option,
                    { model.setStartScreen(option) }, enabled = available, icon = option.tab?.icon ?: Icons.Rounded.History)
            }
        }
        SettingsSectionLabel(stringResource(R.string.nav_stats_defaults))
        SettingsGroup {
            SettingsBlock {
                ConnectedChoice(com.madebycli.aiusage.data.StatsMetric.entries, nav.statsMetric, model::setStatsMetric) { stringResource(it.labelRes) }
                ConnectedChoice(StatsRange.entries, nav.statsRange, model::setStatsRange) { stringResource(it.label) }
            }
        }
    }
}

@Composable
private fun BarPreview(nav: NavSettings) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.panel) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).animateContentSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
            nav.visible.forEach { tab ->
                val selected = tab == nav.initial
                val pill by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "pill")
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.background(pill, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 4.dp)) { Icon(tab.icon, null) }
                    Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

// ---- Widgets -------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.widgetsPage() {
    settingsItem("widgets") {
        val context = LocalContext.current
        val manager = remember { AppWidgetManager.getInstance(context) }
        var ids by remember { mutableStateOf(manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java)).toList()) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ids = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java)).toList() }
        SettingsGroup {
            if (ids.isEmpty()) SettingsBlock { Text(stringResource(R.string.widgets_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            ids.forEach { id ->
                val config = WidgetConfigStore.read(context, id)
                NavigationSettingsItem(stringResource(R.string.widgets_item, id), "${stringResource(config.style.label)} · ${stringResource(config.arrangement.label)}", Icons.Rounded.Widgets) {
                    context.startActivity(Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                }
            }
        }
    }
}

// ---- About ---------------------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.aboutPage() {
    settingsItem("about") {
        val uri = LocalUriHandler.current
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                OrionSpinner(MaterialShapes.SoftBurst, MaterialTheme.colorScheme.primary.copy(.20f), 136.dp, periodMs = 30000)
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher), null, Modifier.padding(16.dp).size(52.dp))
                }
            }
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.about_unofficial), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SettingsSectionLabel(stringResource(R.string.about_source))
        SettingsGroup {
            listOf(
                Triple("AI Usage", "madebycli · GPL-3.0", "https://github.com/madebycli/AiUsage-Android"),
                Triple("Orion Store", "RookieEnough · GPL-3.0", "https://github.com/RookieEnough/Orion-Store/tree/e561e91e9409d9d9b437ab7acf919cd115769d7e"),
                Triple("Iris", "madebycli · GPL-3.0", "https://github.com/madebycli/iris-android"),
                Triple("AniHyou", "axiel7 · GPL-3.0", "https://github.com/axiel7/AniHyou-android"),
                Triple("OpenUsage", "Robin Ebers · MIT", "https://github.com/robinebers/openusage"),
                Triple("CodexBar", "Peter Steinberger · MIT", "https://github.com/steipete/CodexBar"),
                Triple("Google Sans Flex", "OFL-1.1", "https://fonts.google.com/specimen/Google+Sans+Flex")
            ).forEach { (name, detail, link) ->
                SettingsItem(name, { uri.openUri(link) }, subtitle = detail) { Icon(Icons.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ---- Shared pickers ------------------------------------------------------------------------------------

/** Material 3 Expressive connected button group: the selected one morphs round. */
@Composable
internal fun <T> ConnectedChoice(options: List<T>, selected: T, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(option == selected, { onSelect(option) }, Modifier.weight(1f).semantics { role = androidx.compose.ui.semantics.Role.RadioButton },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
