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

private val previewSizes = listOf("Breit" to (250 to 120), "Hoch" to (160 to 230), "Groß" to (250 to 260), "Schmal" to (250 to 62))

@Composable
private fun ConfigScreen(initial: WidgetConfig, widgetWidth: Int, widgetHeight: Int, onCancel: () -> Unit, onSave: (WidgetConfig) -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(initial) }
    val accounts by produceState(emptyList<WidgetAccount>()) { value = withContext(Dispatchers.IO) { WidgetData.load(context) } }
    var sizeIndex by remember { mutableIntStateOf(-1) }
    val (previewW, previewH) = if (sizeIndex < 0) widgetWidth to widgetHeight else previewSizes[sizeIndex].second
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(config, accounts, previewW, previewH) {
        bitmap = withContext(Dispatchers.Default) { runCatching { WidgetRenderer.render(context, config, accounts, previewW, previewH) }.getOrNull() }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Widget anpassen") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Abbrechen") } },
                actions = { FilledTonalButton(onClick = { onSave(config) }) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Fertig") }; Spacer(Modifier.width(12.dp)) })
        }
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Preview(bitmap, previewW, previewH, sizeIndex) { sizeIndex = it }
            Section("Accounts") {
                if (accounts.isEmpty()) Text("Es ist noch kein Account verbunden. Verbinde zuerst einen Account in der App.", style = MaterialTheme.typography.bodyMedium)
                else AccountPicker(accounts, config) { config = it }
            }
            Section("Anzeige") {
                Label("Darstellung")
                SegmentedTabGroup(WidgetStyle.entries, config.style, { config = config.copy(style = it) }, { it.title })
                Text(config.style.hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Label("Zeitfenster")
                SegmentedTabGroup(WidgetWindows.entries, config.windows, { config = config.copy(windows = it) }, { it.title }, fillEqually = true)
                Label("Reset-Zeit")
                SegmentedTabGroup(ResetFormat.entries, config.reset, { config = config.copy(reset = it) }, { it.title }, fillEqually = true)
                Text(when (config.reset) { ResetFormat.CLOCK -> "z. B. 17:40"; ResetFormat.RELATIVE -> "z. B. in 2 Std. 50 Min."; ResetFormat.BOTH -> "z. B. 17:40 · in 2 Std. 50 Min." },
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Label("Balkenfarbe")
                SegmentedTabGroup(WidgetColors.entries, config.colors, { config = config.copy(colors = it) }, { it.title }, fillEqually = true)
                Text(if (config.colors == WidgetColors.TRAFFIC) "Grün bis 70 %, Gelb bis 90 %, danach Rot" else "Farben der App", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("Farben & Form") {
                Label("Hintergrund")
                SegmentedTabGroup(WidgetTheme.entries, config.theme, { config = config.copy(theme = it) }, { it.title }, fillEqually = true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SwitchLine("Android-Farben", "Palette aus dem Hintergrundbild", config.dynamic) { config = config.copy(dynamic = it) }
                SliderLine("Deckkraft", "${config.opacity} %", config.opacity.toFloat(), 20f..100f) { config = config.copy(opacity = it.toInt()) }
                SliderLine("Ecken", "${config.corner} dp", config.corner.toFloat(), 0f..40f) { config = config.copy(corner = it.toInt()) }
                SliderLine("Schriftgröße", "${config.textScale} %", config.textScale.toFloat(), 80f..140f) { config = config.copy(textScale = it.toInt() / 5 * 5) }
            }
            Section("Elemente") {
                SwitchLine("Aktualisieren-Knopf", "Liest die Accounts sofort neu", config.showRefresh) { config = config.copy(showRefresh = it) }
                SwitchLine("Zeit der letzten Messung", "„Stand 14:05“, warnt bei veralteten Daten", config.showUpdated) { config = config.copy(showUpdated = it) }
                SwitchLine("Tarif anzeigen", "Plus, Max 5x und so weiter", config.showPlan) { config = config.copy(showPlan = it) }
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
        Box(Modifier.fillMaxWidth().animateContentSize().background(Brush.linearGradient(wallpaper), RoundedCornerShape(28.dp)).padding(18.dp), contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), "Vorschau", Modifier.width(widthDp.dp).height(heightDp.dp))
            else Box(Modifier.width(widthDp.dp).height(heightDp.dp))
        }
        SegmentedTabGroup((-1 until previewSizes.size).toList(), selected, select, { if (it < 0) "Dein Widget" else previewSizes[it].first })
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, change)
    }
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
    SwitchLine("Alle Accounts", "In der Reihenfolge der Startseite, neue kommen automatisch dazu", all) { auto ->
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
                IconButton(onClick = { move(entry.account.id, -1) }, enabled = config.accounts.indexOf(entry.account.id) > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Nach oben") }
                IconButton(onClick = { move(entry.account.id, 1) }, enabled = config.accounts.indexOf(entry.account.id) < config.accounts.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Nach unten") }
            }
        }
    }
}
