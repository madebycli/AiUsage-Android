@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.madebycli.aiusage.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.axiel7.anihyou.core.ui.theme.AniHyouTheme
import com.madebycli.aiusage.ThemeMode
import com.madebycli.aiusage.ui.SegmentedTabGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings of one widget: accounts and their order, layout, colours. The preview is drawn by the real renderer. */
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
        val initial = WidgetConfigStore.read(this, widgetId)
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

private val previewSizes = listOf(com.madebycli.aiusage.R.string.wc_wide to (250 to 120), com.madebycli.aiusage.R.string.wc_tall to (160 to 230), com.madebycli.aiusage.R.string.wc_large to (250 to 260), com.madebycli.aiusage.R.string.wc_narrow to (250 to 62))

@Composable
internal fun ConfigScreen(initial: WidgetConfig, widgetWidth: Int, widgetHeight: Int, onCancel: () -> Unit, onSave: (WidgetConfig) -> Unit) {
    val context = LocalContext.current
    var config by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<WidgetConfig, String>(
        save = { com.madebycli.aiusage.data.appJson.encodeToString(WidgetConfig.serializer(), it) },
        restore = { com.madebycli.aiusage.data.appJson.decodeFromString(WidgetConfig.serializer(), it) })) { mutableStateOf(initial) }
    val accounts by produceState(emptyList<WidgetAccount>()) { value = withContext(Dispatchers.IO) { WidgetData.load(context) } }
    var sizeIndex by remember { mutableIntStateOf(-1) }
    val (previewW, previewH) = if (sizeIndex < 0) widgetWidth to widgetHeight else previewSizes[sizeIndex].second
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(config, accounts, previewW, previewH) {
        bitmap = withContext(Dispatchers.Default) { runCatching { WidgetRenderer.render(context, config, accounts, previewW, previewH) }.getOrNull() }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_title)) },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.cancel)) } },
                actions = { FilledTonalButton(onClick = { onSave(config) }) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.done)) }; Spacer(Modifier.width(12.dp)) })
        }
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_preset))
            SegmentedTabGroup(WidgetPreset.entries, config.preset, { config = it.applyTo(config) }, { androidx.compose.ui.res.stringResource(it.label) })
            Preview(bitmap, previewW, previewH, sizeIndex) { sizeIndex = it }
            if (WidgetRenderer.effectiveStyle(config, widgetWidth, widgetHeight) != config.style)
                Text(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_taller), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Section(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_accounts)) {
                if (accounts.isEmpty()) Text(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_no_accounts), style = MaterialTheme.typography.bodyMedium)
                else AccountPicker(accounts, config) { config = it.copy(preset = WidgetPreset.CUSTOM) }
            }
            Section(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_display)) {
                Label(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_style))
                SegmentedTabGroup(WidgetStyle.entries, config.style, { config = config.copy(preset = WidgetPreset.CUSTOM, style = it) }, { androidx.compose.ui.res.stringResource(it.label) })
                Text(androidx.compose.ui.res.stringResource(config.style.hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Label(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_arrange_accounts))
                SegmentedTabGroup(WidgetArrangement.entries, config.arrangement, { config = config.copy(preset = WidgetPreset.CUSTOM, arrangement = it) }, { androidx.compose.ui.res.stringResource(it.label) })
                if (config.style == WidgetStyle.RINGS || config.style == WidgetStyle.PERCENT) {
                    Label(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_arrange_windows))
                    SegmentedTabGroup(WidgetWindowLayout.entries, config.windowLayout, { config = config.copy(preset = WidgetPreset.CUSTOM, windowLayout = it) }, { androidx.compose.ui.res.stringResource(it.label) })
                }
                Label(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_windows))
                SegmentedTabGroup(WidgetWindows.entries, config.windows, { config = config.copy(preset = WidgetPreset.CUSTOM, windows = it) }, { androidx.compose.ui.res.stringResource(it.label) }, fillEqually = true)
                Label(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_reset))
                SegmentedTabGroup(ResetFormat.entries, config.reset, { config = config.copy(preset = WidgetPreset.CUSTOM, reset = it) }, { androidx.compose.ui.res.stringResource(it.label) }, fillEqually = true)
                Text(when (config.reset) { ResetFormat.CLOCK -> androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_example_clock); ResetFormat.RELATIVE -> androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_example_relative); ResetFormat.BOTH -> androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_example_both) },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Label(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_bar_color))
                SegmentedTabGroup(WidgetColors.entries, config.colors, { config = config.copy(preset = WidgetPreset.CUSTOM, colors = it) }, { androidx.compose.ui.res.stringResource(it.label) }, fillEqually = true)
                Text(if (config.colors == WidgetColors.TRAFFIC) androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_traffic_hint) else androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_app_colors), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_colors_shape)) {
                Label(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_background))
                SegmentedTabGroup(WidgetTheme.entries, config.theme, { config = config.copy(preset = WidgetPreset.CUSTOM, theme = it) }, { androidx.compose.ui.res.stringResource(it.label) }, fillEqually = true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SwitchLine(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.dynamic_colors), androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.dynamic_colors_desc), config.dynamic) { config = config.copy(preset = WidgetPreset.CUSTOM, dynamic = it) }
                SliderLine(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_opacity), "${config.opacity} %", config.opacity.toFloat(), 20f..100f) { config = config.copy(preset = WidgetPreset.CUSTOM, opacity = it.toInt()) }
                SliderLine(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_corners), "${config.corner} dp", config.corner.toFloat(), 0f..40f) { config = config.copy(preset = WidgetPreset.CUSTOM, corner = it.toInt()) }
                SliderLine(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_text_size), "${config.textScale} %", config.textScale.toFloat(), 80f..140f) { config = config.copy(preset = WidgetPreset.CUSTOM, textScale = it.toInt() / 5 * 5) }
            }
            Section(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_elements)) {
                SwitchLine(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_refresh_button), androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_refresh_button_desc), config.showRefresh) { config = config.copy(preset = WidgetPreset.CUSTOM, showRefresh = it) }
                SwitchLine(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_updated), androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_updated_desc), config.showUpdated) { config = config.copy(preset = WidgetPreset.CUSTOM, showUpdated = it) }
                SwitchLine(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_plan), androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_plan_desc), config.showPlan) { config = config.copy(preset = WidgetPreset.CUSTOM, showPlan = it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Preview(bitmap: Bitmap?, widthDp: Int, heightDp: Int, selected: Int, select: (Int) -> Unit) {
    val dark = isSystemInDarkTheme()
    val wallpaper = if (dark) listOf(Color(0xFF2B3A55), Color(0xFF4A3B5C), Color(0xFF1E2A3A)) else listOf(Color(0xFF9DB8E8), Color(0xFFD9C3F0), Color(0xFFF2D6C4))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().animateContentSize().background(Brush.linearGradient(wallpaper), MaterialTheme.shapes.extraLarge).padding(18.dp), contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_preview), Modifier.width(widthDp.dp).height(heightDp.dp))
            else Box(Modifier.width(widthDp.dp).height(heightDp.dp))
        }
        Text(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_size_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SegmentedTabGroup((-1 until previewSizes.size).toList(), selected, select, { if (it < 0) androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_your_widget) else androidx.compose.ui.res.stringResource(previewSizes[it].first) })
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    com.madebycli.aiusage.ui.OrionGroupItem(0, 1) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    com.madebycli.aiusage.ui.SwitchRow(title, subtitle, checked, change)

}

@Composable
private fun SliderLine(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    Column {
        Row { Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)); Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Slider(current, change, valueRange = range)
    }
}

/** All accounts, or a chosen few in a chosen order. */
@Composable
private fun AccountPicker(accounts: List<WidgetAccount>, config: WidgetConfig, update: (WidgetConfig) -> Unit) {
    val all = config.accounts.isEmpty()
    SwitchLine(androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_all_accounts), androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.wc_all_accounts_desc), all) { auto ->
        update(config.copy(accounts = if (auto) emptyList() else accounts.map { it.account.id }))
    }
    if (all) return
    val chosen = config.accounts.mapNotNull { id -> accounts.firstOrNull { it.account.id == id } }
    val others = accounts.filter { entry -> chosen.none { it.account.id == entry.account.id } }
    fun move(id: String, by: Int) {
        val order = config.accounts.toMutableList()
        val from = order.indexOf(id)
        val to = (from + by).coerceIn(0, order.lastIndex)
        if (from != to) { order.add(to, order.removeAt(from)); update(config.copy(accounts = order)) }
    }
    (chosen.map { it to true } + others.map { it to false }).forEach { (entry, selected) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, { checked ->
                val order = config.accounts.toMutableList()
                if (checked) order += entry.account.id else if (order.size > 1) order -= entry.account.id
                update(config.copy(accounts = order))
            })
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(listOfNotNull(entry.provider, entry.plan).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                IconButton(onClick = { move(entry.account.id, -1) }, enabled = config.accounts.indexOf(entry.account.id) > 0) { Icon(Icons.Rounded.KeyboardArrowUp, androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.usage_up)) }
                IconButton(onClick = { move(entry.account.id, 1) }, enabled = config.accounts.indexOf(entry.account.id) < config.accounts.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, androidx.compose.ui.res.stringResource(com.madebycli.aiusage.R.string.usage_down)) }
            }
        }
    }
}
