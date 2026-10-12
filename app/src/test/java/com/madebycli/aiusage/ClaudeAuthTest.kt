package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class ClaudeAuthTest {
    private class MemoryStore : ClaudeCredentialStore {
        var credential: ClaudeCredential? = null
        var pending: ClaudePendingLogin? = null
        var writes = 0
        override fun readClaude() = credential
        override fun writeClaude(value: ClaudeCredential) { credential = value; writes++ }
        override fun readClaudeLogin() = pending
        override fun writeClaudeLogin(value: ClaudePendingLogin) { pending = value }
        override fun clearClaudeLogin() { pending = null }
    }
    private fun response(access: String = "access-next", refresh: String = "refresh-next") =
        MockResponse().setBody("""{"access_token":"$access","refresh_token":"$refresh","expires_in":28800,"scope":"user:profile user:inference","token_type":"Bearer"}""")

    @Test fun `phone URL uses S256 independent random state and hosted callback`() = runBlocking {
        val store = MemoryStore()
        val auth = ClaudeAuth(HttpTransport(), store, clock = { 100 })
        val url = auth.begin().toHttpUrl()
        val pending = store.pending!!
        assertEquals("claude.com", url.host)
        assertEquals(ClaudeAuth.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals("user:profile user:inference", url.queryParameter("scope"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(43, pending.verifier.length)
        assertEquals(43, pending.state.length)
        assertNotEquals(pending.verifier, pending.state)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(pending.verifier.toByteArray())), url.queryParameter("code_challenge"))
        assertFalse(url.toString().contains(pending.verifier))
        assertEquals(700L, pending.expiresAt)
        // A newly constructed auth instance can recover the encrypted pending record.
        assertEquals(url.toString(), ClaudeAuth(HttpTransport(), store, clock = { 200 }).pendingUrl())
        auth.cancel()
        assertNull(auth.pendingUrl())
    }
    @Test fun `incorrect or partial state never sends a token request`() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val store = MemoryStore()
            val auth = ClaudeAuth(HttpTransport(), store, server.url("/token").toString())
            auth.begin()
            for (code in listOf("code-only", "code#wrong-state", "code#${store.pending!!.state}#extra", "https://evil.example/code#${store.pending!!.state}")) {
                try { auth.complete(code); fail() } catch (_: ClaudeLoginFailure) { }
            }
            assertEquals(0, server.requestCount)
            assertNotNull(store.pending)
        } finally { server.shutdown() }
    }
    @Test fun `expired restored login is removed before code exchange`() = runBlocking {
        var now = 100L
        val store = MemoryStore()
        val auth = ClaudeAuth(HttpTransport(), store, clock = { now })
        auth.begin()
        val state = store.pending!!.state
        now = 700
        try { auth.complete("code#$state"); fail() } catch (_: ClaudeLoginFailure) { }
        assertNull(store.pending)
        assertNull(auth.pendingUrl())
    }
    @Test fun `exchange is consumed once uses PKCE and waits for caller commit`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(response())
            server.start()
            val store = MemoryStore()
            val auth = ClaudeAuth(HttpTransport(), store, server.url("/token").toString(), clock = { 100 })
            auth.begin()
            val pending = store.pending!!
            val credential = auth.complete("code#${pending.state}")
            assertTrue(credential.phoneLogin)
            assertEquals("refresh-next", credential.refresh)
            assertEquals(28900L, credential.expiresAt)
            assertNull(store.credential)
            assertNull(store.pending)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            val body = appJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("code", body["code"]!!.jsonPrimitive.content)
            assertEquals(pending.verifier, body["code_verifier"]!!.jsonPrimitive.content)
            assertEquals(pending.state, body["state"]!!.jsonPrimitive.content)
            assertEquals(ClaudeAuth.REDIRECT_URI, body["redirect_uri"]!!.jsonPrimitive.content)
            assertTrue(request.getHeader("User-Agent")!!.startsWith("AiUsage-Android/"))
            try { auth.complete("code#${pending.state}"); fail() } catch (_: ClaudeLoginFailure) { }
            assertEquals(1, server.requestCount)
            assertFalse(credential.toString().contains("refresh-next"))
        } finally { server.shutdown() }
    }
    @Test fun `failed one time exchange cannot replay code`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            val store = MemoryStore()
            val auth = ClaudeAuth(HttpTransport(), store, server.url("/token").toString())
            auth.begin()
            val code = "code#${store.pending!!.state}"
            try { auth.complete(code); fail() } catch (_: HttpFailure) { }
            try { auth.complete(code); fail() } catch (_: ClaudeLoginFailure) { }
            assertEquals(1, server.requestCount)
            assertNull(store.credential)
        } finally { server.shutdown() }
    }
    @Test fun `parallel phone refresh persists one rotated chain before reuse`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(response())
            server.start()
            val store = MemoryStore().apply { credential = ClaudeCredential("old", 1, "Max 5x", "own-refresh", true) }
            val auth = ClaudeAuth(HttpTransport(), store, server.url("/token").toString(), clock = { 100 })
            val credentials = coroutineScope { List(5) { async { auth.credential() } }.awaitAll() }
            assertTrue(credentials.all { it.access == "access-next" && it.plan == "Max 5x" })
            assertEquals("refresh-next", store.credential!!.refresh)
            assertEquals(1, store.writes)
            assertEquals(1, server.requestCount)
            val body = appJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("own-refresh", body["refresh_token"]!!.jsonPrimitive.content)
        } finally { server.shutdown() }
    }
    @Test fun `legacy imports never rotate copied desktop tokens`() = runBlocking {
        val server = MockWebServer()
        try {
            server.start()
            val store = MemoryStore().apply { credential = appJson.decodeFromString(ClaudeCredential.serializer(), """{"access":"old","expiresAt":1,"plan":"Pro"}""") }
            val auth = ClaudeAuth(HttpTransport(), store, server.url("/token").toString(), clock = { 100 })
            try { auth.credential(); fail() } catch (e: HttpFailure) { assertEquals(401, e.status) }
            // Even an accidentally retained refresh is not used without phone ownership.
            store.credential = ClaudeCredential("old", 1, "Pro", "copied-refresh")
            try { auth.credential(); fail() } catch (_: HttpFailure) { }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun `definitively rejected refresh is not retried but 429 respects backoff`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30"))
            server.start()
            val store = MemoryStore().apply { credential = ClaudeCredential("old", 1, refresh = "dead", phoneLogin = true) }
            val auth = ClaudeAuth(HttpTransport(), store, server.url("/token").toString(), clock = { 100 })
            repeat(2) { try { auth.credential(); fail() } catch (e: HttpFailure) { assertEquals(401, e.status) } }
            assertEquals(1, server.requestCount)
            assertNull(store.credential!!.refresh)
            store.credential = ClaudeCredential("old", 1, refresh = "own", phoneLogin = true)
            repeat(2) { try { auth.credential(); fail() } catch (e: HttpFailure) { assertEquals(429, e.status) } }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun `cancelling an in flight exchange consumes attempt without accepting an account`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(response().setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS))
            server.start()
            val store = MemoryStore()
            val auth = ClaudeAuth(HttpTransport(), store, server.url("/token").toString())
            auth.begin()
            val code = "code#${store.pending!!.state}"
            val exchange = async(Dispatchers.IO) { auth.complete(code) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            exchange.cancelAndJoin()
            assertNull(store.credential)
            assertNull(store.pending)
            try { auth.complete(code); fail() } catch (_: ClaudeLoginFailure) { }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
