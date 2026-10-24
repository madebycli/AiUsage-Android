package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** The fixtures are real answers of the sites, trimmed to what the app reads. */
class IntelTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("intel/$name")!!.readBytes().toString(Charsets.UTF_8)
    private fun at(text: String) = Instant.parse(text).epochSecond
    private val now = at("2026-10-24T11:20:00Z")

    // ---- codex-reset.com ------------------------------------------------------------------------------

    @Test fun `codex-reset forecast keeps ranges rhythm and the usual hours of the day`() {
        val report = IntelParser.codexResetForecast(fixture("codex_reset_forecast.json"), now)
        assertEquals(listOf(24, 48), report.chances.map { it.hours })
        assertEquals(39.0, report.chances[0].percent, 0.0)
        assertEquals(21.3, report.chances[0].low!!, 0.01)
        assertEquals(59.9, report.chances[0].high!!, 0.01)
        assertEquals(63.0, report.chances[1].percent, 0.0)
        assertEquals(at("2026-10-23T17:40:51Z"), report.lastResetAt)
        assertEquals("rounded to the nearest second", at("2026-10-24T11:11:52Z"), report.publishedAt)
        assertEquals(2.4, report.rhythm!!.medianDays, 0.001)
        assertEquals(4.3, report.rhythm!!.longestDays, 0.001)
        assertEquals(DayWindow(3, 6, "UTC"), report.window)
        assertTrue(report.hints.single().startsWith("Tibo, last 7 days"))
    }

    @Test fun `codex-reset timeline drops teasers and cleans the text`() {
        val body = fixture("codex_reset_timeline.json")
        val entries = IntelParser.codexResetTimeline(body)
        assertTrue(entries.isNotEmpty())
        assertEquals(at("2026-10-23T17:40:51Z"), entries.first().at)
        assertEquals(entries.sortedByDescending { it.at }, entries)
        entries.forEach {
            assertFalse("no raw entities: ${it.title}", it.title.contains("&amp;") || it.title.contains("&gt;"))
            assertFalse("no short links: ${it.title}", it.title.contains("t.co/"))
            assertFalse(it.title.contains('\n'))
            assertTrue(it.url == null || it.url.startsWith("https://"))
        }
        // "Day 18/ Decisions API is GA" is a side remark (state none), not a reset.
        assertTrue(entries.none { it.title.startsWith("Day 18/") })
        assertTrue(entries.any { it.kind == ResetKind.BANKED })
        assertTrue(entries.any { it.kind == ResetKind.GLOBAL })
    }

    @Test fun `a recent teaser is offered as a hint but only while it is recent`() {
        val body = fixture("codex_reset_timeline.json")
        assertTrue(IntelParser.codexResetTeaser(body, now)!!.startsWith("Day 18/"))
        assertNull(IntelParser.codexResetTeaser(body, now + 3 * 86_400))
    }

    @Test fun `unknown forecast shapes fail instead of producing numbers`() {
        assertThrows(SchemaException::class.java) { IntelParser.codexResetForecast("""{"probabilities":{"rounded_24h":150,"rounded_48h":10}}""", now) }
        assertThrows(SchemaException::class.java) { IntelParser.codexResetForecast("""{"probabilities":null}""", now) }
        assertThrows(SchemaException::class.java) { IntelParser.codexResetTimeline("""{"events":"none"}""") }
    }

    // ---- LunarWerx ------------------------------------------------------------------------------------

    @Test fun `LunarWerx forecast keeps its own horizons and ranges as percent`() {
        val report = IntelParser.lunarForecast(fixture("lunarwerx_forecast.json"), now)
        assertEquals(listOf(6, 24), report.chances.map { it.hours })
        assertEquals(4.7, report.chances[0].percent, 0.001)
        assertEquals(2.0, report.chances[0].low!!, 0.001)
        assertEquals(11.0, report.chances[0].high!!, 0.001)
        assertEquals(25.8, report.chances[1].percent, 0.001)
        assertEquals(at("2026-10-23T17:42:26Z"), report.lastResetAt)
        assertTrue(report.headline!!.startsWith("Not right now"))
    }

    @Test fun `LunarWerx scale mistakes are rejected`() {
        assertThrows(SchemaException::class.java) { IntelParser.lunarForecast("""{"nearTerm":{"hours":6,"probability":32,"low":0,"high":1},"next24Hours":{"probability":0.2,"low":0.1,"high":0.3}}""", now) }
        assertThrows(SchemaException::class.java) { IntelParser.lunarForecast("""{"nearTerm":{"hours":6,"probability":0.5,"low":0.6,"high":0.9},"next24Hours":{"probability":0.2,"low":0.1,"high":0.3}}""", now) }
    }

    @Test fun `LunarWerx history keeps announcement texts and never claims a kind`() {
        val entries = IntelParser.lunarResets(fixture("lunarwerx_resets.json"))
        assertEquals(20, entries.size)
        assertTrue(entries.all { it.kind == ResetKind.EVENT })
        assertEquals(at("2026-10-23T17:42:26Z"), entries.first().at)
        assertTrue(entries.any { it.title.startsWith("Global reset landing by EOD") && it.url!!.startsWith("https://x.com/") })
    }

    // ---- trackers and claude-resets.net ---------------------------------------------------------------

    @Test fun `tracker reads probability expected reset and publication window`() {
        val report = IntelParser.tracker(IntelSource.CODEX_TRACKER, fixture("tracker_codex.html"), now)
        assertEquals(Chance(48, 7.0), report.chances.single())
        assertEquals(at("2026-10-28T17:20:58Z"), report.expectedAt)
        assertEquals(at("2026-10-24T09:47:44Z"), report.publishedAt)
        assertEquals(at("2026-10-24T11:47:44Z"), report.validUntil)
        assertTrue(report.entries.isNotEmpty())
        assertTrue(report.entries.all { it.kind == ResetKind.EVENT && it.timeKnown })
        val claude = IntelParser.tracker(IntelSource.CLAUDE_TRACKER, fixture("tracker_claude.html"), now)
        assertEquals(42.0, claude.chances.single().percent, 0.0)
        assertNull("\"Awaiting update\" is no time", claude.expectedAt)
        assertThrows(SchemaException::class.java) { IntelParser.tracker(IntelSource.CODEX_TRACKER, fixture("tracker_claude.html"), now) }
    }

    @Test fun `claude-resets keeps title scope plans and reason and knows only the day`() {
        val report = IntelParser.claudeResets(fixture("claude_resets.html"), now)
        assertEquals(Chance(168, 80.0), report.chances.single())
        assertEquals(6, report.entries.size)
        assertEquals(at("2026-09-22T00:00:00Z"), report.lastResetAt)
        assertTrue(report.entries.none { it.timeKnown })
        val card = report.entries.first()
        assertEquals(ResetKind.BANKED, card.kind)
        assertTrue(card.title.contains("Reset for free"))
        assertTrue(card.detail!!.contains("Opus 5.5 launch"))
        val unverified = report.entries.first { it.at == at("2026-09-01T00:00:00Z") }
        assertFalse(unverified.verified)
        assertEquals("Limits reset for all users", unverified.title)
        assertEquals("5 Std. und Woche · alle Tarife · Fable 5.1 launch", unverified.detail)
        assertEquals(ResetKind.GLOBAL, unverified.kind)
        assertEquals(ResetKind.PARTIAL, report.entries.first { it.at == at("2026-09-04T00:00:00Z") }.kind)
        assertThrows(SchemaException::class.java) { IntelParser.claudeResets("<h1>Error</h1>", now) }
    }

    @Test fun `page text is cleaned`() {
        assertEquals("Codex & work are live.", IntelParser.clean("Codex &amp; work\n are   live. https://t.co/abc123"))
        assertNull(IntelParser.clean("  https://t.co/abc  "))
        assertEquals(240, IntelParser.clean("x".repeat(400))!!.length)
    }

    // ---- the combined picture -------------------------------------------------------------------------

    private fun results(): Map<IntelSource, IntelResult> {
        val timeline = IntelParser.codexResetTimeline(fixture("codex_reset_timeline.json"))
        return mapOf(
            IntelSource.CODEX_RESET to IntelResult(IntelParser.codexResetForecast(fixture("codex_reset_forecast.json"), now).withHistory(timeline)),
            IntelSource.LUNARWERX to IntelResult(IntelParser.lunarForecast(fixture("lunarwerx_forecast.json"), now).withHistory(IntelParser.lunarResets(fixture("lunarwerx_resets.json")))),
            IntelSource.CODEX_TRACKER to IntelResult(IntelParser.tracker(IntelSource.CODEX_TRACKER, fixture("tracker_codex.html"), now)))
    }

    @Test fun `sources are listed side by side and disagreement is flagged instead of averaged`() {
        val outlook = ResetOutlookBuilder.build("Codex", results(), now)
        assertEquals(listOf(6, 24, 48), outlook.horizons.map { it.hours })
        val day = outlook.horizons.first { it.hours == 24 }
        assertEquals(listOf(IntelSource.CODEX_RESET, IntelSource.LUNARWERX), day.rows.map { it.source })
        assertFalse(day.disagree)
        val two = outlook.horizons.first { it.hours == 48 }
        assertEquals(listOf(63.0, 7.0), two.rows.map { it.chance.percent })
        assertEquals(56.0, two.spread!!, 0.001)
        assertTrue("63 % against 7 % must not look like a consensus", two.disagree)
        assertNull("one source alone has no spread", outlook.horizons.first { it.hours == 6 }.spread)
    }

    @Test fun `stale and failed sources stay visible but no longer count`() {
        val stale = ResetOutlookBuilder.build("Codex", results(), now + 4 * 3600)
        assertTrue(stale.horizons.flatMap { it.rows }.none { it.fresh })
        assertTrue(stale.sources.filter { it.state != SourceState.EMPTY }.all { it.state == SourceState.STALE })
        val failed = results().toMutableMap().apply { put(IntelSource.LUNARWERX, IntelResult(this[IntelSource.LUNARWERX]!!.report, "Offline")) }
        val outlook = ResetOutlookBuilder.build("Codex", failed, now)
        assertFalse(outlook.horizons.first { it.hours == 24 }.rows.first { it.source == IntelSource.LUNARWERX }.fresh)
        assertEquals(SourceState.ERROR, outlook.sources.first { it.source == IntelSource.LUNARWERX }.state)
        assertEquals(SourceState.EMPTY, ResetOutlookBuilder.build("Claude Code", emptyMap(), now).sources.first().state)
    }

    @Test fun `a tracker is current until its own next sync plus an hour`() {
        val report = IntelParser.tracker(IntelSource.CODEX_TRACKER, fixture("tracker_codex.html"), now)
        assertTrue(ResetOutlookBuilder.isFresh(report.copy(fetchedAt = at("2026-10-24T12:20:00Z")), at("2026-10-24T12:30:00Z")))
        assertFalse(ResetOutlookBuilder.isFresh(report.copy(fetchedAt = at("2026-10-24T13:40:00Z")), at("2026-10-24T13:45:00Z")))
    }

    @Test fun `the same reset from several sources is one row with the best information`() {
        val outlook = ResetOutlookBuilder.build("Codex", results(), now)
        val resets = outlook.resets
        val dayOfLast = resets.filter { it.at in at("2026-10-23T12:00:00Z")..at("2026-10-23T23:00:00Z") }
        assertEquals("codex-reset.com (17:40) and LunarWerx (17:42) report one reset", 1, dayOfLast.size)
        assertEquals("headline and row name the same moment", resets.first().at, outlook.lastResetAt)
        assertEquals(at("2026-10-23T17:40:51Z"), outlook.lastResetAt)
        assertTrue(outlook.lastResetTimeKnown)
        assertEquals(resets.sortedByDescending { it.at }, resets)
    }

    @Test fun `a day only entry joins the exact one of the same day and keeps the richer text`() {
        val exact = ResetEntry(at("2026-09-22T16:44:06Z"), true, ResetKind.EVENT, "Reset gemeldet", null, "https://x.com/a/status/1", true, IntelSource.CLAUDE_TRACKER)
        val dayOnly = ResetEntry(at("2026-09-22T00:00:00Z"), false, ResetKind.BANKED, "“Reset for free” card", "ein gespeicherter Reset", null, true, IntelSource.CLAUDE_RESETS)
        val other = ResetEntry(at("2026-09-04T00:00:00Z"), false, ResetKind.PARTIAL, "Weekly limits reset for Max", null, null, true, IntelSource.CLAUDE_RESETS)
        val merged = ResetOutlookBuilder.mergeTimeline(listOf(exact, dayOnly, other))
        assertEquals(2, merged.size)
        assertEquals("“Reset for free” card", merged.first().title)
        assertEquals(ResetKind.BANKED, merged.first().kind)
        assertEquals(at("2026-09-22T16:44:06Z"), merged.first().at)
        assertTrue(merged.first().timeKnown)
        assertEquals("https://x.com/a/status/1", merged.first().url)
    }

    @Test fun `rhythm needs three gaps and ignores saved credits`() {
        fun entry(day: Int, kind: ResetKind = ResetKind.GLOBAL) = ResetEntry(at("2026-10-01T12:00:00Z") + day * 86_400L, true, kind, "x", null, null, true, IntelSource.CODEX_RESET)
        assertNull(ResetOutlookBuilder.rhythm(listOf(entry(0), entry(2), entry(5))))
        val rhythm = ResetOutlookBuilder.rhythm(listOf(entry(0), entry(2), entry(5), entry(6), entry(9, ResetKind.BANKED), entry(11)))!!
        assertEquals(4, rhythm.sample)
        assertEquals(2.5, rhythm.medianDays, 0.001)
        assertEquals(2.75, rhythm.meanDays, 0.001)
        assertEquals(5.0, rhythm.longestDays, 0.001)
    }

    @Test fun `the usual window is shown in the phones time zone`() {
        val berlin = java.time.ZoneId.of("Europe/Berlin")
        assertEquals(5 to 8, ResetOutlookBuilder.localHours(DayWindow(3, 6, "UTC"), at("2026-10-24T12:00:00Z"), berlin))
        assertEquals("winter time", 4 to 7, ResetOutlookBuilder.localHours(DayWindow(3, 6, "UTC"), at("2026-12-24T12:00:00Z"), berlin))
    }
}
