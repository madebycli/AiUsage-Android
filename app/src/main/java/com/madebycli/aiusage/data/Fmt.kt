package com.madebycli.aiusage.data

import android.content.res.Resources
import com.madebycli.aiusage.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Every number, time and date the app shows, in the language of [Resources]. All words come from string resources,
 * so a translation only touches `res/values-xx/strings.xml`.
 */
object Fmt {
    fun locale(res: Resources): Locale = res.configuration.locales[0] ?: Locale.getDefault()

    private fun time(res: Resources) = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale(res))

    /** "in 2 h 43 min" / "in 2 Std. 43 Min." Never "02:43", which reads like a time of day. */
    fun remaining(res: Resources, seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val days = (total / 86_400).toInt()
        val hours = ((total % 86_400) / 3_600).toInt()
        val minutes = ((total % 3_600) / 60).toInt()
        return when {
            total < 60 -> res.getString(R.string.time_in_under_minute)
            days > 0 -> res.getQuantityString(R.plurals.time_in_days, days, days) + if (hours > 0) " " + res.getString(R.string.unit_hours, hours) else ""
            hours > 0 -> res.getString(R.string.time_in, res.getString(R.string.unit_hours, hours) + if (minutes > 0) " " + res.getString(R.string.unit_minutes, minutes) else "")
            else -> res.getString(R.string.time_in, res.getString(R.string.unit_minutes, minutes))
        }
    }

    /** Duration without "in": "4 h 10 min". */
    fun duration(res: Resources, seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val days = (total / 86_400).toInt()
        val hours = ((total % 86_400) / 3_600).toInt()
        val minutes = ((total % 3_600) / 60).toInt()
        return listOfNotNull(
            days.takeIf { it > 0 }?.let { res.getString(R.string.unit_days, it) },
            hours.takeIf { it > 0 }?.let { res.getString(R.string.unit_hours, it) },
            minutes.takeIf { it > 0 && days == 0 }?.let { res.getString(R.string.unit_minutes, it) }
        ).joinToString(" ").ifEmpty { res.getString(R.string.unit_minutes, 0) }
    }

    /** "17:40", "tomorrow 03:10", "Thu 17:40", "12/11 17:40". */
    fun clock(res: Resources, at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val moment = Instant.ofEpochSecond(at).atZone(zone)
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, moment.toLocalDate())
        val clock = moment.format(time(res))
        return when (days) {
            0L -> clock
            1L -> res.getString(R.string.time_tomorrow_at, clock)
            -1L -> res.getString(R.string.time_yesterday_at, clock)
            in 2..6 -> res.getString(R.string.time_weekday_at, weekdayShort(res, moment.dayOfWeek.value), clock)
            else -> res.getString(R.string.time_weekday_at, shortDate(res, at, zone), clock)
        }
    }

    fun clockOnly(res: Resources, at: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochSecond(at).atZone(zone).format(time(res))

    /** "just now", "35 min ago", "17 h ago", "3 days ago". */
    fun ago(res: Resources, seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> res.getString(R.string.time_just_now)
            s < 3600 -> res.getString(R.string.time_ago, res.getString(R.string.unit_minutes, (s / 60).toInt()))
            s < 48 * 3600 -> res.getString(R.string.time_ago, res.getString(R.string.unit_hours, (s / 3600).toInt()))
            else -> (s / 86_400).toInt().let { res.getQuantityString(R.plurals.time_days_ago, it, it) }
        }
    }

    /** Mon = 1 … Sun = 7. */
    fun weekdayShort(res: Resources, isoDay: Int): String = res.getStringArray(R.array.weekdays_short)[isoDay - 1]
    fun weekdayLong(res: Resources, isoDay: Int): String = res.getStringArray(R.array.weekdays_long)[isoDay - 1]
    fun weekdayLetter(res: Resources, isoDay: Int): String = res.getStringArray(R.array.weekdays_letter)[isoDay - 1]
    fun monthShort(res: Resources, month: Int): String = res.getStringArray(R.array.months_short)[month - 1]
    fun monthLong(res: Resources, month: Int): String = res.getStringArray(R.array.months_long)[month - 1]

    fun shortDate(res: Resources, at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochSecond(at).atZone(zone).toLocalDate().let { res.getString(R.string.date_day_month, it.dayOfMonth, it.monthValue) }

    fun dayMonth(res: Resources, date: java.time.LocalDate): String = res.getString(R.string.date_day_month, date.dayOfMonth, date.monthValue)
    fun longDate(res: Resources, date: java.time.LocalDate): String =
        res.getString(R.string.date_long, weekdayLong(res, date.dayOfWeek.value), date.dayOfMonth, monthLong(res, date.monthValue))
    fun mediumDate(res: Resources, date: java.time.LocalDate): String =
        res.getString(R.string.date_medium, weekdayShort(res, date.dayOfWeek.value), date.dayOfMonth, monthShort(res, date.monthValue))

    fun number(res: Resources, value: Double, maxFraction: Int = 1): String =
        NumberFormat.getNumberInstance(locale(res)).apply { maximumFractionDigits = maxFraction }.format(value)

    /** Whole percentages for display; the stored values keep their precision. */
    fun percent(value: Double): String = if (value.isFinite()) Math.round(value).toString() else "–"

    /** 100 five-hour points are one full limit: "2.4 limits". */
    fun limits(res: Resources, points: Double): String {
        val limits = points / 100
        val rounded = if (limits < 10) Math.round(limits * 10) / 10.0 else Math.round(limits).toDouble()
        val quantity = if (rounded == 1.0) 1 else 2
        return res.getQuantityString(R.plurals.limits, quantity, number(res, rounded, if (limits < 10) 1 else 0))
    }
}
