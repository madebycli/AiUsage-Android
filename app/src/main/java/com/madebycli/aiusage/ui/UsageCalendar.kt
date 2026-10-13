package com.madebycli.aiusage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madebycli.aiusage.data.UsageDay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** AniSync/GitHub contribution layout; cells represent measured quota, not inferred daily tokens. */
@Composable
internal fun UsageCalendar(provider: String, days: List<UsageDay>, now: Long) {
    val today = Instant.ofEpochSecond(now).atZone(ZoneId.systemDefault()).toLocalDate()
    var period by rememberSaveable(provider) { mutableStateOf("Monat") }
    var selected by rememberSaveable(provider) { mutableStateOf(today.toString()) }
    var weekOffset by rememberSaveable(provider) { mutableIntStateOf(0) }
    var info by remember { mutableStateOf(false) }
    val records = remember(days) { days.mapNotNull { day -> runCatching { LocalDate.parse(day.date) to day }.getOrNull() }.toMap() }
    val colors = MaterialTheme.colorScheme
    SoftCard {
        SegmentedTabGroup(listOf("Monat", "Woche"), period, { period = it }, { it },
            fillEqually = true, icon = { if (it == "Monat") Icons.Rounded.CalendarMonth else Icons.Rounded.ViewWeek })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${days.filter { it.date >= today.minusWeeks(20).toString() && it.date <= today.toString() }.sumOf { it.measurements }} Messungen · ${days.count { it.date >= today.minusWeeks(20).toString() && it.date <= today.toString() }} Tage", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { info = true }) { Icon(Icons.Rounded.Info, "Kalender erklären", Modifier.size(20.dp)) }
        }
        if (period == "Monat") {
            val first = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(19)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val cellPixels = ((maxWidth.value * density.density - (4 * density.density).roundToInt() * 19) / 20).toInt().coerceAtLeast(1)
                val cell = (cellPixels / density.density).dp.coerceAtMost(24.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(20) { week ->
                            val date = first.plusWeeks(week.toLong())
                            val label = if (week == 0 || date.month != date.minusWeeks(1).month) date.format(DateTimeFormatter.ofPattern("MMM", Locale.GERMAN)).removeSuffix(".") else ""
                            Box(Modifier.width(cell).height(18.dp)) { Text(label, modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true), fontSize = 10.sp, lineHeight = 12.sp, softWrap = false, color = colors.onSurfaceVariant) }
                        }
                    }
                    repeat(7) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            repeat(20) { week ->
                                val date = first.plusDays(week * 7L + row)
                                val record = records[date]
                                val future = date > today
                                val level = record?.peak
                                val background = when { future -> Color.Transparent; level == null -> colors.surfaceContainerHigh; level == 0.0 -> colors.primary.copy(alpha = .08f); else -> colors.primary.copy(alpha = (.25 + level / 100 * .75).toFloat()) }
                                val shape = RoundedCornerShape(3.dp)
                                Box(Modifier.size(cell).clip(shape).background(background)
                                    .then(if (date.toString() == selected) Modifier.border(1.dp, colors.primary, shape) else Modifier)
                                    .semantics { contentDescription = "$provider, $date: ${level?.let { "${formatPeak(it)} Prozent gemessene 5h-Spitze" } ?: "keine Messung"}" }
                                    .clickable(enabled = !future) { selected = date.toString() })
                            }
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Weniger", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                listOf(.12f, .3f, .5f, .7f, 1f).forEach { alpha -> Box(Modifier.size(12.dp).background(colors.primary.copy(alpha = alpha), RoundedCornerShape(3.dp))) }
                Text("Mehr", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        } else {
            val first = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { weekOffset-- }) { Icon(Icons.Rounded.ChevronLeft, "Vorherige Woche") }
                Text("${first.format(DateTimeFormatter.ofPattern("dd.MM."))} – ${first.plusDays(6).format(DateTimeFormatter.ofPattern("dd.MM."))}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { weekOffset++ }, enabled = weekOffset < 0) { Icon(Icons.Rounded.ChevronRight, "Nächste Woche") }
            }
            repeat(7) { day ->
                val date = first.plusDays(day.toLong())
                val record = records[date]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = date <= today) { selected = date.toString() }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(date.format(DateTimeFormatter.ofPattern("EE dd", Locale.GERMAN)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                    UsageTrack(((record?.peak ?: 0.0) / 100).toFloat(), colors.primary, Modifier.weight(1f).height(12.dp))
                    Text(record?.peak?.let { "${formatPeak(it)}%" } ?: "—", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(42.dp))
                }
            }
        }
        val entry = records[runCatching { LocalDate.parse(selected) }.getOrNull()]
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = .4f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(runCatching { LocalDate.parse(selected).format(DateTimeFormatter.ofPattern("dd. MMMM", Locale.GERMAN)) }.getOrDefault(selected), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(entry?.peak?.let { "${formatPeak(it)}% · 5h-Spitze" } ?: "Keine Messung", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            entry?.let { Text("${it.measurements}×", style = MaterialTheme.typography.titleLarge, color = colors.primary) }
        }
    }
    if (info) AlertDialog(onDismissRequest = { info = false }, title = { Text("Gemessene Nutzung") },
        text = { Text("Jeder Tag zeigt den höchsten tatsächlich gemessenen 5-Stunden-Verbrauch. Das ist keine vollständige Tagesbilanz. Ohne Abruf bleibt der Tag unbekannt. Die App sammelt ab jetzt Messungen, automatisch etwa täglich und bei Aktualisierungen. Android kann Hintergrundabrufe verzögern.") },
        confirmButton = { TextButton(onClick = { info = false }) { Text("Verstanden") } })
}
private fun formatPeak(value: Double) = if (value % 1 == 0.0) value.toInt().toString() else String.format(Locale.GERMAN, "%.1f", value)
