package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ClaudeForecastTest {
    @Test fun `phone profile preserves reported plan and does not infer Free for Team`() {
        assertEquals("Max 20x", ClaudeParser.profilePlan("""{"account":{"has_claude_max":true},"organization":{"organization_type":"claude_max","rate_limit_tier":"default_claude_max_20x"}}"""))
        assertEquals("Team", ClaudeParser.profilePlan("""{"account":{"has_claude_pro":false,"has_claude_max":false},"organization":{"organization_type":"claude_team"}}"""))
        assertNull(ClaudeParser.profilePlan("""{"account":{"has_claude_pro":false,"has_claude_max":false}}"""))
    }
    @Test fun `Claude parses only reported windows model caps extra money and usable grants`() {
        val body = """{"five_hour":{"utilization":42,"resets_at":"2026-10-12T18:00:00Z"},"seven_day":null,"seven_day_sonnet":{"utilization":18},"limits":[{"kind":"weekly_scoped","percent":7,"scope":{"model":{"display_name":"Fable"}}}],"extra_usage":{"is_enabled":true,"used_credits":1200.0,"monthly_limit":5000,"currency":"EUR","decimal_places":2},"cedar_ember":{"grants":[{"label":"One reset","resets_left":2,"usable_now":true},{"label":"Paused","resets_left":3,"usable_now":true,"paused":true}]}}"""
        val usage = ClaudeParser.parse(body, 50, "Max 5x")
        assertEquals(3, usage.groups.size)
        assertEquals(1, usage.groups.first().windows.size)
        assertEquals(2, usage.availableResets)
        assertEquals("paused", usage.resetCredits[1].status)
        assertEquals("EUR", usage.extraUsage?.currency)
        assertEquals(1200.0, usage.extraUsage!!.used!!, 0.0)
    }
    @Test fun `missing grant and money fields remain unknown`() {
        val usage = ClaudeParser.parse("""{"five_hour":null,"seven_day":null,"extra_usage":{"is_enabled":true}}""", 50, null)
        assertTrue(usage.groups.isEmpty())
        assertNull(usage.availableResets)
        assertNull(usage.extraUsage!!.used)
    }
    @Test fun `ineligible reset block does not fabricate zero available credits`() {
        val usage = ClaudeParser.parse("""{"five_hour":{"utilization":1},"cedar_ember":{"eligible":false,"ineligible_reason":"surface"}}""", 50, null)
        assertNull(usage.availableResets)
    }
    @Test fun `import drops rotating refresh token and converts millisecond expiry`() {
        val credential = ClaudeParser.credential("""{"claudeAiOauth":{"accessToken":"access-test","refreshToken":"refresh-secret","expiresAt":1791810000000.0,"subscriptionType":"max","rateLimitTier":"max_20x"}}""")
        assertEquals(1791810000L, credential.expiresAt)
        assertEquals("Max 20x", credential.plan)
        assertFalse(credential.toString().contains("access-test"))
        assertNull(credential.refresh)
        assertFalse(credential.phoneLogin)
        assertFalse(appJson.encodeToString(ClaudeCredential.serializer(), credential).contains("refresh-secret"))
    }
    @Test(expected = IllegalArgumentException::class) fun `API key cannot impersonate subscription credentials`() { ClaudeParser.credential("sk-ant-api03-test") }
    @Test(expected = SchemaException::class) fun `invalid Claude percent fails without a fabricated reading`() { ClaudeParser.parse("""{"five_hour":{"utilization":420}}""", 0, null) }
    @Test fun `new refresh always fetches but respects provider 429`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"five_hour":{"utilization":1}}"""))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
            server.start()
            val repo = ClaudeRepository(HttpTransport(), server.url("/usage").toString())
            val credential = ClaudeCredential("access-test")
            repo.fetch(credential)
            try { repo.fetch(credential); fail() } catch (e: HttpFailure) { assertEquals(429, e.status) }
            try { repo.fetch(credential); fail() } catch (e: HttpFailure) { assertEquals(429, e.status) }
            assertEquals(2, server.requestCount)
            val request = server.takeRequest()
            assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
            assertEquals("Bearer access-test", request.getHeader("Authorization"))
            assertTrue(request.getHeader("User-Agent")!!.startsWith("claude-cli/2.1.281"))
            assertTrue(request.getHeader("User-Agent")!!.contains("AiUsage-Android/0.4"))
        } finally { server.shutdown() }
    }
    @Test fun `source horizons are preserved without converting six hours to forty eight`() {
        val lunar = ForecastParser.parse(ResetSource.LUNARWERX, """{"nearTerm":{"hours":6,"probability":0.032,"low":0.011,"high":0.097},"next24Hours":{"probability":0.27,"low":0.136,"high":0.452}}""", 10)
        assertEquals(listOf(6, 24), lunar.chances.map { it.hours })
        assertEquals(27.0, lunar.chances[1].percent, .001)
        val codex = ForecastParser.parse(ResetSource.CODEX_RESET, """{"probabilities":{"rounded_24h":19,"rounded_48h":34},"confidence":"low"}""", 10)
        assertEquals(listOf(24, 48), codex.chances.map { it.hours })
        assertNull(codex.publishedAt)
    }
    @Test(expected = SchemaException::class) fun `invalid probability scale rejected`() { ForecastParser.parse(ResetSource.LUNARWERX, """{"nearTerm":{"hours":6,"probability":32,"low":0,"high":1}}""", 0) }
}
