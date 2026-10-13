package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

@Serializable
data class UsageDay(val date: String, val peak: Double?, val measurements: Int = 0, val lastMeasuredAt: Long,
    val actual: Double? = null, val unit: String? = null, val source: String? = null)

/** Actual sampled five-hour quota, not token counts or an invented complete daily total. */
fun recordMeasurement(record: PersonalRecord, snapshot: UsageSnapshot, provider: String, zone: ZoneId = ZoneId.systemDefault()): PersonalRecord {
    if (record.usage != null && snapshot.fetchedAt <= record.usage.fetchedAt) {
        return if (snapshot.fetchedAt == record.usage.fetchedAt) record.copy(usage = snapshot) else record
    }
    val day = Instant.ofEpochSecond(snapshot.fetchedAt).atZone(zone).toLocalDate().toString()
    val value = snapshot.groups.firstOrNull { it.name == provider }?.windows?.firstOrNull { it.seconds == 18000L }
        ?.usedPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
    val old = record.days.firstOrNull { it.date == day }
    val peak = listOfNotNull(old?.peak, value).maxOrNull()
    // Legacy measurement counts were fetch counts, never usage. Stop incrementing them.
    val updated = UsageDay(day, peak, 0, snapshot.fetchedAt)
    val days = (record.days.filter { it.date != day } + updated).sortedByDescending { it.date }.take(366)
    return record.copy(usage = snapshot, days = days)
}

data class HistorySource(val id: String, val label: String)
fun historySources(provider: String): List<HistorySource> = buildList {
    if (provider == "Codex") ResetSource.entries.forEach { add(HistorySource("api:${it.name}", it.label)) }
    CommunitySource.entries.filter { it.provider == provider }.forEach { add(HistorySource("page:${it.name}", it.label)) }
}
fun historyEvents(provider: String, sourceId: String, feed: ResetFeed?, community: Map<CommunitySource, CommunityResult>): List<ResetEvent> {
    val events = if (sourceId.startsWith("api:") && provider == "Codex") {
        val source = ResetSource.entries.firstOrNull { "api:${it.name}" == sourceId }
        feed?.takeIf { it.source == source }?.events.orEmpty()
    } else {
        val source = CommunitySource.entries.firstOrNull { "page:${it.name}" == sourceId && it.provider == provider }
        community[source]?.report?.takeIf { it.source == source }?.events.orEmpty()
    }
    return events.sortedByDescending { it.at }.distinctBy { event ->
        event.url?.let { runCatching { val uri = java.net.URI(it); "${uri.host?.lowercase()}${uri.path}" }.getOrNull() }
            ?: "${event.at}:${event.kind}:${event.summary.trim()}"
    }.take(100)
}
