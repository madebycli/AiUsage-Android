package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/** Cycle bookkeeping from 2.0 on: what a day shows must never exceed what can physically happen. */
class UsageCycleTest {
    private val utc = ZoneOffset.UTC
    private fun at(text: String) = Instant.parse(text).epochSecond
    private fun snap(at: Long, five: Pair<Double, Long?>? = null, week: Pair<Double, Long?>? = null) = UsageSnapshot(null,
        listOf(QuotaGroup("Codex", listOfNotNull(five?.let { UsageWindow(it.first, FIVE_HOURS, it.second) }, week?.let { UsageWindow(it.first, ONE_WEEK, it.second) }))), at)
    private fun feed(readings: List<UsageSnapshot>, start: UsageLedger = UsageLedger()) = readings.fold(start) { l, r -> UsageTracker.record(l, r, "Codex", utc) }
    private fun UsageLedger.on(date: String) = LedgerStats.days(this, utc)[LocalDate.parse(date)]!!

    @Test fun `twenty refreshes of a cycle at sixty percent are sixty percent once`() {
        val end = at("2026-11-20T15:00:00Z")
        val ledger = feed(List(20) { snap(at("2026-11-20T12:00:00Z") + it * 60L, five = 60.0 to end) })
        val day = ledger.on("2026-11-20")
        assertEquals(60.0, day.five, 0.0)
        assertEquals(1, day.fiveCycles)
        assertEquals(1, ledger.fiveCycles.size)
        assertEquals(20, ledger.fiveCycles.single().samples)
    }

    @Test fun `four full cycles in one day are four limits when each end is read`() {
        val readings = (0 until 4).flatMap { i ->
            val start = at("2026-11-20T00:30:00Z") + i * FIVE_HOURS
            val end = start + FIVE_HOURS
            listOf(snap(start + 1800, five = 30.0 to end), snap(end - ResetTicks_LEAD, five = 100.0 to end))
        }
        val ledger = feed(readings)
        val day = ledger.on("2026-11-20")
        assertEquals(400.0, day.five, 0.0)
        assertEquals(4, day.fiveCycles)
        assertEquals("every end was read, nothing is open", 0.0, day.fiveOpen, 0.0)
    }

    @Test fun `a cycle whose end was not read stays open and marks the day as a lower bound`() {
        val end = at("2026-11-20T15:00:00Z")
        val ledger = feed(listOf(snap(at("2026-11-20T11:00:00Z"), five = 30.0 to end), snap(at("2026-11-20T18:00:00Z"), five = 0.0 to at("2026-11-20T23:00:00Z"))))
        val day = ledger.on("2026-11-20")
        assertEquals(30.0, day.five, 0.0)
        assertEquals(30.0, day.fiveOpen, 0.0)
        assertFalse(ledger.fiveCycles.single().complete)
    }

    @Test fun `random reading orders never create overlapping cycles or more than fit in a day`() {
        val random = Random(42)
        repeat(30) {
            var t = at("2026-11-01T00:00:00Z")
            var cycleEnd: Long? = null
            var percent = 0.0
            val readings = mutableListOf<UsageSnapshot>()
            val originals = mutableListOf<UsageSnapshot>()
            while (t < at("2026-11-15T00:00:00Z")) {
                t += random.nextLong(60, 4 * 3600)
                if (cycleEnd != null && t >= cycleEnd) { cycleEnd = null; percent = 0.0 }
                if (cycleEnd == null && random.nextInt(3) == 0) cycleEnd = t + FIVE_HOURS - random.nextLong(0, 600)
                if (cycleEnd != null) percent = (percent + random.nextDouble(0.0, 30.0)).coerceAtMost(100.0)
                val reading = snap(t, five = percent to (cycleEnd ?: (t + FIVE_HOURS)))
                readings += reading
                originals += reading
                // Duplicates and stale re-reads must change nothing.
                if (random.nextInt(4) == 0) readings += reading
                if (random.nextInt(6) == 0) readings += reading.copy(fetchedAt = t - 30)
            }
            val ledger = feed(readings)
            val cycles = ledger.fiveCycles.sortedBy { it.end }
            cycles.zipWithNext().forEach { (a, b) ->
                assertTrue("cycles overlap: ${a.end} / ${b.start(FIVE_HOURS)}", b.start(FIVE_HOURS) >= a.end - FIVE_HOURS + UsageTracker.RESET_TOLERANCE_SECONDS || b.firstSeen >= (a.closedAt ?: a.end))
                assertTrue(a.closedAt != null)
            }
            LedgerStats.days(ledger, utc).values.forEach { day ->
                assertTrue("${day.date}: ${day.fiveCycles} cycles", day.fiveCycles <= 5)
                assertTrue("${day.date}: ${day.five}", day.five <= day.fiveCycles * 100.0 + 0.0001)
            }
            // Duplicates and late re-reads were dropped: the result equals the clean sequence exactly.
            assertEquals(feed(originals).fiveCycles, ledger.fiveCycles)
        }
    }

    @Test fun `weekly points rising across an unseen cycle become an estimate once calibrated`() {
        val week = at("2026-11-27T00:00:00Z")
        val readings = mutableListOf<UsageSnapshot>()
        // Two fully watched cycles: 100 five-hour points cost 10 weekly points.
        var weekly = 0.0
        listOf("2026-11-20T00:00:00Z", "2026-11-20T06:00:00Z").forEach { startText ->
            val start = at(startText); val end = start + FIVE_HOURS
            listOf(10.0, 50.0, 100.0).forEachIndexed { i, p ->
                weekly += if (i == 0) 1.0 else if (i == 1) 4.0 else 5.0
                readings += snap(start + 600 + i * 3000L, five = p to end, week = weekly to week)
            }
        }
        // Then nothing until a new cycle is seen: the weekly value jumped by 20 more points than the new cycle explains.
        weekly += 20.0 + 1.0
        readings += snap(at("2026-11-20T23:00:00Z"), five = 10.0 to at("2026-11-21T03:00:00Z"), week = weekly to week)
        val ledger = feed(readings)
        val k = ledger.calibration.weekPerLimit!!
        assertTrue("calibrated near 10 points per limit: $k", k in 9.0..11.0)
        val day = ledger.on("2026-11-20")
        assertNotNull(day.fiveEstimate)
        assertTrue("about two unseen limits: ${day.fiveEstimate}", day.fiveEstimate!! in 150.0..250.0)
        assertEquals("measured values stay as measured", 210.0, day.five, 0.0001)
    }

    @Test fun `without calibration there is no estimate`() {
        val ledger = feed(listOf(snap(at("2026-11-20T10:00:00Z"), five = 10.0 to at("2026-11-20T14:00:00Z"), week = 1.0 to at("2026-11-27T00:00:00Z"))))
        assertNull(ledger.on("2026-11-20").fiveEstimate)
    }

    @Test fun `a 0_9 ledger keeps its day totals and counts the running cycle only above what it already had`() {
        val end = at("2026-11-20T15:00:00Z")
        val old = UsageLedger(listOf(LedgerDay("2026-11-20", five = 140.0, fiveRuns = 2, reads = 5)), LedgerRun(end, at("2026-11-20T10:30:00Z"), at("2026-11-20T11:00:00Z"), 40.0),
            null, at("2026-11-20T11:00:00Z"))
        val ledger = feed(listOf(snap(at("2026-11-20T12:00:00Z"), five = 70.0 to end), snap(at("2026-11-20T12:30:00Z"), five = 70.0 to end)), old)
        val day = ledger.on("2026-11-20")
        assertEquals(170.0, day.five, 0.0001)
        assertEquals("the migrated cycle was already counted as a run", 2, day.fiveCycles)
        assertEquals(2, ledger.version)
    }

    @Test fun `cycles of a day are listed with their account`() {
        val ledger = feed(listOf(snap(at("2026-11-20T10:00:00Z"), five = 25.0 to at("2026-11-20T14:00:00Z"))))
        val list = LedgerStats.cyclesOn(mapOf("a" to ledger), LocalDate.parse("2026-11-20"), StatsMetric.FIVE, utc)
        assertEquals(1, list.size)
        assertEquals("a", list.single().accountId)
        assertEquals(at("2026-11-20T09:00:00Z"), list.single().start)
    }

    private companion object { const val ResetTicks_LEAD = 180L }
}
