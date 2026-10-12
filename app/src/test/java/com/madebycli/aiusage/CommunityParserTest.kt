package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test

class CommunityParserTest {
    @Test fun `Claude seven day estimate keeps horizon and marks unverified history`() {
        val html = """<aside><h2>Claude resets odds · next 7 days</h2><svg aria-label="Reset odds 80%"></svg></aside><section><div><h2>Claude resets history</h2></div><ol><li><time datetime="2026-09-01T00:00:00Z"></time><p>unverified</p><a href="https://x.com/example/status/1">source</a></li></ol></section>"""
        val report = CommunityParser.parse(CommunitySource.CLAUDE_RESETS, html, 10)
        assertEquals(168, report.chance.hours)
        assertEquals(80.0, report.chance.percent, 0.0)
        assertNull(report.publishedAt)
        assertEquals("Unbestätigte Meldung", report.events.single().kind)
    }
    @Test fun `Vercel reads exact provider source timestamp and past estimated date`() {
        val html = """<h1>Did Claude Reset Today?</h1><section id="prediction"><h2>Reset probability — next 48 hours</h2><span class="prob-value">42<!-- -->%</span><div class="next-reset"><time datetime="2026-10-10T23:51:34Z"></time></div></section><div class="timeline-sync-bar"><span class="sync-item">Last synced: <time datetime="2026-10-12T09:08:04Z"></time></span></div><section aria-label="Reset history"><ul><li><time datetime="2026-09-23T21:44:58Z"></time><a href="javascript:alert(1)">bad link</a></li></ul></section>"""
        val report = CommunityParser.parse(CommunitySource.VERCEL_CLAUDE, html, 10)
        assertEquals(48, report.chance.hours)
        assertNotNull(report.publishedAt)
        assertNotNull(report.expectedAt)
        assertNull(report.events.single().url)
        try { CommunityParser.parse(CommunitySource.VERCEL_CODEX, html, 10); fail("Wrong provider must fail") } catch (_: SchemaException) { }
    }
    @Test(expected = SchemaException::class) fun `changed website cannot manufacture zero odds`() { CommunityParser.parse(CommunitySource.CLAUDE_RESETS, "<h1>Error</h1>", 10) }
}
