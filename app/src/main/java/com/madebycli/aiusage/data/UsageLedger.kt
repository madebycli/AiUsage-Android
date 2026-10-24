package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Usage bookkeeping that works on *runs*, not on readings.
 *
 * A run is one reset cycle of a quota window: it is identified by the reset time the provider reports.
 * Reading the same run twice, however often, never adds usage twice; only the part above the highest
 * value already seen in that run counts. A new run starts when the provider reports a different reset
 * time, or when the previous run's reset time has passed.
 */
@Serializable
data class LedgerRun(
    /** Reset time that identifies the run. */
    val endsAt: Long,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** Highest percentage the provider reported for this run. */
    val peak: Double
)

/**
 * Everything known about one calendar day (local time).
 * [five] and [week] are percentage points of the respective window that were consumed.
 * 100 points of [five] equal one full 5-hour limit.
 */
@Serializable
data class LedgerDay(
    val date: String,
    val five: Double = 0.0,
    val week: Double = 0.0,
    /** 5-hour runs in which usage was first seen on this day. */
    val fiveRuns: Int = 0,
    val weekRuns: Int = 0,
    /** 5-hour / weekly resets that happened on this day. */
    val fiveResets: Int = 0,
    val weekResets: Int = 0,
    /** Highest 5-hour percentage reached on this day. */
    val peak: Double = 0.0,
    /** Number of readings taken on this day. 0 means nobody looked, so the value is unknown. */
    val reads: Int = 0,
    /** Carried over from the old peak-only history; usage is a lower bound. */
    val legacy: Boolean = false
) {
    val measured get() = reads > 0 || legacy || five > 0 || week > 0
    fun value(metric: StatsMetric) = if (metric == StatsMetric.FIVE) five else week
    fun resets(metric: StatsMetric) = if (metric == StatsMetric.FIVE) fiveResets else weekResets
    fun runs(metric: StatsMetric) = if (metric == StatsMetric.FIVE) fiveRuns else weekRuns
}

enum class StatsMetric(val seconds: Long, val title: String) {
    FIVE(FIVE_HOURS, "5-Std.-Limit"), WEEK(ONE_WEEK, "Wochenlimit")
}

@Serializable
data class UsageLedger(
    /** Newest first. */
    val days: List<LedgerDay> = emptyList(),
    val fiveRun: LedgerRun? = null,
    val weekRun: LedgerRun? = null,
    val lastReadAt: Long = 0
) {
    fun run(metric: StatsMetric) = if (metric == StatsMetric.FIVE) fiveRun else weekRun
}

/**
 * A run that ended. [early] means the provider reset the window before its announced deadline, for example a
 * global reset in the middle of the week or a redeemed reset credit. [at] is when the reset happened as far as
 * known: the old deadline, or the moment the change was noticed for an early one.
 */
data class LedgerEvent(val metric: StatsMetric, val at: Long, val early: Boolean, val peak: Double, val endedAt: Long)

class LedgerUpdate(val ledger: UsageLedger, val events: List<LedgerEvent>)

object UsageTracker {
    /** Providers report the same reset with a little jitter; closer times are the same run. */
    const val RESET_TOLERANCE_SECONDS = 300L
    /** A reading without a reset time continues a run unless the percentage fell by more than this. */
    private const val DROP_TOLERANCE = 1.0
    const val MAX_DAYS = 800

    private class Closed(val at: Long, val early: Boolean, val peak: Double, val endsAt: Long)
    private class Outcome(var run: LedgerRun?, var added: Double = 0.0, var started: Boolean = false, val resets: MutableList<Closed> = mutableListOf())

    /** Folds one reading of one window into its run. [window] is null when the provider reports none. */
    private fun observe(window: UsageWindow?, current: LedgerRun?, at: Long, seconds: Long): Outcome {
        val outcome = Outcome(current)
        val percent = window?.usedPercent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
        val reported = window?.resetAt
        var run = current
        if (run != null) {
            val expired = at > run.endsAt + RESET_TOLERANCE_SECONDS
            val sameEnd = reported != null && abs(reported - run.endsAt) <= RESET_TOLERANCE_SECONDS
            val continues = when {
                window == null || percent == null -> !expired
                sameEnd -> true
                reported == null -> !expired && percent >= run.peak - DROP_TOLERANCE
                else -> false
            }
            if (!continues) {
                // A passed deadline resets at that deadline; a changed one resets when it was noticed.
                outcome.resets += Closed(if (expired) run.endsAt else at, !expired, run.peak, run.endsAt)
                run = null
            } else if (sameEnd) run = run.copy(endsAt = reported!!)
        }
        if (percent != null) {
            if (run == null) {
                if (percent > 0) {
                    run = LedgerRun(reported ?: (at + seconds), at, at, percent)
                    outcome.added = percent
                    outcome.started = true
                }
            } else {
                outcome.added = (percent - run.peak).coerceAtLeast(0.0)
                run = run.copy(peak = maxOf(run.peak, percent), lastSeenAt = at)
            }
        }
        outcome.run = run
        return outcome
    }

    fun record(ledger: UsageLedger, snapshot: UsageSnapshot, provider: String, zone: ZoneId = ZoneId.systemDefault()): UsageLedger =
        update(ledger, snapshot, provider, zone).ledger

    fun update(ledger: UsageLedger, snapshot: UsageSnapshot, provider: String, zone: ZoneId = ZoneId.systemDefault()): LedgerUpdate {
        val at = snapshot.fetchedAt
        // Older or repeated readings (enrichment of the same read, racing workers) add nothing.
        if (at <= ledger.lastReadAt) return LedgerUpdate(ledger, emptyList())
        fun day(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate().toString()
        val windows = snapshot.windows(provider)
        val five = observe(windows.firstOrNull { it.seconds == FIVE_HOURS }, ledger.fiveRun, at, FIVE_HOURS)
        val week = observe(windows.firstOrNull { it.seconds == ONE_WEEK }, ledger.weekRun, at, ONE_WEEK)
        val today = day(at)
        val byDate = ledger.days.associateByTo(linkedMapOf()) { it.date }
        fun edit(date: String, change: (LedgerDay) -> LedgerDay) { byDate[date] = change(byDate[date] ?: LedgerDay(date)) }
        edit(today) { it.copy(reads = it.reads + 1, five = it.five + five.added, week = it.week + week.added,
            fiveRuns = it.fiveRuns + if (five.started) 1 else 0, weekRuns = it.weekRuns + if (week.started) 1 else 0,
            peak = if (five.added > 0) maxOf(it.peak, five.run?.peak ?: 0.0) else it.peak) }
        five.resets.forEach { closed -> edit(day(closed.at)) { it.copy(fiveResets = it.fiveResets + 1) } }
        week.resets.forEach { closed -> edit(day(closed.at)) { it.copy(weekResets = it.weekResets + 1) } }
        val events = five.resets.map { LedgerEvent(StatsMetric.FIVE, it.at, it.early, it.peak, it.endsAt) } +
            week.resets.map { LedgerEvent(StatsMetric.WEEK, it.at, it.early, it.peak, it.endsAt) }
        return LedgerUpdate(UsageLedger(byDate.values.sortedByDescending { it.date }.take(MAX_DAYS), five.run, week.run, at), events)
    }

    /** Old history only knew the highest 5-hour value of a day; keep that as a lower bound. */
    fun fromLegacy(days: List<UsageDay>, ledger: UsageLedger): UsageLedger {
        if (days.isEmpty()) return ledger
        val known = ledger.days.map { it.date }.toSet()
        val imported = days.filter { it.date !in known && (it.peak != null || it.weekly != null) }
            .map { LedgerDay(it.date, five = it.peak ?: 0.0, peak = it.peak ?: 0.0, legacy = true) }
        return ledger.copy(days = (ledger.days + imported).sortedByDescending { it.date }.take(MAX_DAYS))
    }
}

/** Old peak-only history; still decoded so that it can be carried over once. */
@Serializable
data class UsageDay(val date: String, val peak: Double? = null, val lastMeasuredAt: Long = 0, val weekly: Double? = null)

/** Stores the newest snapshot and folds it into the account's usage ledger. */
fun recordMeasurement(record: PersonalRecord, snapshot: UsageSnapshot, provider: String, zone: ZoneId = ZoneId.systemDefault()): PersonalRecord =
    recordMeasurementEvents(record, snapshot, provider, zone).first

/** Like [recordMeasurement], and also names the runs that ended with this reading. Each ending is reported exactly once. */
fun recordMeasurementEvents(record: PersonalRecord, snapshot: UsageSnapshot, provider: String, zone: ZoneId = ZoneId.systemDefault()): Pair<PersonalRecord, List<LedgerEvent>> {
    val base = record.migrated()
    val current = base.usage
    if (current != null && snapshot.fetchedAt < current.fetchedAt) return base to emptyList()
    val update = UsageTracker.update(base.ledger, snapshot, provider, zone)
    return base.copy(usage = snapshot, ledger = update.ledger) to update.events
}

/** Moves the old peak-only history into the ledger once. */
fun PersonalRecord.migrated(): PersonalRecord =
    if (days.isEmpty()) this else copy(days = emptyList(), ledger = UsageTracker.fromLegacy(days, ledger))

// ---- Read side -------------------------------------------------------------------------------

data class RangeSummary(
    val range: ClosedRange<LocalDate>,
    val total: Double,
    val activeDays: Int,
    val measuredDays: Int,
    val resets: Int,
    val runs: Int,
    val bestDay: LedgerDay?,
    val peak: Double,
    /** Total consumed per weekday, Monday first. */
    val weekdayTotal: List<Double>,
    /** Average per measured day, Monday first; null where no day was measured. */
    val weekdayAverage: List<Double?>,
    val busiestWeekday: Int?
) {
    val averagePerActiveDay get() = if (activeDays == 0) 0.0 else total / activeDays
}

object LedgerStats {
    /** Adds up the same dates of several accounts. */
    fun merge(ledgers: List<UsageLedger>): List<LedgerDay> {
        if (ledgers.size == 1) return ledgers.single().days
        return ledgers.flatMap { it.days }.groupBy { it.date }.map { (date, parts) ->
            LedgerDay(date, parts.sumOf { it.five }, parts.sumOf { it.week }, parts.sumOf { it.fiveRuns }, parts.sumOf { it.weekRuns },
                parts.sumOf { it.fiveResets }, parts.sumOf { it.weekResets }, parts.maxOf { it.peak }, parts.sumOf { it.reads }, parts.all { it.legacy })
        }.sortedByDescending { it.date }
    }

    fun summarize(days: List<LedgerDay>, range: ClosedRange<LocalDate>, metric: StatsMetric): RangeSummary {
        val inRange = days.filter { day -> runCatching { LocalDate.parse(day.date) }.getOrNull()?.let { it in range } == true }
        val total = DoubleArray(7)
        val measured = IntArray(7)
        inRange.filter { it.measured }.forEach { day ->
            val index = LocalDate.parse(day.date).dayOfWeek.value - 1
            total[index] += day.value(metric)
            measured[index]++
        }
        val average = List(7) { if (measured[it] == 0) null else total[it] / measured[it] }
        val busiest = average.withIndex().filter { (it.value ?: 0.0) > 0.0 }.maxByOrNull { it.value!! }?.index
        return RangeSummary(range, inRange.sumOf { it.value(metric) }, inRange.count { it.value(metric) > 0 }, inRange.count { it.measured },
            inRange.sumOf { it.resets(metric) }, inRange.sumOf { it.runs(metric) }, inRange.filter { it.value(metric) > 0 }.maxByOrNull { it.value(metric) },
            inRange.maxOfOrNull { it.peak } ?: 0.0, total.toList(), average, busiest)
    }

    /** Longest run of consecutive days with usage that ends today or yesterday. */
    fun currentStreak(days: List<LedgerDay>, today: LocalDate, metric: StatsMetric): Int {
        val active = days.filter { it.value(metric) > 0 }.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet()
        var cursor = if (today in active) today else today.minusDays(1)
        var count = 0
        while (cursor in active) { count++; cursor = cursor.minusDays(1) }
        return count
    }
}
