package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test

class UsageStatsTest {
    @Test fun `optional credit balance and individual spend values preserve fractional amounts`() {
        val usage = UsageParser.parse("""{"rate_limit":null,"credits":{"has_credits":true,"unlimited":false,"balance":"8.25"},"spend_control":{"individual_limit":{"limit":"25000","used":"8000.5","remaining_percent":68,"resets_at":200000}}}""", 1)
        assertEquals(8.25, usage.accountCredits!!.balance!!, 0.0)
        assertEquals(8000.5, usage.spending!!.used!!, 0.0)
        assertEquals(68.0, usage.spending!!.remainingPercent!!, 0.0)
        val old = appJson.decodeFromString<UsageSnapshot>("""{"plan":null,"groups":[],"fetchedAt":1}""")
        assertNull(old.accountCredits)
    }
    @Test fun `real daily provider totals replace instead of incrementing with every refresh`() {
        val body = """{"stats":{"lifetime_tokens":1200,"current_streak_days":2,"daily_usage_buckets":[{"start_date":"2026-10-12","tokens":1000},{"start_date":"2026-10-13","tokens":200}]}}"""
        var bundle = StatsBundle()
        repeat(20) { bundle = bundle.merge(StatsParser.codex(CodexReport.PROFILE, body, 20000L + it)) }
        assertEquals(1, bundle.reports.size)
        val days = reportedActivity(bundle)
        assertEquals(2, days.size)
        assertEquals(200.0, days.first().actual!!, 0.0)
        assertEquals(0, days.first().measurements)
    }
    @Test fun `a bucket repeated verbatim is not counted twice`() {
        val body = """{"stats":{"daily_usage_buckets":[{"start_date":"2026-10-13","tokens":500},{"start_date":"2026-10-13","tokens":500},{"start_date":"2026-10-13","tokens":70}]}}"""
        val days = reportedActivity(StatsBundle(listOf(StatsParser.codex(CodexReport.PROFILE, body, 1))))
        assertEquals(570.0, days.single().actual!!, 0.0)
    }
    @Test fun `refreshing a report keeps the tab order`() {
        val ids = CodexReport.entries.map { it.id }
        var bundle = StatsBundle(ids.map { StatsReport(it, it, "s", 1) })
        bundle = bundle.merge(StatsReport(ids.first(), "neu", "s", 2))
        assertEquals(ids, bundle.reports.map { it.id })
        val shuffled = StatsBundle(ids.reversed().map { StatsReport(it, it, "s", 1) })
        assertEquals(ids, shuffled.ordered().map { it.id })
    }
    @Test fun `surface credits and token categories retain separate units and signs`() {
        val credits = StatsParser.codex(CodexReport.CREDITS, """{"data":[{"date":"2026-10-13","product_surface":"work","credit_amount":-0.004}]}""", 20000)
        assertEquals(-0.004, credits.points.single().value, 0.0)
        val usage = StatsParser.codex(CodexReport.USAGE, """{"units":"credits","data":[{"date":"2026-10-13","product_surface_usage_values":{"work":3,"cli":2},"premium_usage_values":{"uncached_text_input_tokens_by_surface":{"cli":200}},"models":[{"model":"gpt-5","credits":2,"cost_usd":"0.15","text_output_tokens":10}]}]}""", 20000)
        assertEquals(2, usage.points.count { it.metric == "Verbrauch" && it.unit == "credits" })
        assertEquals(0.15, usage.points.first { it.metric == "cost_usd" }.value, 0.0)
        assertEquals("USD", usage.points.first { it.metric == "cost_usd" }.unit)
        assertTrue(reportedActivity(StatsBundle(listOf(usage))).isEmpty())
    }
    @Test fun `missing units never become tokens and unknown products stay attributed`() {
        val report = StatsParser.codex(CodexReport.USAGE, """{"data":[{"date":"2026-10-13","product_surface_usage_values":{"future_product":12}}]}""", 1)
        assertEquals("Einheiten", report.points.single().unit)
        assertEquals("future_product", report.points.single().label)
        assertTrue(StatsParser.codex(CodexReport.PROFILE, """{"stats":{}}""", 1).points.isEmpty())
    }
    @Test fun `analytics requests are bounded read only reports with real date scope`() {
        val at = java.time.Instant.parse("2026-10-13T12:00:00Z").epochSecond
        assertTrue(CodexReport.USAGE.url(at).contains("start_date=2026-09-14&end_date=2026-10-13"))
        assertFalse(CodexReport.PROFILE.url(at).contains('?'))
        assertFalse(CodexReport.CREDITS.url(at).contains('?'))
    }
    @Test(expected = SchemaException::class) fun `another reports shape does not invent daily totals`() {
        StatsParser.codex(CodexReport.PROFILE, """{"data":[]}""", 1)
    }
    @Test fun `declared tracker sync preserves the 48h horizon without trusting indefinitely old data`() {
        val now = 100000L
        val report = CommunityReport(CommunitySource.VERCEL_CLAUDE, ForecastChance(48, 42.0), now - 10800, now, null, emptyList(), now + 10800)
        assertTrue(ForecastConsensus.isCommunityFresh(report, now))
        assertFalse(ForecastConsensus.isCommunityFresh(report, now + 10801))
        assertFalse(ForecastConsensus.isCommunityFresh(report.copy(validUntil = now + 30000), now))
        assertEquals(48, ForecastConsensus.combine("Claude Code", emptyMap(), mapOf(CommunitySource.VERCEL_CLAUDE to CommunityResult(report)), now).single().hours)
    }
}
