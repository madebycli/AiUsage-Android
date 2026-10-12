package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test

class ResetParserTest {
    @Test fun `teasers and banked grants never become global resets`() {
        val feed = ResetParser.parse(ResetSource.CODEX_RESET, """{"updated_at":"2026-10-12T10:00:00Z","events":[
            {"id":"tease","group":"reset","announcement_state":"none","announced_at":"2026-10-12T09:00:00Z"},
            {"id":"banked","group":"credits","banked_state":"available","announced_at":"2026-10-11T10:00:00Z"},
            {"id":"reset","group":"reset","announcement_state":"announced","announced_at":"2026-10-10T10:00:00Z","url":"javascript:alert(1)"}]}""", 100)
        assertEquals(2, feed.events.size)
        assertTrue(feed.events.first().kind.startsWith("Banked"))
        assertEquals("Globaler Reset", feed.events.last().kind)
        assertNull(feed.events.last().url)
    }
    @Test fun `lunar ledger is not falsely classified as global`() {
        val feed = ResetParser.parse(ResetSource.LUNARWERX, """{"generatedAt":"2026-10-12T10:00:00Z","resets":[{"at":"2026-10-11T10:00:00Z","announcement":{"text":"Banked grant","url":"https://x.com/a/status/1"}}]}""", 100)
        assertEquals("Ledger-Ereignis", feed.events.single().kind)
        assertNotNull(feed.publishedAt)
    }
    @Test fun `malformed feed fails rather than clearing history`() {
        assertThrows(Exception::class.java) { ResetParser.parse(ResetSource.CODEX_RESET, "{}", 100) }
    }
}
