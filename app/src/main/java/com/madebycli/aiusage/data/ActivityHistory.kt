package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * One calendar day of provider-reported values.
 * [peak] and [weekly] are quota percentages the provider returned when the app read them;
 * [actual] is a daily total the provider reported itself (Codex tokens).
 */
@Serializable
data class UsageDay(val date: String, val peak: Double?, val measurements: Int = 0, val lastMeasuredAt: Long,
    val actual: Double? = null, val unit: String? = null, val source: String? = null, val weekly: Double? = null)

/** Stores the newest snapshot and folds its quota percentages into that day's record. */
fun recordMeasurement(record: PersonalRecord, snapshot: UsageSnapshot, provider: String, zone: ZoneId = ZoneId.systemDefault()): PersonalRecord {
    val current = record.usage
    if (current != null && snapshot.fetchedAt <= current.fetchedAt) {
        return if (snapshot.fetchedAt == current.fetchedAt) record.copy(usage = snapshot) else record
    }
    val day = Instant.ofEpochSecond(snapshot.fetchedAt).atZone(zone).toLocalDate().toString()
    fun percent(seconds: Long) = snapshot.windows(provider).firstOrNull { it.seconds == seconds }
        ?.usedPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val old = record.days.firstOrNull { it.date == day }
    val updated = UsageDay(day, listOfNotNull(old?.peak, percent(FIVE_HOURS)).maxOrNull(), 0, snapshot.fetchedAt,
        weekly = percent(ONE_WEEK) ?: old?.weekly)
    val days = (record.days.filter { it.date != day } + updated).sortedByDescending { it.date }.take(366)
    return record.copy(usage = snapshot, days = days)
}
