package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test

class ResetHistoryTest {
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

    @Test fun `reset times with numeric offsets parse like Z times`() {
        assertEquals(1791849600L, parseInstant("2026-10-13T00:00:00Z"))
        assertEquals(1791849600L, parseInstant("2026-10-13T00:00:00+00:00"))
        assertEquals(1791849600L, parseInstant("2026-10-13T02:00:00.000000+02:00"))
        assertEquals(1791849600L, parseInstant("2026-10-12T23:59:59.8+00:00"))
        assertNull(parseInstant("tomorrow"))
        assertNull(parseInstant(null))
    }
}
