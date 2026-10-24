package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class UsageLedgerTest {
    private val utc = ZoneOffset.UTC
    private fun at(text: String) = Instant.parse(text).epochSecond
    private fun hours(n: Long) = n * 3600

    private fun snap(at: Long, five: Pair<Double, Long?>? = null, week: Pair<Double, Long?>? = null, provider: String = "Codex"): UsageSnapshot {
        val windows = listOfNotNull(five?.let { UsageWindow(it.first, FIVE_HOURS, it.second) }, week?.let { UsageWindow(it.first, ONE_WEEK, it.second) })
        return UsageSnapshot(null, listOf(QuotaGroup(provider, windows)), at)
    }

    private fun feed(vararg readings: UsageSnapshot, provider: String = "Codex", zone: ZoneId = utc): UsageLedger =
        readings.fold(UsageLedger()) { ledger, reading -> UsageTracker.record(ledger, reading, provider, zone) }

    private fun UsageLedger.day(date: String) = days.first { it.date == date }

    // ---- the scenarios from the bug report ------------------------------------------------------------

    @Test fun `reading the same run again and again counts it once`() {
        val end = at("2026-10-20T15:00:00Z")
        val ledger = feed(
            snap(at("2026-10-20T10:20:00Z"), five = 99.0 to end), snap(at("2026-10-20T11:20:00Z"), five = 99.0 to end),
            snap(at("2026-10-20T12:20:00Z"), five = 99.0 to end), snap(at("2026-10-20T13:20:00Z"), five = 99.0 to end),
            snap(at("2026-10-20T14:20:00Z"), five = 99.0 to end))
        val day = ledger.day("2026-10-20")
        assertEquals(99.0, day.five, 0.0001)
        assertEquals(1, day.fiveRuns)
        assertEquals(5, day.reads)
        assertEquals(0, day.fiveResets)
    }

    @Test fun `fifty percent then eighty percent in one run is eighty and not one hundred thirty`() {
        val end = at("2026-10-20T15:00:00Z")
        val ledger = feed(snap(at("2026-10-20T10:10:00Z"), five = 50.0 to end), snap(at("2026-10-20T11:10:00Z"), five = 80.0 to end))
        assertEquals(80.0, ledger.day("2026-10-20").five, 0.0001)
        assertEquals(1, ledger.day("2026-10-20").fiveRuns)
    }

    @Test fun `a new reset cycle starts a new run and the old one is counted as reset`() {
        val first = at("2026-10-20T15:00:00Z")
        val second = at("2026-10-20T20:00:00Z")
        val ledger = feed(
            snap(at("2026-10-20T11:00:00Z"), five = 99.0 to first),
            snap(at("2026-10-20T15:30:00Z"), five = 30.0 to second))
        val day = ledger.day("2026-10-20")
        assertEquals(129.0, day.five, 0.0001)
        assertEquals(2, day.fiveRuns)
        assertEquals(1, day.fiveResets)
        assertEquals(30.0, ledger.fiveRun!!.peak, 0.0)
    }

    // ---- reset detection ------------------------------------------------------------------------------

    @Test fun `a reset that happened while the app was closed is counted on the day it happened`() {
        val end = at("2026-10-20T22:00:00Z")
        val ledger = feed(
            snap(at("2026-10-20T20:00:00Z"), five = 70.0 to end),
            snap(at("2026-10-23T09:00:00Z"), five = 0.0 to at("2026-10-23T14:00:00Z")))
        assertEquals(1, ledger.day("2026-10-20").fiveResets)
        assertEquals(0.0, ledger.day("2026-10-23").five, 0.0)
        assertNull("no usage, so no run", ledger.fiveRun)
    }

    @Test fun `an early reset with a moved deadline closes the run now`() {
        val end = at("2026-10-20T20:00:00Z")
        val ledger = feed(
            snap(at("2026-10-20T12:00:00Z"), five = 90.0 to end),
            snap(at("2026-10-20T12:30:00Z"), five = 4.0 to at("2026-10-20T17:30:00Z")))
        val day = ledger.day("2026-10-20")
        assertEquals(94.0, day.five, 0.0001)
        assertEquals(2, day.fiveRuns)
        assertEquals(1, day.fiveResets)
    }

    @Test fun `a window that disappears before its deadline is not a reset`() {
        val end = at("2026-10-20T20:00:00Z")
        val ledger = feed(snap(at("2026-10-20T12:00:00Z"), five = 40.0 to end), snap(at("2026-10-20T13:00:00Z")), snap(at("2026-10-20T14:00:00Z"), five = 45.0 to end))
        val day = ledger.day("2026-10-20")
        assertEquals(0, day.fiveResets)
        assertEquals(45.0, day.five, 0.0001)
        assertEquals(1, day.fiveRuns)
    }

    @Test fun `a window that is gone after its deadline resets at the deadline`() {
        val end = at("2026-10-20T14:00:00Z")
        val ledger = feed(snap(at("2026-10-20T12:00:00Z"), five = 40.0 to end), snap(at("2026-10-20T16:00:00Z")))
        assertEquals(1, ledger.day("2026-10-20").fiveResets)
        assertNull(ledger.fiveRun)
    }

    @Test fun `jitter in the reported deadline is the same run`() {
        val end = at("2026-10-20T15:00:00Z")
        val ledger = feed(snap(at("2026-10-20T10:00:00Z"), five = 20.0 to end), snap(at("2026-10-20T11:00:00Z"), five = 25.0 to end + 2),
            snap(at("2026-10-20T12:00:00Z"), five = 30.0 to end - 40))
        assertEquals(30.0, ledger.day("2026-10-20").five, 0.0001)
        assertEquals(1, ledger.day("2026-10-20").fiveRuns)
        assertEquals(0, ledger.day("2026-10-20").fiveResets)
    }

    @Test fun `a lower reading in the same run never reduces or double counts usage`() {
        val end = at("2026-10-20T15:00:00Z")
        val ledger = feed(snap(at("2026-10-20T10:00:00Z"), five = 80.0 to end), snap(at("2026-10-20T11:00:00Z"), five = 20.0 to end),
            snap(at("2026-10-20T12:00:00Z"), five = 90.0 to end))
        assertEquals(90.0, ledger.day("2026-10-20").five, 0.0001)
    }

    @Test fun `without a reported deadline the run continues unless the value falls`() {
        val ledger = feed(snap(at("2026-10-20T10:00:00Z"), five = 30.0 to null), snap(at("2026-10-20T11:00:00Z"), five = 45.0 to null))
        assertEquals(45.0, ledger.day("2026-10-20").five, 0.0001)
        assertEquals(1, ledger.day("2026-10-20").fiveRuns)
        val dropped = UsageTracker.record(ledger, snap(at("2026-10-20T12:00:00Z"), five = 5.0 to null), "Codex", utc)
        assertEquals(50.0, dropped.day("2026-10-20").five, 0.0001)
        assertEquals(2, dropped.day("2026-10-20").fiveRuns)
        assertEquals(1, dropped.day("2026-10-20").fiveResets)
    }

    // ---- idle windows and bad input -------------------------------------------------------------------

    @Test fun `idle windows with a rolling deadline never create usage or runs`() {
        val ledger = feed(snap(at("2026-10-20T10:00:00Z"), five = 0.0 to at("2026-10-20T15:00:00Z")), snap(at("2026-10-20T11:00:00Z"), five = 0.0 to at("2026-10-20T16:00:00Z")),
            snap(at("2026-10-20T12:00:00Z"), five = 0.0 to at("2026-10-20T17:00:00Z")))
        val day = ledger.day("2026-10-20")
        assertEquals(0.0, day.five, 0.0)
        assertEquals(0, day.fiveRuns)
        assertEquals(0, day.fiveResets)
        assertTrue("measured zero is known, not unknown", day.measured)
    }

    @Test fun `repeated and older readings change nothing`() {
        val end = at("2026-10-20T15:00:00Z")
        val a = feed(snap(at("2026-10-20T12:00:00Z"), five = 50.0 to end))
        assertSame(a, UsageTracker.record(a, snap(at("2026-10-20T12:00:00Z"), five = 70.0 to end), "Codex", utc))
        assertSame(a, UsageTracker.record(a, snap(at("2026-10-20T11:00:00Z"), five = 10.0 to end), "Codex", utc))
    }

    @Test fun `another providers windows are ignored`() {
        val ledger = feed(snap(at("2026-10-20T12:00:00Z"), five = 50.0 to at("2026-10-20T15:00:00Z"), provider = "Claude Code"), provider = "Codex")
        assertEquals(0.0, ledger.day("2026-10-20").five, 0.0)
        assertNull(ledger.fiveRun)
    }

    @Test fun `out of range percentages are clamped`() {
        val ledger = feed(snap(at("2026-10-20T12:00:00Z"), five = 100.9 to at("2026-10-20T15:00:00Z")))
        assertEquals(100.0, ledger.day("2026-10-20").five, 0.0)
    }

    // ---- days and weeks -------------------------------------------------------------------------------

    @Test fun `usage belongs to the local day of the reading`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val end = at("2026-10-24T23:50:00Z")
        val ledger = feed(snap(at("2026-10-24T21:30:00Z"), five = 20.0 to end), snap(at("2026-10-24T22:30:00Z"), five = 60.0 to end), zone = berlin)
        assertEquals(20.0, ledger.day("2026-10-24").five, 0.0001)
        assertEquals(40.0, ledger.day("2026-10-25").five, 0.0001)
    }

    @Test fun `five hour and weekly windows are tracked independently`() {
        val five = at("2026-10-20T15:00:00Z")
        val week = at("2026-10-26T00:00:00Z")
        val ledger = feed(snap(at("2026-10-20T10:00:00Z"), five = 40.0 to five, week = 10.0 to week),
            snap(at("2026-10-20T16:00:00Z"), five = 5.0 to at("2026-10-20T21:00:00Z"), week = 12.0 to week))
        val day = ledger.day("2026-10-20")
        assertEquals(45.0, day.five, 0.0001)
        assertEquals(12.0, day.week, 0.0001)
        assertEquals(1, day.weekRuns)
        assertEquals(0, day.weekResets)
    }

    @Test fun `history is bounded`() {
        var ledger = UsageLedger()
        repeat(900) { ledger = UsageTracker.record(ledger, snap(10_000L + it * 86_400L, five = 1.0 to 10_000L + it * 86_400L + 3600), "Codex", utc) }
        assertEquals(UsageTracker.MAX_DAYS, ledger.days.size)
    }

    // ---- reading side ---------------------------------------------------------------------------------

    private fun day(date: String, five: Double = 0.0, reads: Int = 1, resets: Int = 0, runs: Int = 0) = LedgerDay(date, five = five, reads = reads, fiveResets = resets, fiveRuns = runs)

    @Test fun `summary counts usage resets and active days of a range`() {
        val days = listOf(day("2026-10-19", 150.0, resets = 2, runs = 2), day("2026-10-20", 0.0), day("2026-10-21", 50.0, resets = 1, runs = 1), day("2026-11-01", 500.0))
        val summary = LedgerStats.summarize(days, LocalDate.parse("2026-10-19")..LocalDate.parse("2026-10-25"), StatsMetric.FIVE)
        assertEquals(200.0, summary.total, 0.0)
        assertEquals(2, summary.activeDays)
        assertEquals(3, summary.measuredDays)
        assertEquals(3, summary.resets)
        assertEquals("2026-10-19", summary.bestDay!!.date)
    }

    @Test fun `busiest weekday uses the average of measured days only`() {
        // Mondays: 10/19 (100) and 10/26 (not measured). Tuesday 10/20 measured with 60.
        val days = listOf(day("2026-10-19", 100.0), day("2026-10-20", 60.0), day("2026-10-21", 0.0, reads = 3))
        val summary = LedgerStats.summarize(days, LocalDate.parse("2026-10-19")..LocalDate.parse("2026-11-01"), StatsMetric.FIVE)
        assertEquals(0, summary.busiestWeekday)
        assertEquals(100.0, summary.weekdayAverage[0]!!, 0.0)
        assertNull("a weekday nobody measured stays unknown", summary.weekdayAverage[6])
        assertEquals(0.0, summary.weekdayAverage[2]!!, 0.0)
    }

    @Test fun `no usage means no busiest weekday`() {
        val summary = LedgerStats.summarize(listOf(day("2026-10-19", 0.0)), LocalDate.parse("2026-10-19")..LocalDate.parse("2026-10-25"), StatsMetric.FIVE)
        assertNull(summary.busiestWeekday)
    }

    @Test fun `accounts add up per date`() {
        val a = UsageLedger(listOf(day("2026-10-19", 40.0, resets = 1)))
        val b = UsageLedger(listOf(day("2026-10-19", 70.0, resets = 2), day("2026-10-18", 10.0)))
        val merged = LedgerStats.merge(listOf(a, b))
        assertEquals(110.0, merged.first { it.date == "2026-10-19" }.five, 0.0)
        assertEquals(3, merged.first { it.date == "2026-10-19" }.fiveResets)
        assertEquals(listOf("2026-10-19", "2026-10-18"), merged.map { it.date })
    }

    @Test fun `streak counts consecutive days with usage ending today or yesterday`() {
        val days = listOf(day("2026-10-24", 10.0), day("2026-10-23", 10.0), day("2026-10-22", 0.0), day("2026-10-21", 10.0))
        assertEquals(2, LedgerStats.currentStreak(days, LocalDate.parse("2026-10-24"), StatsMetric.FIVE))
        assertEquals(2, LedgerStats.currentStreak(days, LocalDate.parse("2026-10-25"), StatsMetric.FIVE))
        assertEquals(0, LedgerStats.currentStreak(days, LocalDate.parse("2026-10-26"), StatsMetric.FIVE))
    }

    // ---- storage --------------------------------------------------------------------------------------

    @Test fun `the old peak only history is carried over once as a lower bound`() {
        val old = PersonalRecord(days = listOf(UsageDay("2026-10-10", 80.0, 5, 12.0), UsageDay("2026-10-11", null, 5, null)))
        val migrated = old.migrated()
        assertTrue(migrated.days.isEmpty())
        assertEquals(1, migrated.ledger.days.size)
        assertEquals(80.0, migrated.ledger.days.single().five, 0.0)
        assertTrue(migrated.ledger.days.single().legacy)
        assertEquals(migrated, migrated.migrated())
    }

    @Test fun `measurements keep the newest snapshot and fold it into the ledger`() {
        val end = at("2026-10-20T15:00:00Z")
        var record = PersonalRecord()
        repeat(20) { record = recordMeasurement(record, snap(at("2026-10-20T10:00:00Z") + it, five = 21.0 to end), "Codex", utc) }
        assertEquals(21.0, record.ledger.day("2026-10-20").five, 0.0)
        assertEquals(1, record.ledger.day("2026-10-20").fiveRuns)
        val enriched = recordMeasurement(record, record.usage!!.copy(availableResets = 2), "Codex", utc)
        assertEquals(2, enriched.usage!!.availableResets)
        assertEquals(record.ledger, enriched.ledger)
        assertEquals(record, recordMeasurement(record, snap(at("2026-10-20T09:00:00Z"), five = 90.0 to end), "Codex", utc))
    }

    @Test fun `ledgers survive serialization`() {
        val end = at("2026-10-20T15:00:00Z")
        val ledger = feed(snap(at("2026-10-20T10:00:00Z"), five = 50.0 to end, week = 5.0 to end + 86_400))
        assertEquals(ledger, appJson.decodeFromString<UsageLedger>(appJson.encodeToString(UsageLedger.serializer(), ledger)))
    }
}
