package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test

class UsageParserTest {
    @Test fun `windows identified by duration rather than slot`() {
        val usage = UsageParser.parse("""{"rate_limit":{"primary_window":{"used_percent":"61.5","limit_window_seconds":604800,"reset_at":1900000000}},"plan_type":"pro"}""", 100)
        assertEquals("Wöchentlich", usage.groups.single().windows.single().title)
        assertEquals(61.5, usage.groups.single().windows.single().usedPercent, 0.0)
        assertNull(usage.availableResets)
    }
    @Test fun `absent window is not a fabricated zero percent`() {
        val usage = UsageParser.parse("""{"rate_limit":{"primary_window":null,"secondary_window":null},"rate_limit_reset_credits":{"available_count":0}}""", 100)
        assertTrue(usage.groups.single().windows.isEmpty())
        assertEquals(0, usage.availableResets)
    }
    @Test fun `reset timestamp fallback only when supplied`() {
        val usage = UsageParser.parse("""{"rate_limit":{"primary_window":{"used_percent":1,"limit_window_seconds":18000,"reset_after_seconds":120}}}""", 100)
        assertEquals(220L, usage.groups.single().windows.single().resetAt)
    }
    @Test fun `schema errors never become plausible zero counters`() {
        for (body in listOf("{}", """{"error":"unauthorized"}""",
            """{"rate_limit":{"primary_window":{"used_percent":"NaN","limit_window_seconds":18000}}}""",
            """{"rate_limit":{"primary_window":{"used_percent":-1,"limit_window_seconds":18000}}}""",
            """{"rate_limit":{"primary_window":{"used_percent":1,"limit_window_seconds":0}}}""",
            """{"rate_limit":null,"rate_limit_reset_credits":{"available_count":-1}}""")) {
            assertThrows(Exception::class.java) { UsageParser.parse(body, 100) }
        }
    }
    @Test fun `additional limits do not overwrite main limits`() {
        val usage = UsageParser.parse("""{"rate_limit":{"primary_window":{"used_percent":10,"limit_window_seconds":18000}},"additional_rate_limits":[{"limit_name":"Reserve","rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":604800}}}]}""", 100)
        assertEquals(listOf("Codex", "Reserve"), usage.groups.map { it.name })
        assertEquals(10.0, usage.groups.first().windows.single().usedPercent, 0.0)
    }
    @Test fun `credits retain available versus redeemed state`() {
        val credits = UsageParser.credits("""{"available_count":1,"credits":[{"status":"redeemed","title":"Full reset","expires_at":"2026-10-01T00:00:00Z"},{"status":"available","title":"Weekly","expires_at":null}]}""")
        assertEquals(2, credits.size)
        assertEquals("available", credits.last().status)
        assertNull(credits.last().expiresAt)
    }
}
