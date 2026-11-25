package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Usage bookkeeping that works on *cycles*, not on readings.
 *
 * A cycle is one reset period of a quota window, identified by the reset time the provider reports. Reading the same
 * cycle again, however often, never adds usage twice: a cycle keeps only its highest value. A new cycle starts when the
 * provider reports a different reset time, or when the previous reset time has passed.
 *
 * Every 5-hour cycle is stored on its own ([UsageLedger.fiveCycles]) and counted on the local day it started
 * (reset time minus five hours). Weekly usage is the rise of the weekly value between two readings of the same weekly
 * cycle, counted on the day of the later reading.
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

/** One finished or running cycle of a window. */
@Serializable
data class CycleRecord(
    /** Reset time as reported; the key of the cycle. */
    val end: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    val peak: Double,
    val samples: Int = 1,
    /** When the cycle ended, null while it runs. */
    val closedAt: Long? = null,
    /** Ended before its reset time: global reset, redeemed credit. */
    val early: Boolean = false,
    /** False when the provider gave no reset time and [end] is an assumption. */
    val endKnown: Boolean = true,
    /** Part of [peak] that the day totals of 0.9 already contain. Only the rest is counted again. */
    val baseline: Double = 0.0,
    /** Cycle that was running when 0.9 data was taken over; its start was already counted then. */
    val migrated: Boolean = false
) {
    fun start(seconds: Long) = end - seconds
    /**
     * The final value is known: the last reading was at most 15 minutes before the end, or, for an early reset,
     * at most 15 minutes before the reset was noticed.
     */
    val complete get() = lastSeen >= end - COMPLETE_SECONDS || (early && closedAt != null && lastSeen >= closedAt - COMPLETE_SECONDS)
    val counted get() = (peak - baseline).coerceAtLeast(0.0)

    companion object { const val COMPLETE_SECONDS = 15 * 60L }
}

/** Measured pairs of 5-hour and weekly increments inside the same cycles: how many weekly points a full 5-hour limit costs. */
@Serializable
data class Calibration(val five: Double = 0.0, val week: Double = 0.0) {
    /** Weekly points per 100 five-hour points, once enough was measured. */
    val weekPerLimit: Double? get() = if (five >= MIN_FIVE && week >= MIN_WEEK) week / (five / 100) else null
    fun add(fiveDelta: Double, weekDelta: Double) = Calibration(five + fiveDelta, week + weekDelta)

    companion object { const val MIN_FIVE = 150.0; const val MIN_WEEK = 3.0 }
}

/**
 * Everything known about one calendar day (local time) apart from the 5-hour cycles.
 * [five] only holds 0.9 data; 5-hour usage from 2.0 on is derived from the cycles.
 */
@Serializable
data class LedgerDay(
    val date: String,
    /** 0.9: 5-hour points read on this day. */
    val five: Double = 0.0,
    /** Weekly points consumed on this day. */
    val week: Double = 0.0,
    /** 0.9: 5-hour runs first seen on this day. */
    val fiveRuns: Int = 0,
    val weekRuns: Int = 0,
    val fiveResets: Int = 0,
    val weekResets: Int = 0,
    /** 0.9: highest 5-hour percentage of the day. */
    val peak: Double = 0.0,
    /** Readings on this day. 0 means nobody looked, so the value is unknown. */
    val reads: Int = 0,
    /** Carried over from the peak-only history before 0.9; a lower bound. */
    val legacy: Boolean = false,
    /** Weekly points that rose across a 5-hour cycle boundary the app did not see to its end. */
    val gapWeek: Double = 0.0,
    /** 5-hour points seen in those same intervals. */
    val gapFive: Double = 0.0
) {
    val measured get() = reads > 0 || legacy || five > 0 || week > 0
}

enum class StatsMetric(val seconds: Long) { FIVE(FIVE_HOURS), WEEK(ONE_WEEK) }

@Serializable
data class UsageLedger(
    /** Newest first. */
    val days: List<LedgerDay> = emptyList(),
    val fiveRun: LedgerRun? = null,
    val weekRun: LedgerRun? = null,
    val lastReadAt: Long = 0,
    /** 2 from 2.0 on: 5-hour usage lives in [fiveCycles]. */
    val version: Int = 1,
    /** Newest first; the running cycle, if any, is the first entry without [CycleRecord.closedAt]. */
    val fiveCycles: List<CycleRecord> = emptyList(),
    val weekCycles: List<CycleRecord> = emptyList(),
    val calibration: Calibration = Calibration()
) {
    fun run(metric: StatsMetric) = if (metric == StatsMetric.FIVE) fiveRun else weekRun
    fun cycles(metric: StatsMetric) = if (metric == StatsMetric.FIVE) fiveCycles else weekCycles
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
    const val MAX_FIVE_CYCLES = 2000
    const val MAX_WEEK_CYCLES = 160
    const val WEEK_CAP = 99.0

    private class Closed(val at: Long, val early: Boolean, val peak: Double, val endsAt: Long)
    private class Outcome(var run: LedgerRun?, var added: Double = 0.0, var started: Boolean = false,
        val resets: MutableList<Closed> = mutableListOf(), var present: Boolean = false, var endKnown: Boolean = true)

    /** Folds one reading of one window into its run. [window] is null when the provider reports none. */
    private fun observe(window: UsageWindow?, current: LedgerRun?, at: Long, seconds: Long): Outcome {
        val outcome = Outcome(current)
        val percent = window?.usedPercent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
        val reported = window?.resetAt
        outcome.present = percent != null
        outcome.endKnown = reported != null
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
            } else if (sameEnd && reported != null) run = run.copy(endsAt = reported)
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

    /** Applies what [observe] found to the stored cycle list. */
    private fun cycles(list: List<CycleRecord>, outcome: Outcome, at: Long, max: Int): List<CycleRecord> {
        val result = list.toMutableList()
        outcome.resets.forEach { closed ->
            val index = result.indexOfFirst { it.closedAt == null && abs(it.end - closed.endsAt) <= RESET_TOLERANCE_SECONDS }
            if (index >= 0) result[index] = result[index].copy(closedAt = closed.at, early = closed.early)
        }
        val run = outcome.run
        if (run != null) {
            val index = result.indexOfFirst { it.closedAt == null && abs(it.end - run.endsAt) <= RESET_TOLERANCE_SECONDS }
            if (index >= 0) {
                val old = result[index]
                result[index] = old.copy(end = run.endsAt, peak = maxOf(old.peak, run.peak), lastSeen = maxOf(old.lastSeen, at),
                    samples = old.samples + if (outcome.present) 1 else 0)
            } else if (outcome.started) {
                result.add(0, CycleRecord(run.endsAt, run.firstSeenAt, at, run.peak, endKnown = outcome.endKnown))
            }
        }
        // Anything still marked open that is not the current run has ended without being noticed (should not happen).
        val openEnd = run?.endsAt
        return result.map { if (it.closedAt == null && (openEnd == null || abs(it.end - openEnd) > RESET_TOLERANCE_SECONDS)) it.copy(closedAt = minOf(it.end, at)) else it }
            .sortedByDescending { it.end }.take(max)
    }

    fun record(ledger: UsageLedger, snapshot: UsageSnapshot, provider: String, zone: ZoneId = ZoneId.systemDefault()): UsageLedger =
        update(ledger, snapshot, provider, zone).ledger

    fun update(ledger: UsageLedger, snapshot: UsageSnapshot, provider: String, zone: ZoneId = ZoneId.systemDefault()): LedgerUpdate {
        val base = upgraded(ledger)
        val at = snapshot.fetchedAt
        // Older or repeated readings (enrichment of the same read, racing workers) add nothing.
        if (at <= base.lastReadAt) return LedgerUpdate(base, emptyList())
        fun day(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate().toString()
        val windows = snapshot.windows(provider)
        val five = observe(windows.firstOrNull { it.seconds == FIVE_HOURS }, base.fiveRun, at, FIVE_HOURS)
        val week = observe(windows.firstOrNull { it.seconds == ONE_WEEK }, base.weekRun, at, ONE_WEEK)
        val today = day(at)
        val byDate = base.days.associateByTo(linkedMapOf()) { it.date }
        fun edit(date: String, change: (LedgerDay) -> LedgerDay) { byDate[date] = change(byDate[date] ?: LedgerDay(date)) }

        val fiveContinued = base.fiveRun != null && five.resets.isEmpty() && !five.started && five.present
        val weekContinued = base.weekRun != null && week.resets.isEmpty() && !week.started && week.present
        // A weekly value at its cap no longer rises with use; such readings say nothing about the ratio of the two windows.
        val weekCapped = (week.run?.peak ?: 0.0) >= WEEK_CAP
        var calibration = base.calibration
        // Both windows inside the same cycles: their increments belong together.
        if (fiveContinued && weekContinued && !weekCapped) calibration = calibration.add(five.added, week.added)
        // Weekly points may hide unseen 5-hour cycles only when a cycle ended without its end being read, or when no cycle was
        // known since a reading long enough ago. A cycle change read just before the old end and after the new start hides nothing.
        val previousFive = base.fiveCycles.firstOrNull { it.closedAt == null }
        val lostEnd = five.resets.isNotEmpty() && (previousFive == null || previousFive.lastSeen < previousFive.end - CycleRecord.COMPLETE_SECONDS)
        val unseenStart = base.fiveRun == null && base.lastReadAt > 0 && at - base.lastReadAt > CycleRecord.COMPLETE_SECONDS
        val gap = weekContinued && !weekCapped && (lostEnd || unseenStart)

        edit(today) { it.copy(reads = it.reads + 1, week = it.week + week.added, weekRuns = it.weekRuns + if (week.started) 1 else 0,
            gapWeek = it.gapWeek + if (gap) week.added else 0.0, gapFive = it.gapFive + if (gap) five.added else 0.0) }
        five.resets.forEach { closed -> edit(day(closed.at)) { it.copy(fiveResets = it.fiveResets + 1) } }
        week.resets.forEach { closed -> edit(day(closed.at)) { it.copy(weekResets = it.weekResets + 1) } }
        val events = five.resets.map { LedgerEvent(StatsMetric.FIVE, it.at, it.early, it.peak, it.endsAt) } +
            week.resets.map { LedgerEvent(StatsMetric.WEEK, it.at, it.early, it.peak, it.endsAt) }
        val next = UsageLedger(byDate.values.sortedByDescending { it.date }.take(MAX_DAYS), five.run, week.run, at, 2,
            cycles(base.fiveCycles, five, at, MAX_FIVE_CYCLES), cycles(base.weekCycles, week, at, MAX_WEEK_CYCLES), calibration)
        return LedgerUpdate(next, events)
    }

    /** 0.9 ledgers: day totals stay as they are, the running cycles are taken over with what was already counted. */
    fun upgraded(ledger: UsageLedger): UsageLedger {
        if (ledger.version >= 2) return ledger
        fun open(run: LedgerRun?) = run?.let { listOf(CycleRecord(it.endsAt, it.firstSeenAt, it.lastSeenAt, it.peak, baseline = it.peak, migrated = true)) }.orEmpty()
        return ledger.copy(version = 2, fiveCycles = open(ledger.fiveRun), weekCycles = open(ledger.weekRun))
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

/**
 * One day as the statistics show it. 5-hour values are percentage points: 100 are one full limit.
 * [fiveOpen] are points of cycles whose end the app did not see, so the true value may be higher.
 * [fiveEstimate] are points that the weekly value shows but no seen cycle explains; null without calibration.
 */
data class DayUsage(
    val date: LocalDate,
    val five: Double,
    val fiveOpen: Double,
    val fiveEstimate: Double?,
    val week: Double,
    val fiveCycles: Int,
    val weekCycles: Int,
    val fiveResets: Int,
    val weekResets: Int,
    val peak: Double,
    val reads: Int,
    val legacy: Boolean
) {
    val measured get() = reads > 0 || legacy || five > 0 || week > 0
    fun value(metric: StatsMetric) = if (metric == StatsMetric.FIVE) five else week
    fun resets(metric: StatsMetric) = if (metric == StatsMetric.FIVE) fiveResets else weekResets
    fun cycles(metric: StatsMetric) = if (metric == StatsMetric.FIVE) fiveCycles else weekCycles
}

/** A cycle as listed under a day, with its account. */
data class CycleView(val accountId: String, val metric: StatsMetric, val start: Long, val record: CycleRecord)

data class RangeSummary(
    val range: ClosedRange<LocalDate>,
    val total: Double,
    val open: Double,
    val estimate: Double?,
    val activeDays: Int,
    val measuredDays: Int,
    val resets: Int,
    val cycles: Int,
    val bestDay: DayUsage?,
    val peak: Double,
    /** Total consumed per weekday, Monday first. */
    val weekdayTotal: List<Double>,
    /** Average per measured day, Monday first; null where no day was measured. */
    val weekdayAverage: List<Double?>,
    val busiestWeekday: Int?,
    /** 5-hour cycles started per hour of the day, 0 to 23. */
    val startHours: List<Int>
) {
    val averagePerActiveDay get() = if (activeDays == 0) 0.0 else total / activeDays
}

object LedgerStats {
    /** Smallest unexplained amount per day, in 5-hour points, that counts as an estimate. */
    const val MIN_ESTIMATE = 25.0
    private fun localDate(epoch: Long, zone: ZoneId) = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()

    /** Every day of one ledger. */
    fun days(ledger: UsageLedger, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, DayUsage> {
        val upgraded = UsageTracker.upgraded(ledger)
        val k = upgraded.calibration.weekPerLimit
        val result = HashMap<LocalDate, DayUsage>()
        upgraded.days.forEach { day ->
            val date = runCatching { LocalDate.parse(day.date) }.getOrNull() ?: return@forEach
            // Below a quarter limit the difference is rounding of the reported percentages, not unseen use.
            val estimate = k?.let { ((day.gapWeek / it) * 100 - day.gapFive).takeIf { e -> e >= MIN_ESTIMATE } ?: 0.0 }
            result[date] = DayUsage(date, day.five, 0.0, estimate, day.week, day.fiveRuns, day.weekRuns, day.fiveResets, day.weekResets,
                day.peak, day.reads, day.legacy)
        }
        upgraded.fiveCycles.forEach { cycle ->
            val date = localDate(cycle.start(FIVE_HOURS), zone)
            val old = result[date] ?: DayUsage(date, 0.0, 0.0, k?.let { 0.0 }, 0.0, 0, 0, 0, 0, 0.0, 0, false)
            // A running cycle is not missing anything yet; only ended cycles without a read end are lower bounds.
            val open = cycle.closedAt != null && !cycle.complete
            result[date] = old.copy(five = old.five + cycle.counted, fiveOpen = old.fiveOpen + if (open) cycle.counted else 0.0,
                fiveCycles = old.fiveCycles + if (cycle.migrated) 0 else 1, peak = maxOf(old.peak, cycle.peak))
        }
        return result
    }

    /** Adds up the same dates of several accounts. */
    fun merge(ledgers: List<UsageLedger>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, DayUsage> {
        val all = ledgers.map { days(it, zone) }
        if (all.size == 1) return all.single()
        val result = HashMap<LocalDate, DayUsage>()
        all.forEach { map -> map.forEach { (date, day) ->
            val old = result[date]
            result[date] = if (old == null) day else DayUsage(date, old.five + day.five, old.fiveOpen + day.fiveOpen,
                if (old.fiveEstimate == null && day.fiveEstimate == null) null else (old.fiveEstimate ?: 0.0) + (day.fiveEstimate ?: 0.0),
                old.week + day.week, old.fiveCycles + day.fiveCycles, old.weekCycles + day.weekCycles, old.fiveResets + day.fiveResets,
                old.weekResets + day.weekResets, maxOf(old.peak, day.peak), old.reads + day.reads, old.legacy && day.legacy)
        } }
        return result
    }

    /** The 5-hour or weekly cycles that started on [date], newest first. */
    fun cyclesOn(ledgers: Map<String, UsageLedger>, date: LocalDate, metric: StatsMetric, zone: ZoneId = ZoneId.systemDefault()): List<CycleView> =
        ledgers.flatMap { (id, ledger) ->
            UsageTracker.upgraded(ledger).cycles(metric).filter { localDate(it.start(metric.seconds), zone) == date && it.peak > 0 }
                .map { CycleView(id, metric, it.start(metric.seconds), it) }
        }.sortedByDescending { it.start }

    fun summarize(days: Map<LocalDate, DayUsage>, range: ClosedRange<LocalDate>, metric: StatsMetric, cycleStarts: List<Long> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault()): RangeSummary {
        val inRange = days.values.filter { it.date in range }
        val total = DoubleArray(7)
        val measured = IntArray(7)
        inRange.filter { it.measured }.forEach { day ->
            val index = day.date.dayOfWeek.value - 1
            total[index] += day.value(metric)
            measured[index]++
        }
        val average = List(7) { if (measured[it] == 0) null else total[it] / measured[it] }
        val busiest = average.withIndex().filter { (it.value ?: 0.0) > 0.0 }.maxByOrNull { it.value!! }?.index
        val hours = IntArray(24)
        cycleStarts.forEach { start ->
            val time = Instant.ofEpochSecond(start).atZone(zone)
            if (time.toLocalDate() in range) hours[time.hour]++
        }
        val estimates = inRange.mapNotNull { it.fiveEstimate }
        return RangeSummary(range, inRange.sumOf { it.value(metric) },
            if (metric == StatsMetric.FIVE) inRange.sumOf { it.fiveOpen } else 0.0,
            if (metric == StatsMetric.FIVE && estimates.isNotEmpty()) estimates.sum() else null,
            inRange.count { it.value(metric) > 0 }, inRange.count { it.measured },
            inRange.sumOf { it.resets(metric) }, inRange.sumOf { it.cycles(metric) }, inRange.filter { it.value(metric) > 0 }.maxByOrNull { it.value(metric) },
            inRange.maxOfOrNull { it.peak } ?: 0.0, total.toList(), average, busiest, hours.toList())
    }

    /** Longest run of consecutive days with usage that ends today or yesterday. */
    fun currentStreak(days: Map<LocalDate, DayUsage>, today: LocalDate, metric: StatsMetric): Int {
        var cursor = if ((days[today]?.value(metric) ?: 0.0) > 0) today else today.minusDays(1)
        var count = 0
        while ((days[cursor]?.value(metric) ?: 0.0) > 0) { count++; cursor = cursor.minusDays(1) }
        return count
    }
}
