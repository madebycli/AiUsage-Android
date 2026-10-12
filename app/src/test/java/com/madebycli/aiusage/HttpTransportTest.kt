package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HttpTransportTest {
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
