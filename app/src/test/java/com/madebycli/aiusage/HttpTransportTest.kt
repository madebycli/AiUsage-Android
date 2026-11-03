package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HttpTransportTest {
    @Test fun `public tracker pages follow canonical redirects and still parse both providers`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val http = HttpTransport()
            for ((source, fixture) in listOf(IntelSource.CODEX_TRACKER to "tracker_codex.html", IntelSource.CLAUDE_TRACKER to "tracker_claude.html")) {
                val html = javaClass.classLoader!!.getResourceAsStream("intel/$fixture")!!.use { it.readBytes().toString(Charsets.UTF_8) }
                for (status in listOf(301, 302, 303, 307, 308)) {
                    val path = java.net.URI(source.site).path
                    server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", "$path/"))
                    server.enqueue(MockResponse().setBody(html))
                    val body = http.getPublicHtml(server.url(path).toString())
                    val report = IntelParser.tracker(source, body, 1_792_840_800L)
                    assertEquals(source, report.source)
                    assertTrue(report.chances.isNotEmpty())
                    assertEquals(path, server.takeRequest().path)
                    val redirected = server.takeRequest()
                    assertEquals("$path/", redirected.path)
                    assertEquals("text/html", redirected.getHeader("Accept"))
                    assertNull(redirected.getHeader("Authorization"))
                    assertNull(redirected.getHeader("ChatGPT-Account-Id"))
                }
            }
        } finally { server.shutdown() }
    }

    @Test fun `public pages reject redirects to another origin before contacting it`() = runBlocking {
        val server = MockWebServer(); server.start()
        val other = MockWebServer(); other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(308).setHeader("Location", other.url("/page")))
            assertThrows(java.io.IOException::class.java) {
                runBlocking { HttpTransport().getPublicHtml(server.url("/page").toString()) }
            }
            assertEquals(0, other.requestCount)
        } finally { server.shutdown(); other.shutdown() }
    }

    @Test fun `public redirect loops are bounded`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            repeat(22) { server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/loop")) }
            assertThrows(java.io.IOException::class.java) {
                runBlocking { HttpTransport().getPublicHtml(server.url("/loop").toString()) }
            }
            assertEquals(21, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `authenticated GET and login POST still reject redirects`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val http = HttpTransport()
            repeat(2) { server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/unexpected")) }
            val getError = assertThrows(HttpFailure::class.java) {
                runBlocking { http.get(server.url("/usage").toString(), mapOf("Authorization" to "Bearer private")) }
            }
            val postError = assertThrows(HttpFailure::class.java) {
                runBlocking { http.postForm(server.url("/token").toString(), mapOf("refresh_token" to "private")) }
            }
            assertEquals(307, getError.status)
            assertEquals(307, postError.status)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `device login waits for approval and exchanges PKCE code`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val memory = object : TokenStore {
                var saved: Tokens? = null
                override fun readTokens() = saved
                override fun writeTokens(tokens: Tokens) { saved = tokens }
                override fun clear() { saved = null }
            }
            server.enqueue(MockResponse().setBody("""{"device_auth_id":"device","user_code":"ABCD","interval":"1"}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("""{"authorization_code":"approved","code_verifier":"verifier","code_challenge":"challenge"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","expires_in":3600}"""))
            val auth = CodexAuth(HttpTransport(), memory, server.url("/").toString().trimEnd('/'))
            val code = auth.begin(); assertEquals("ABCD", code.code)
            auth.complete(code)
            assertEquals("access", memory.saved?.access)
            assertEquals("/api/accounts/deviceauth/usercode", server.takeRequest().path)
            assertEquals("/api/accounts/deviceauth/token", server.takeRequest().path)
            assertEquals("/api/accounts/deviceauth/token", server.takeRequest().path)
            val exchange = server.takeRequest()
            assertEquals("/oauth/token", exchange.path)
            val body = exchange.body.readUtf8()
            assertTrue(body.contains("code_verifier=verifier"))
            assertTrue(body.contains("grant_type=authorization_code"))
        } finally { server.shutdown() }
    }
    @Test fun `consecutive fetches reach server with no local cache`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val http = HttpTransport()
            repeat(3) { server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=600").setBody("{\"value\":$it}")) }
            val responses = (0..2).map { http.get(server.url("/usage").toString()) }
            assertEquals(3, responses.distinct().size)
            assertEquals(3, server.requestCount)
            repeat(3) { assertEquals("no-cache, no-store", server.takeRequest().getHeader("Cache-Control")) }
        } finally { server.shutdown() }
    }
    @Test fun `429 respects retry after and never exposes response body`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "90").setBody("secret-token"))
            try { HttpTransport().get(server.url("/").toString()); fail("expected 429") }
            catch (error: HttpFailure) { assertEquals(90L, error.retrySeconds); assertFalse(error.message!!.contains("secret-token")) }
        } finally { server.shutdown() }
    }
    @Test fun `rotated tokens persisted before retrying rejected auth`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val memory = object : TokenStore {
                var tokens = Tokens("old-access", "old-refresh", "account", null)
                override fun readTokens() = tokens
                override fun writeTokens(tokens: Tokens) { this.tokens = tokens }
                override fun clear() {}
            }
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","refresh_token":"rotated","expires_in":3600}"""))
            server.enqueue(MockResponse().setBody("{}"))
            CodexAuth(HttpTransport(), memory, server.url("/").toString().trimEnd('/')).authorizedGet(server.url("/usage").toString())
            assertEquals("rotated", memory.tokens.refresh)
            assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
            assertEquals("/oauth/token", server.takeRequest().path)
            val retry = server.takeRequest()
            assertEquals("Bearer new-access", retry.getHeader("Authorization"))
            assertEquals("account", retry.getHeader("ChatGPT-Account-Id"))
        } finally { server.shutdown() }
    }
}
