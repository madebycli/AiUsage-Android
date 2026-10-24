package com.madebycli.aiusage.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** German wording for reset times, shared by the app and the widget. */
object TimeText {
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
    private val dayMonth = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMAN)
    private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.GERMAN)

    /** "in 2 Std. 50 Min.", never "02:50:00", which reads like a time of day. */
    fun remaining(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val days = total / 86_400
        val hours = (total % 86_400) / 3_600
        val minutes = (total % 3_600) / 60
        return when {
            total < 60 -> "in unter 1 Min."
            days > 0 -> "in $days ${if (days == 1L) "Tag" else "Tagen"}${if (hours > 0) " $hours Std." else ""}"
            hours > 0 -> "in $hours Std.${if (minutes > 0) " $minutes Min." else ""}"
            else -> "in $minutes Min."
        }
    }

    /** The clock time of [at]: "17:40", with the day when it is not today ("morgen 03:10", "Do. 17:40", "12.11. 17:40"). */
    fun clock(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val moment = Instant.ofEpochSecond(at).atZone(zone)
        val today: LocalDate = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, moment.toLocalDate())
        val time = moment.format(hourMinute)
        return when {
            days == 0L -> time
            days == 1L -> "morgen $time"
            days == -1L -> "gestern $time"
            days in 2..6 -> "${moment.format(weekday).removeSuffix(".")}. $time"
            else -> "${moment.format(dayMonth)} $time"
        }
    }

    /** "17:40 Uhr" style sentence part for headings: "heute 17:40 Uhr". */
    fun clockLong(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val text = clock(at, now, zone)
        val moment = Instant.ofEpochSecond(at).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        return (if (moment.toLocalDate() == today) "heute $text" else text) + " Uhr"
    }
}

/** "42" or "42,5": whole numbers stay whole. */
fun formatPercent(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.GERMAN, "%.1f", value)
