package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MigrationAndIdentityTest {
    @Test fun `a record written by the previous version still opens and keeps its history`() {
        val old = """{"usage":{"plan":"plus","groups":[{"name":"Codex","windows":[{"usedPercent":21.0,"seconds":18000,"resetAt":2000000}]}],"fetchedAt":1900000},
            "days":[{"date":"2026-10-14","peak":42.0,"measurements":0,"lastMeasuredAt":1800000,"actual":null,"unit":null,"source":null,"weekly":10.0},
                    {"date":"2026-10-13","peak":null,"measurements":0,"lastMeasuredAt":1700000,"actual":1500.0,"unit":"Tokens","source":"Account · UTC","weekly":null}]}"""
        val record = appJson.decodeFromString<PersonalRecord>(old).migrated()
        assertEquals(1900000L, record.usage!!.fetchedAt)
        assertTrue(record.days.isEmpty())
        // Token totals were removed with the cloud reports; only the quota peak is carried over.
        assertEquals(listOf("2026-10-14"), record.ledger.days.map { it.date })
        assertEquals(42.0, record.ledger.days.single().five, 0.0)
        assertTrue(record.ledger.days.single().legacy)
    }

    @Test fun `stored sign ins without an email still decode`() {
        val tokens = appJson.decodeFromString<Tokens>("""{"access":"a","refresh":"r","accountId":"acct","expiresAt":5}""")
        assertNull(tokens.email)
        assertEquals("acct", tokens.accountId)
        val claude = appJson.decodeFromString<ClaudeCredential>("""{"access":"a","expiresAt":5,"plan":"Pro","refresh":"r","phoneLogin":true}""")
        assertTrue(claude.phoneLogin)
    }

    @Test fun `the Claude profile names the account without guessing`() {
        val profile = ClaudeParser.profile("""{"account":{"uuid":"1234-abcd","email_address":"me@example.com","has_claude_max":true},"organization":{"organization_type":"claude_max","rate_limit_tier":"default_claude_max_5x"}}""")
        assertEquals("Max 5x", profile.plan)
        assertEquals("me@example.com", profile.email)
        assertEquals("1234-abcd", profile.id)
        val bare = ClaudeParser.profile("""{"account":{}}""")
        assertNull(bare.email); assertNull(bare.id); assertNull(bare.plan)
        assertNull("control characters never reach the screen", ClaudeParser.profile("""{"account":{"email_address":"a\u0000b"}}""").email)
    }

    private fun jwt(payload: String) = "x." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".y"

    @Test fun `Codex sign in carries account id and email from the token`() {
        val auth = CodexAuth(HttpTransport(), object : TokenStore {
            override fun readTokens(): Tokens? = null
            override fun writeTokens(tokens: Tokens) {}
            override fun clear() {}
        })
        val id = jwt("""{"email":"me@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"acct-1"}}""")
        val tokens = auth.parseTokens("""{"access_token":"${jwt("""{"exp":4000000000}""")}","refresh_token":"r","id_token":"$id","expires_in":3600}""")
        assertEquals("acct-1", tokens.accountId)
        assertEquals("me@example.com", tokens.email)
        val refreshed = auth.parseTokens("""{"access_token":"new","expires_in":3600}""", tokens)
        assertEquals("a refresh without id token keeps what is known", "me@example.com", refreshed.email)
        assertEquals("acct-1", refreshed.accountId)
        assertEquals("r", refreshed.refresh)
    }
}
