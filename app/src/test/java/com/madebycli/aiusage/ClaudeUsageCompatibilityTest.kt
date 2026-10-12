package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ClaudeUsageCompatibilityTest {
    @Test fun `fractional extra spend does not reject a valid post login usage response`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"five_hour":{"utilization":42.5,"resets_at":"2026-10-12T22:00:00+00:00"},"seven_day":{"utilization":18.0},"extra_usage":{"is_enabled":true,"monthly_limit":600,"used_credits":434.43,"currency":"USD","decimal_places":2}}"""))
            server.start()
            val snapshot = ClaudeRepository(HttpTransport(), server.url("/usage").toString()).fetch(ClaudeCredential("fixture-access"))
            assertEquals(42.5, snapshot.groups[0].windows[0].usedPercent, 0.0)
            assertEquals(18.0, snapshot.groups[0].windows[1].usedPercent, 0.0)
            assertEquals(434.43, snapshot.extraUsage!!.used!!, 0.0)
            assertEquals(600.0, snapshot.extraUsage!!.limit!!, 0.0)
            assertTrue(snapshot.warnings.isEmpty())
            assertEquals("GET", server.takeRequest().method)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun `broken optional block never suppresses independent quota or fabricates credits`() {
        val snapshot = ClaudeParser.parse("""{"five_hour":{"utilization":12},"extra_usage":["changed"],"cedar_ember":{"eligible":true,"grants":[{"resets_left":"unknown"}]}}""", 50, "Pro")
        assertEquals(12.0, snapshot.groups[0].windows[0].usedPercent, 0.0)
        assertNull(snapshot.extraUsage)
        assertNull(snapshot.availableResets)
        assertTrue(snapshot.resetCredits.isEmpty())
        assertEquals(2, snapshot.warnings.size)
        assertNotNull(snapshot.creditsWarning)
    }
    @Test fun `malformed optional money metadata retains reported amounts without invented currency`() {
        val snapshot = ClaudeParser.parse("""{"five_hour":{"utilization":9},"extra_usage":{"is_enabled":{"changed":true},"used_credits":123.456,"monthly_limit":-1,"currency":"not-a-currency","decimal_places":100}}""", 50, null)
        assertEquals(9.0, snapshot.groups[0].windows[0].usedPercent, 0.0)
        val extra = snapshot.extraUsage!!
        assertNull(extra.enabled)
        assertNull(extra.limit)
        assertNull(extra.currency)
        assertNull(extra.decimals)
        assertEquals(123.456, extra.used!!, 0.0)
        assertEquals("123,456 gemeldete Einheiten", extraAmountText(extra.used, extra.currency, extra.decimals))
        assertEquals(1, snapshot.warnings.size)
    }
    @Test fun `structured session and weekly limits are read when legacy windows are absent`() {
        val snapshot = ClaudeParser.parse("""{"limits":[{"kind":"session","percent":0.97,"resets_at":"2026-10-12T22:00:00Z"},{"kind":"weekly_all","percent":1},{"kind":"weekly_scoped","percent":27,"scope":{"model":{"display_name":"Fable"}}}]}""", 50, null)
        assertEquals(listOf(18000L, 604800L), snapshot.groups[0].windows.map { it.seconds })
        assertEquals(0.97, snapshot.groups[0].windows[0].usedPercent, 0.0)
        assertEquals(1.0, snapshot.groups[0].windows[1].usedPercent, 0.0)
        assertEquals("Fable", snapshot.groups[1].name)
        assertTrue(snapshot.warnings.isEmpty())
    }
    @Test fun `legacy and structured duplicates do not produce conflicting double rows`() {
        val snapshot = ClaudeParser.parse("""{"five_hour":{"utilization":5},"seven_day":{"utilization":8},"seven_day_sonnet":{"utilization":3},"limits":[{"kind":"session","percent":7},{"kind":"weekly_all","percent":9},{"kind":"weekly_scoped","percent":4,"scope":{"model":{"display_name":"Sonnet"}}}]}""", 50, null)
        assertEquals(2, snapshot.groups.size)
        assertEquals(listOf(5.0, 8.0), snapshot.groups[0].windows.map { it.usedPercent })
        assertEquals(3.0, snapshot.groups[1].windows[0].usedPercent, 0.0)
    }
    @Test fun `one invalid quota leaves the other visible and invalid reset does not mean unbounded date`() {
        val snapshot = ClaudeParser.parse("""{"five_hour":{"utilization":420},"seven_day":{"utilization":18,"resets_at":"changed"},"seven_day_future_metadata":{"experiment":true}}""", 50, null)
        assertEquals(1, snapshot.groups[0].windows.size)
        assertEquals(604800L, snapshot.groups[0].windows[0].seconds)
        assertEquals(18.0, snapshot.groups[0].windows[0].usedPercent, 0.0)
        assertNull(snapshot.groups[0].windows[0].resetAt)
        assertEquals(2, snapshot.warnings.size)
    }
    @Test fun `null windows remain unknown and new metadata cannot create false zero usage`() {
        val snapshot = ClaudeParser.parse("""{"five_hour":{"utilization":null,"resets_at":null},"seven_day":null,"seven_day_future_metadata":{"experiment":true},"extra_usage":{"is_enabled":false,"used_credits":null}}""", 50, null)
        assertTrue(snapshot.groups.isEmpty())
        assertNull(snapshot.extraUsage!!.used)
        assertTrue(snapshot.warnings.isEmpty())
    }
    @Test fun `unreadable entire response still fails with safe structured diagnostic`() {
        for ((body, section) in listOf("<html>private-body</html>" to ClaudeUsageSection.RESPONSE,
            """{"unexpected":"private-body"}""" to ClaudeUsageSection.RESPONSE,
            """{"five_hour":{"utilization":"private-body"}}""" to ClaudeUsageSection.WINDOWS)) {
            try { ClaudeParser.parse(body, 50, null); fail() }
            catch (e: ClaudeUsageFormatException) {
                assertEquals(section, e.section)
                assertTrue(diagnosticFailure("claude_usage", e).contains("section=${section.name}"))
                assertFalse(diagnosticFailure("claude_usage", e).contains("private-body"))
                assertFalse(safeError(e).contains("hat sich geändert"))
                assertFalse(safeError(e).contains("private-body"))
            }
        }
    }
    @Test fun `eligible inventory uses reported dates and paused state when usable flag is absent`() {
        val snapshot = ClaudeParser.parse("""{"five_hour":{"utilization":12},"cedar_ember":{"eligible":true,"grants":[{"resets_left":2.0,"resets_total":2,"paused":false},{"resets_left":3,"paused":true},{"resets_left":1,"paused":false,"starts_at":"2099-01-01T00:00:00Z"},{"resets_left":1,"paused":false,"ends_at":"1970-01-01T00:00:01Z"}]}}""", 50, null)
        assertEquals(2, snapshot.availableResets)
        assertEquals(listOf("available", "paused", "unavailable", "expired"), snapshot.resetCredits.map { it.status })
    }
    @Test fun `unconfirmed eligibility or pause state is unknown rather than zero available`() {
        for (block in listOf("""{"grants":[{"resets_left":1,"paused":false}]}""",
            """{"eligible":true,"grants":[{"resets_left":1}]}""")) {
            val snapshot = ClaudeParser.parse("""{"five_hour":{"utilization":1},"cedar_ember":$block}""", 50, null)
            assertNull(snapshot.availableResets)
        }
    }
    @Test fun `ineligible malformed grants are ignored and empty eligible inventory confirms zero`() {
        val ineligible = ClaudeParser.parse("""{"five_hour":{"utilization":1},"cedar_ember":{"eligible":false,"grants":"unsupported"}}""", 50, null)
        assertNull(ineligible.availableResets)
        assertTrue(ineligible.warnings.isEmpty())
        val empty = ClaudeParser.parse("""{"five_hour":{"utilization":1},"cedar_ember":{"eligible":true,"grants":[]}}""", 50, null)
        assertEquals(0, empty.availableResets)
    }
    @Test fun `amount display follows only reported scale and currency`() {
        assertEquals("12,35 EUR", extraAmountText(1234.567, "EUR", 2))
        assertEquals("123,45 gemeldete Einheiten (USD)", extraAmountText(123.45, "USD", null))
        assertEquals("123,45 gemeldete Einheiten", extraAmountText(123.45, null, 2))
        assertEquals("Nicht gemeldet", extraAmountText(null, "EUR", 2))
    }
    @Test fun `previous encrypted snapshot schema remains readable`() {
        val old = """{"usage":{"plan":"Pro","groups":[],"fetchedAt":10,"extraUsage":{"enabled":true,"used":1234.0,"limit":null,"currency":"EUR","decimals":2}},"observations":[]}"""
        val record = appJson.decodeFromString(PersonalRecord.serializer(), old)
        assertEquals(true, record.usage!!.extraUsage!!.enabled)
        assertEquals(1234.0, record.usage!!.extraUsage!!.used!!, 0.0)
        assertTrue(record.usage!!.warnings.isEmpty())
    }
}
