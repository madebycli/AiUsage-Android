@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.madebycli.aiusage.R
import com.madebycli.aiusage.ThemeMode
import com.madebycli.aiusage.data.StatsMetric
import com.madebycli.aiusage.data.appJson
import com.madebycli.aiusage.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings of one widget. The preview at the top is drawn by the real renderer with the real accounts. */
class WidgetConfigActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out must cancel the widget instead of placing an unconfigured one.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        val appearance = getSharedPreferences("appearance", 0)
        val initial = WidgetUpdater.config(this, widgetId)
        val (width, height) = WidgetUpdater.sizeDp(this, AppWidgetManager.getInstance(this), widgetId)
        setContent {
            val dark = when (runCatching { ThemeMode.valueOf(appearance.getString("theme", "SYSTEM").orEmpty()) }.getOrDefault(ThemeMode.SYSTEM)) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false
            }
            AniHyouTheme(darkTheme = dark, dynamicColor = appearance.getBoolean("dynamic", false), blackColors = appearance.getBoolean("amoled", false)) {
                ConfigScreen(initial, width, height, onCancel = { finish() }, onSave = { config ->
                    WidgetConfigStore.write(this, widgetId, config)
                    WidgetUpdater.update(this, intArrayOf(widgetId))
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                })
            }
        }
    }
}

/** Sizes to try the layout at, in dp, besides the widget's own. */
private val previewSizes = listOf(R.string.wc_small to (110 to 110), R.string.wc_wide to (250 to 120), R.string.wc_large to (250 to 250), R.string.wc_narrow to (250 to 62))

private val configSaver = Saver<WidgetConfig, String>(
    save = { appJson.encodeToString(WidgetConfig.serializer(), it) },
    restore = { appJson.decodeFromString(WidgetConfig.serializer(), it) })

@Composable
internal fun ConfigScreen(initial: WidgetConfig, widgetWidth: Int, widgetHeight: Int, onCancel: () -> Unit, onSave: (WidgetConfig) -> Unit,
    given: List<WidgetAccount>? = null, now: Long? = null, scroll: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()) {
    val context = LocalContext.current
    var config by rememberSaveable(stateSaver = configSaver) { mutableStateOf(initial) }
    val accounts by produceState(given ?: emptyList()) { if (given == null) value = withContext(Dispatchers.IO) { WidgetData.load(context) } }
    var sizeIndex by rememberSaveable { mutableIntStateOf(-1) }
    val (previewW, previewH) = if (sizeIndex < 0) widgetWidth to widgetHeight else previewSizes[sizeIndex].second
    val kind = config.kind
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSettingsAccent provides kind.accent) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), state = scroll,
                contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 112.dp)) {
                item(key = "hero") {
                    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    OrionPageHero(kind.icon, stringResource(kind.label), stringResource(R.string.wc_title), kind.accent, kind.shape, topInset = statusBar) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
                        }
                    }
                }
                settingsItem("preview", 0) {
                    WallpaperBox(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), kind.accent) {
                        BoxWithConstraints(Modifier.padding(vertical = 28.dp, horizontal = 20.dp), contentAlignment = Alignment.Center) {
                            val fit = minOf(maxWidth.value, previewW.toFloat() * 1.25f).dp
                            WidgetPicture(config, accounts, previewW, previewH, displayWidth = minOf(fit, maxWidth), now = now)
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(-1) + previewSizes.indices).forEach { i ->
                            FilterChip(sizeIndex == i, { sizeIndex = i }, label = { Text(stringResource(if (i < 0) R.string.wc_your_widget else previewSizes[i].first)) },
                                leadingIcon = if (sizeIndex == i) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null)
                        }
                    }
                    if (kind == WidgetKind.USAGE && sizeIndex < 0 && WidgetRenderer.effectiveStyle(config, widgetWidth, widgetHeight) != config.style)
                        Text(stringResource(R.string.wc_taller), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp))
                }
                when (kind) {
                    WidgetKind.USAGE -> usageSettings(config, accounts, now) { config = it }
                    WidgetKind.STATS -> statsSettings(config) { config = it }
                    WidgetKind.PLAN -> {}
                    WidgetKind.RESETS -> resetSettings(config) { config = it }
                }
                settingsItem("accounts", 3) {
                    SettingsSectionLabel(stringResource(R.string.wc_accounts))
                    if (accounts.isEmpty()) SettingsGroup { SettingsBlock { Text(stringResource(R.string.wc_no_accounts), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    else AccountPicker(accounts, config) { config = it }
                }
                settingsItem("look", 4) {
                    SettingsSectionLabel(stringResource(R.string.wc_colors_shape))
                    SettingsGroup {
                        SettingsBlock {
                            Text(stringResource(R.string.wc_background), style = MaterialTheme.typography.titleMedium)
                            ConnectedChoice(WidgetTheme.entries, config.theme, { config = config.copy(theme = it) }) { stringResource(it.label) }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            SwitchSettingsItem(stringResource(R.string.dynamic_colors), config.dynamic, { config = config.copy(dynamic = it) },
                                subtitle = stringResource(R.string.dynamic_colors_desc), icon = Icons.Rounded.Palette)
                        SettingsBlock {
                            SliderLine(stringResource(R.string.wc_opacity), "${config.opacity} %", config.opacity.toFloat(), 0f..100f, 19) { config = config.copy(opacity = kotlin.math.round(it).toInt()) }
                            SliderLine(stringResource(R.string.wc_corners), "${config.corner} dp", config.corner.toFloat(), 0f..40f, 9) { config = config.copy(corner = kotlin.math.round(it).toInt()) }
                            SliderLine(stringResource(R.string.wc_text_size), "${config.textScale} %", config.textScale.toFloat(), 80f..140f, 11) { config = config.copy(textScale = kotlin.math.round(it).toInt()) }
                        }
                    }
                }
                settingsItem("elements", 5) {
                    SettingsSectionLabel(stringResource(R.string.wc_elements))
                    SettingsGroup {
                        SwitchSettingsItem(stringResource(R.string.wc_refresh_button), config.showRefresh, { config = config.copy(showRefresh = it) },
                            subtitle = stringResource(R.string.wc_refresh_button_desc), icon = Icons.Rounded.Refresh)
                        SwitchSettingsItem(stringResource(R.string.wc_updated), config.showUpdated, { config = config.copy(showUpdated = it) },
                            subtitle = stringResource(R.string.wc_updated_desc), icon = Icons.Rounded.Schedule)
                        if (kind == WidgetKind.USAGE) SwitchSettingsItem(stringResource(R.string.wc_plan), config.showPlan, { config = config.copy(showPlan = it) },
                            subtitle = stringResource(R.string.wc_plan_desc), icon = Icons.Rounded.WorkspacePremium)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { onSave(config) }, icon = { Icon(Icons.Rounded.Check, null) }, text = { Text(stringResource(R.string.wc_save)) },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.usageSettings(config: WidgetConfig, accounts: List<WidgetAccount>, now: Long?, update: (WidgetConfig) -> Unit) {
    settingsItem("style", 1) {
        SettingsSectionLabel(stringResource(R.string.wc_style))
        // Each choice shows the real picture in that style, so the choice is made by looking, not by reading.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sample = accounts.take(1)
            WidgetStyle.entries.forEach { style ->
                SettingsChoiceTile(config.style == style, { update(config.copy(style = style)) }, Modifier.width(140.dp)) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WidgetPicture(config.copy(style = style, accounts = sample.map { it.id }, showRefresh = false, showUpdated = false, opacity = 100),
                            sample, if (style == WidgetStyle.COMPACT) 200 else 170, if (style == WidgetStyle.COMPACT) 62 else 130, displayWidth = 118.dp, now = now)
                        Text(stringResource(style.label), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    settingsItem("layout", 2) {
        SettingsSectionLabel(stringResource(R.string.wc_display))
        SettingsGroup {
            SettingsBlock {
                Text(stringResource(R.string.wc_windows), style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(WidgetWindows.entries, config.windows, { update(config.copy(windows = it)) }) { stringResource(it.label) }
            }
            SettingsBlock {
                Text(stringResource(R.string.wc_reset), style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(ResetFormat.entries, config.reset, { update(config.copy(reset = it)) }) { stringResource(it.label) }
            }
            SettingsBlock {
                Text(stringResource(R.string.wc_arrange_accounts), style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(WidgetArrangement.entries, config.arrangement, { update(config.copy(arrangement = it)) }) { stringResource(it.label) }
            }
            SettingsBlock {
                Text(stringResource(R.string.wc_bar_color), style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(WidgetColors.entries, config.colors, { update(config.copy(colors = it)) }) { stringResource(it.label) }
                AnimatedVisibility(config.colors == WidgetColors.TRAFFIC) {
                    Text(stringResource(R.string.wc_traffic_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.statsSettings(config: WidgetConfig, update: (WidgetConfig) -> Unit) {
    settingsItem("stats", 1) {
        SettingsSectionLabel(stringResource(R.string.wc_display))
        SettingsGroup {
            SettingsBlock {
                Text(stringResource(R.string.wc_limit), style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(StatsMetric.entries, config.metric, { update(config.copy(metric = it)) }) { stringResource(it.labelRes) }
            }
            SettingsBlock {
                Text(stringResource(R.string.wc_span), style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(StatsSpan.entries, config.span, { update(config.copy(span = it)) }) { stringResource(it.label) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.resetSettings(config: WidgetConfig, update: (WidgetConfig) -> Unit) {
    settingsItem("resets", 1) {
        SettingsSectionLabel(stringResource(R.string.wc_display))
        SettingsGroup {
            SettingsBlock {
                Text(stringResource(R.string.wc_windows), style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(WidgetWindows.entries, config.windows, { update(config.copy(windows = it)) }) { stringResource(it.label) }
            }
            SettingsBlock {
                Text(stringResource(R.string.wc_reset), style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(ResetFormat.entries, config.reset, { update(config.copy(reset = it)) }) { stringResource(it.label) }
            }
        }
    }
}

@Composable
private fun SliderLine(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, change: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Slider(current, change, valueRange = range, steps = steps)
    }
}

/** All accounts, or a chosen few in a chosen order. */
@Composable
private fun AccountPicker(accounts: List<WidgetAccount>, config: WidgetConfig, update: (WidgetConfig) -> Unit) {
    val all = config.accounts.isEmpty()
    SettingsGroup {
        SwitchSettingsItem(stringResource(R.string.wc_all_accounts), all, { auto -> update(config.copy(accounts = if (auto) emptyList() else accounts.map { it.account.id })) },
            subtitle = stringResource(R.string.wc_all_accounts_desc), icon = Icons.Rounded.SelectAll)
        if (all) return@SettingsGroup
        val chosen = config.accounts.mapNotNull { id -> accounts.firstOrNull { it.account.id == id } }
        val others = accounts.filter { entry -> chosen.none { it.account.id == entry.account.id } }
        fun move(id: String, by: Int) {
            val order = config.accounts.toMutableList()
            val from = order.indexOf(id)
            val to = (from + by).coerceIn(0, order.lastIndex)
            if (from != to) { order.add(to, order.removeAt(from)); update(config.copy(accounts = order)) }
        }
        (chosen.map { it to true } + others.map { it to false }).forEach { (entry, selected) ->
            SettingsItem(entry.title, {
                val order = config.accounts.toMutableList()
                if (!selected) order += entry.account.id else if (order.size > 1) order -= entry.account.id
                update(config.copy(accounts = order))
            }, subtitle = listOfNotNull(com.madebycli.aiusage.Providers.short(entry.provider), entry.plan).joinToString(" · "),
                leading = { ProviderBadge(entry.provider, 40.dp) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        IconButton(onClick = { move(entry.account.id, -1) }, enabled = config.accounts.indexOf(entry.account.id) > 0) {
                            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.usage_up))
                        }
                        IconButton(onClick = { move(entry.account.id, 1) }, enabled = config.accounts.indexOf(entry.account.id) < config.accounts.lastIndex) {
                            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.usage_down))
                        }
                    }
                    Checkbox(selected, null)
                }
            }
        }
    }
}
