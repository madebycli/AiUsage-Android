package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test

/** Response shapes as documented by OpenUsage's Antigravity tests (MIT) and CodexBar's docs/antigravity.md. */
class GeminiTest {
    private val complete = """{"response":{"groups":[{"displayName":"Gemini Models","buckets":[{"bucketId":"gemini-5h","window":"5h","remainingFraction":0.58,"resetTime":"2026-11-25T19:00:00Z"},{"bucketId":"gemini-weekly","window":"weekly","remainingFraction":0.9,"resetTime":"2026-11-29T00:00:00Z"}]},{"displayName":"Claude and GPT models","buckets":[{"bucketId":"3p-5h","remainingFraction":1},{"bucketId":"3p-weekly","remainingFraction":0.25}]}]}}"""

    @Test fun `quota summary becomes five hour and weekly windows`() {
        assertTrue(GeminiParser.isComplete(complete))
        val usage = GeminiParser.summary(listOf(complete), "Google AI Pro", 100)
        val gemini = usage.windows(GeminiParser.GEMINI)
        assertEquals(listOf(FIVE_HOURS, ONE_WEEK), gemini.map { it.seconds })
        assertEquals(42.0, gemini[0].usedPercent, 1e-9)
        assertEquals(10.0, gemini[1].usedPercent, 1e-9)
        assertEquals(parseInstant("2026-11-25T19:00:00Z"), gemini[0].resetAt)
        assertEquals(75.0, usage.windows(GeminiParser.OTHER).first { it.seconds == ONE_WEEK }.usedPercent, 1e-9)
        assertEquals("Google AI Pro", usage.plan)
    }

    @Test fun `the first host with a value wins and a missing value is never zero`() {
        val first = """{"groups":[{"buckets":[{"bucketId":"gemini-5h"},{"bucketId":"3p-5h","remainingFraction":0.5}]}]}"""
        val second = """{"groups":[{"buckets":[{"bucketId":"gemini-5h","remainingFraction":0.75},{"bucketId":"3p-5h","remainingFraction":0.1}]}]}"""
        val usage = GeminiParser.summary(listOf(first, "not json", second), null, 1)
        assertEquals(25.0, usage.windows(GeminiParser.GEMINI).single().usedPercent, 1e-9)
        assertEquals(50.0, usage.windows(GeminiParser.OTHER).single().usedPercent, 1e-9)
        assertFalse(GeminiParser.isComplete(first))
    }

    @Test fun `an answer without buckets is an error, not an empty quota`() {
        assertThrows(SchemaException::class.java) { GeminiParser.summary(listOf("{}"), null, 1) }
    }

    @Test fun `plan names come from the paid tier`() {
        assertEquals("Google AI Ultra", GeminiParser.plan("""{"paidTier":{"id":"g1-ultra-tier"},"cloudaicompanionProject":"p-1"}"""))
        assertEquals("Google AI Pro", GeminiParser.plan("""{"currentTier":{"name":"Gemini Code Assist Pro"}}"""))
        assertEquals("p-1", GeminiParser.project("""{"paidTier":{"id":"g1-ultra-tier"},"cloudaicompanionProject":"p-1"}"""))
    }

    @Test fun `the callback address must carry the state of this attempt`() {
        val attempt = GeminiOAuth.attempt()
        assertTrue(attempt.url.contains("code_challenge_method=S256"))
        assertTrue(attempt.url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A51121%2Foauth-callback"))
        assertEquals("abc", GeminiOAuth.codeFrom("http://localhost:51121/oauth-callback?state=${attempt.state}&code=abc", attempt.state))
        assertThrows(GeminiLoginFailure::class.java) { GeminiOAuth.codeFrom("http://localhost:51121/oauth-callback?state=other&code=abc", attempt.state) }
        assertThrows(GeminiLoginFailure::class.java) { GeminiOAuth.codeFrom("http://localhost:51121/oauth-callback?state=${attempt.state}&error=access_denied", attempt.state) }
    }
}
