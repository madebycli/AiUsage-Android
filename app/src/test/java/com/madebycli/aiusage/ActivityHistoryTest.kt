package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ActivityHistoryTest {
    private fun snapshot(at: Long, percent: Double, provider: String = "Codex", reset: Long? = 200000) = UsageSnapshot(null,
        listOf(QuotaGroup(provider, listOf(UsageWindow(percent, 18000, reset)))), at)
    @Test fun `actual measurements preserve daily peak and do not invent missing days`() {
        val zone = ZoneId.of("Europe/Berlin")
        val a = recordMeasurement(PersonalRecord(), snapshot(10000, 21.0), "Codex", zone)
        val b = recordMeasurement(a, snapshot(11000, 4.0), "Codex", zone)
        assertEquals(21.0, b.days.single().peak!!, 0.0)
        assertEquals(0, b.days.single().measurements)
        val next = recordMeasurement(b, snapshot(200000, 0.0), "Codex", zone)
        assertEquals(2, next.days.size)
        assertEquals(0.0, next.days.first().peak!!, 0.0)
    }
    @Test fun `twenty refreshes cannot become twenty used limits`() {
        var record = PersonalRecord()
        repeat(20) { record = recordMeasurement(record, snapshot(10000L + it, 21.0), "Codex") }
        assertEquals(21.0, record.days.single().peak!!, 0.0)
        assertEquals(0, record.days.single().measurements)
        assertNull(record.days.single().actual)
    }
    @Test fun `enrichment duplicates and old results never add phantom measurements`() {
        val a = recordMeasurement(PersonalRecord(), snapshot(10000, 21.0), "Codex")
        assertEquals(a.days, recordMeasurement(a, snapshot(10000, 21.0).copy(availableResets = 1), "Codex").days)
        assertEquals(a, recordMeasurement(a, snapshot(9999, 60.0), "Codex"))
    }
    @Test fun `missing quota remains unknown and providers do not mix`() {
        val record = recordMeasurement(PersonalRecord(), snapshot(10000, 50.0, "Claude Code"), "Codex")
        assertNull(record.days.single().peak)
        assertEquals(50.0, recordMeasurement(PersonalRecord(), snapshot(10000, 50.0, "Claude Code"), "Claude Code").days.single().peak!!, 0.0)
    }
    @Test fun `local date handles daylight saving and history is bounded`() {
        val zone = ZoneId.of("Europe/Berlin")
        val at = Instant.parse("2026-10-24T22:30:00Z").epochSecond
        assertEquals("2026-10-25", recordMeasurement(PersonalRecord(), snapshot(at, 1.0), "Codex", zone).days.single().date)
        var record = PersonalRecord()
        repeat(400) { record = recordMeasurement(record, snapshot(10000 + it * 86400L, 1.0), "Codex", ZoneId.of("UTC")) }
        assertEquals(366, record.days.size)
    }
    @Test fun `source selection never mixes tracker events into an API tab`() {
        val event = ResetEvent("a", 100, "Reset", "API", "https://x.com/a/status/1")
        val tracker = ResetEvent("b", 101, "Community", "Tracker", "https://x.com/a/status/2")
        val feed = ResetFeed(ResetSource.CODEX_RESET, listOf(event), null, 110)
        val community = mapOf(CommunitySource.VERCEL_CODEX to CommunityResult(CommunityReport(CommunitySource.VERCEL_CODEX,
            ForecastChance(48, 10.0), null, 110, null, listOf(tracker))))
        assertEquals(listOf(event), historyEvents("Codex", "api:CODEX_RESET", feed, community))
        assertTrue(historyEvents("Codex", "api:LUNARWERX", feed, community).isEmpty())
        assertEquals(listOf(tracker), historyEvents("Codex", "page:VERCEL_CODEX", feed, community))
        assertTrue(historyEvents("Claude Code", "page:VERCEL_CODEX", feed, community).isEmpty())
        assertTrue(historyEvents("Claude Code", "api:CODEX_RESET", feed, community).isEmpty())
    }
    @Test fun `one source deduplicates identical original links without merging distinct reports`() {
        val events = listOf(ResetEvent("a", 100, "Reset", "One", "https://x.com/a/status/1?utm=test"),
            ResetEvent("b", 101, "Reset", "Same", "https://x.com/a/status/1"), ResetEvent("c", 101, "Reset", "Other", null))
        assertEquals(2, historyEvents("Codex", "api:CODEX_RESET", ResetFeed(ResetSource.CODEX_RESET, events, null, 120), emptyMap()).size)
    }
    @Test fun `weekly value keeps the latest reading of the day`() {
        fun both(at: Long, five: Double, week: Double) = UsageSnapshot(null,
            listOf(QuotaGroup("Claude Code", listOf(UsageWindow(five, 18000, 1), UsageWindow(week, 604800, 2)))), at)
        val zone = ZoneId.of("UTC")
        var record = recordMeasurement(PersonalRecord(), both(10000, 30.0, 12.0), "Claude Code", zone)
        record = recordMeasurement(record, both(20000, 10.0, 14.0), "Claude Code", zone)
        assertEquals(30.0, record.days.single().peak!!, 0.0)
        assertEquals(14.0, record.days.single().weekly!!, 0.0)
    }
    @Test fun `reset times with numeric offsets parse like Z times`() {
        assertEquals(1791849600L, parseInstant("2026-10-13T00:00:00Z"))
        assertEquals(1791849600L, parseInstant("2026-10-13T00:00:00+00:00"))
        assertEquals(1791849600L, parseInstant("2026-10-13T02:00:00.000000+02:00"))
        assertEquals(1791849600L, parseInstant("2026-10-12T23:59:59.8+00:00"))
        assertNull(parseInstant("tomorrow"))
        assertNull(parseInstant(null))
    }
}
