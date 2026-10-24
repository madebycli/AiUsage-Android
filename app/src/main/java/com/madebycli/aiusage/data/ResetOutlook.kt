package com.madebycli.aiusage.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

enum class SourceState { FRESH, STALE, ERROR, EMPTY }

data class SourceStatus(val source: IntelSource, val state: SourceState, val fetchedAt: Long?, val publishedAt: Long?, val error: String?, val partial: String?)

data class SourceChance(val source: IntelSource, val chance: Chance, val fresh: Boolean, val fetchedAt: Long)

/** Every source's own number for one horizon. They are listed side by side, never averaged. */
data class Horizon(val hours: Int, val rows: List<SourceChance>) {
    private val values get() = rows.filter { it.fresh }.map { it.chance.percent }
    /** Difference between the highest and the lowest current estimate, when two or more sources report this horizon. */
    val spread: Double? get() = values.takeIf { it.size >= 2 }?.let { it.max() - it.min() }
    val disagree: Boolean get() = (spread ?: 0.0) >= DISAGREEMENT_POINTS
    companion object { const val DISAGREEMENT_POINTS = 25.0 }
}

data class ResetOutlook(
    val provider: String,
    /** The last reset any source knows about, and whether the time of day is known. */
    val lastResetAt: Long?, val lastResetTimeKnown: Boolean,
    val rhythm: Rhythm?, val window: DayWindow?,
    val horizons: List<Horizon>,
    val hints: List<Pair<IntelSource, String>>,
    val expected: List<Pair<IntelSource, Long>>,
    val timeline: List<ResetEntry>,
    val sources: List<SourceStatus>
) {
    /** Resets everybody got (not saved credits). */
    val resets get() = timeline.filter { it.kind != ResetKind.BANKED }
}

object ResetOutlookBuilder {
    private const val MAX_FETCH_AGE = 2 * 3600L
    private const val DEFAULT_PUBLISH_AGE = 3 * 3600L
    private const val SAME_RESET_SECONDS = 6 * 3600L

    /** A report is current when it was read recently and its own publication time is not too old for how often that source updates. */
    fun isFresh(report: IntelReport, now: Long): Boolean {
        if (now - report.fetchedAt !in -300L..MAX_FETCH_AGE) return false
        val published = report.publishedAt ?: return true
        val until = report.validUntil
        val limit = if (until != null && until > published && until - published <= 12 * 3600) until - published + 3600 else DEFAULT_PUBLISH_AGE
        return now - published in -300L..limit
    }

    fun build(provider: String, results: Map<IntelSource, IntelResult>, now: Long): ResetOutlook {
        val sources = IntelSource.entries.filter { it.provider == provider }
        val reports = sources.mapNotNull { source -> results[source]?.report?.takeIf { it.source == source } }
        val statuses = sources.map { source ->
            val result = results[source]
            val report = result?.report?.takeIf { it.source == source }
            SourceStatus(source, when {
                result == null -> SourceState.EMPTY
                report == null -> if (result.error != null) SourceState.ERROR else SourceState.EMPTY
                result.error != null -> SourceState.ERROR
                isFresh(report, now) -> SourceState.FRESH
                else -> SourceState.STALE
            }, report?.fetchedAt, report?.publishedAt, result?.error, result?.partial)
        }
        val horizons = reports.flatMap { report ->
            val fresh = isFresh(report, now) && results[report.source]?.error == null
            report.chances.filter { it.hours in 1..336 && it.percent.isFinite() && it.percent in 0.0..100.0 }
                // One value per source and horizon: a source that lists two different numbers for the same time is not trusted for it.
                .groupBy { it.hours }.mapNotNull { (_, same) -> same.distinctBy { it.percent }.singleOrNull() }
                .map { SourceChance(report.source, it, fresh, report.fetchedAt) }
        }.groupBy { it.chance.hours }.toSortedMap().map { (hours, rows) -> Horizon(hours, rows.sortedBy { it.source.ordinal }) }
        val timeline = mergeTimeline(reports.flatMap { it.entries })
        val resets = timeline.filter { it.kind != ResetKind.BANKED }
        val newest = resets.firstOrNull()
        // claude-resets.net only knows the day, so its own "last reset" never decides the time of day.
        val reported = reports.filter { it.source != IntelSource.CLAUDE_RESETS }.mapNotNull { it.lastResetAt }.maxOrNull()
        // The history row and the headline must name the same moment: when the source's number is the same reset, the row wins.
        val lastAt = when {
            newest == null -> reported
            reported == null || kotlin.math.abs(reported - newest.at) <= SAME_RESET_SECONDS || reported < newest.at -> newest.at
            else -> reported
        }
        val lastKnown = if (lastAt == null) false else if (newest != null && lastAt == newest.at) newest.timeKnown else true
        val rhythm = rhythm(timeline) ?: reports.firstNotNullOfOrNull { it.rhythm }
        return ResetOutlook(provider, lastAt, lastKnown,
            rhythm, reports.firstNotNullOfOrNull { it.window }, horizons,
            reports.flatMap { report -> (listOfNotNull(report.headline) + report.hints).map { report.source to it } },
            reports.mapNotNull { r -> r.expectedAt?.takeIf { it > now }?.let { r.source to it } }, timeline, statuses)
    }

    private fun sameReset(a: ResetEntry, b: ResetEntry): Boolean = when {
        // An unclassified report may be either kind; two classified ones of different kind are different events.
        a.kind != ResetKind.EVENT && b.kind != ResetKind.EVENT && (a.kind == ResetKind.BANKED) != (b.kind == ResetKind.BANKED) -> false
        a.timeKnown && b.timeKnown -> kotlin.math.abs(a.at - b.at) <= SAME_RESET_SECONDS
        else -> Math.floorDiv(a.at, 86_400L) == Math.floorDiv(b.at, 86_400L)
    }

    private val generic = setOf("Reset gemeldet", "Reset im Verlauf")
    private fun richness(e: ResetEntry) = (if (e.title !in generic) 4 else 0) + (if (e.detail != null) 2 else 0) + (if (e.url != null) 1 else 0)
    private fun rank(kind: ResetKind) = when (kind) { ResetKind.GLOBAL -> 3; ResetKind.PARTIAL -> 2; ResetKind.BANKED -> 1; ResetKind.EVENT -> 0 }

    /** One row per reset: the same reset reported by several sources becomes one entry with the best text, link and time. */
    fun mergeTimeline(entries: List<ResetEntry>): List<ResetEntry> {
        val clusters = mutableListOf<MutableList<ResetEntry>>()
        entries.sortedBy { it.at }.forEach { entry ->
            val home = clusters.lastOrNull { cluster -> cluster.any { sameReset(it, entry) } }
            if (home != null) home += entry else clusters += mutableListOf(entry)
        }
        return clusters.map { cluster ->
            val base = cluster.maxWith(compareBy<ResetEntry>({ richness(it) }, { it.timeKnown }, { it.verified }))
            val exact = cluster.filter { it.timeKnown }
            val at = if (base.timeKnown || exact.isEmpty()) base.at else exact.minOf { it.at }
            base.copy(at = at, timeKnown = base.timeKnown || exact.isNotEmpty(), kind = cluster.maxBy { rank(it.kind) }.kind,
                url = base.url ?: cluster.firstNotNullOfOrNull { it.url }, verified = cluster.any { it.verified })
        }.sortedByDescending { it.at }
    }

    /** Gaps between the latest resets, from the merged history. Needs three gaps to say anything. */
    fun rhythm(timeline: List<ResetEntry>): Rhythm? {
        val times = timeline.filter { it.kind != ResetKind.BANKED }.map { it.at }.sorted()
        val gaps = times.zipWithNext { a, b -> (b - a) / 86_400.0 }.filter { it >= 0.25 }.takeLast(8)
        if (gaps.size < 3) return null
        val sorted = gaps.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        return Rhythm(median, gaps.average(), sorted.last(), gaps.size)
    }

    /** Days between an entry and the previous reset in the list; null for the oldest. */
    fun gapsDays(resets: List<ResetEntry>): List<Double?> =
        resets.mapIndexed { i, e -> resets.getOrNull(i + 1)?.let { older -> (e.at - older.at) / 86_400.0 } }

    /** The window's hours in the phone's time zone, for example 3 to 6 UTC becomes 5 to 8. */
    fun localHours(window: DayWindow, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
        val source = runCatching { ZoneId.of(window.zone) }.getOrDefault(ZoneOffset.UTC)
        val day = Instant.ofEpochSecond(now).atZone(source).toLocalDate()
        fun local(hour: Int): Int = ZonedDateTime.of(day, LocalTime.MIDNIGHT, source).plusHours(hour.toLong()).withZoneSameInstant(zone).hour
        return local(window.startHour) to local(window.endHour)
    }

    fun today(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
}
