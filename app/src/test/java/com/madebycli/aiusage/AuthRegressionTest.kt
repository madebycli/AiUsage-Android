package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthRegressionTest {
    private class MemoryStore : TokenStore {
        @Volatile var saved: Tokens? = Tokens("old", "refresh", "account", null)
        override fun readTokens() = saved
        override fun writeTokens(tokens: Tokens) { saved = tokens }
        override fun clear() { saved = null }
    }

    @Test fun `slow credit details do not hold quota authorization lock`() = runBlocking {
        val server = MockWebServer(); server.start()
        val arrived = CompletableDeferred<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/credits") {
                    arrived.complete(Unit)
                    return MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS)
                }
                return MockResponse().setBody("quota")
            }
        }
        val auth = CodexAuth(HttpTransport(), MemoryStore(), server.url("/").toString().trimEnd('/'))
        val slow = async { auth.authorizedGet(server.url("/credits").toString()) }
        try {
            withTimeout(2_000) { arrived.await() }
            assertEquals("quota", withTimeout(1_500) { auth.authorizedGet(server.url("/usage").toString()) })
        } finally { slow.cancelAndJoin(); server.shutdown() }
    }

    @Test fun `parallel rejected tokens rotate once and both retry with new token`() = runBlocking {
        val server = MockWebServer(); server.start()
        val rejected = CountDownLatch(2)
        val rotations = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/oauth/token") {
                    rotations.incrementAndGet()
                    return MockResponse().setBody("""{"access_token":"new","refresh_token":"rotated","expires_in":3600}""")
                }
                if (request.getHeader("Authorization") == "Bearer old") {
                    rejected.countDown()
                    check(rejected.await(3, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(401)
                }
                return MockResponse().setBody("quota")
            }
        }
        val memory = MemoryStore()
        val auth = CodexAuth(HttpTransport(), memory, server.url("/").toString().trimEnd('/'))
        try {
            val results = withTimeout(5_000) {
                listOf(async { auth.authorizedGet(server.url("/usage").toString()) },
                    async { auth.authorizedGet(server.url("/usage").toString()) }).awaitAll()
            }
            assertEquals(listOf("quota", "quota"), results)
            assertEquals(1, rotations.get())
            assertEquals("rotated", memory.saved?.refresh)
        } finally { server.shutdown() }
    }

    @Test fun `browser approval exchange failure reports exchange phase and saves nothing`() = runBlocking {
        val server = MockWebServer(); server.start()
        val memory = MemoryStore().apply { saved = null }
        val phases = mutableListOf<LoginPhase>()
        server.enqueue(MockResponse().setBody("""{"authorization_code":"secret-code","code_verifier":"secret-verifier"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("secret-response"))
        try {
            val auth = CodexAuth(HttpTransport(), memory, server.url("/").toString().trimEnd('/'))
            try { auth.complete(DeviceCode("device", "ABCD", 1)) { phases += it }; fail("expected failure") }
            catch (error: HttpFailure) {
                assertEquals(listOf(LoginPhase.EXCHANGING), phases)
                val message = loginFailure(phases.last(), error)
                assertTrue(message.contains("HTTP 400"))
                assertTrue(message.contains("neuen Code"))
                assertFalse(message.contains("secret"))
            }
            assertNull(memory.saved)
        } finally { server.shutdown() }
    }

    @Test fun `device storage rejection is distinct from network or schema failure`() = runBlocking {
        val server = MockWebServer(); server.start()
        val phases = mutableListOf<LoginPhase>()
        server.enqueue(MockResponse().setBody("""{"authorization_code":"code","code_verifier":"verifier"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh"}"""))
        val broken = object : TokenStore {
            override fun readTokens(): Tokens? = null
            override fun writeTokens(tokens: Tokens) { throw IllegalArgumentException("secret-key") }
            override fun clear() {}
        }
        try {
            try {
                CodexAuth(HttpTransport(), broken, server.url("/").toString().trimEnd('/'))
                    .complete(DeviceCode("device", "ABCD", 1)) { phases += it }
                fail("expected storage error")
            } catch (error: IllegalArgumentException) {
                assertEquals(LoginPhase.SAVING, phases.last())
                assertTrue(loginFailure(phases.last(), error).contains("Gerät nicht gespeichert"))
                assertFalse(diagnosticFailure("login_SAVING", error).contains("secret-key"))
            }
        } finally { server.shutdown() }
    }

    @Test fun `account id falls back to access token when id token has no auth claim`() {
        fun jwt(payload: String) = "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.sig"
        val id = jwt("""{"sub":"person"}""")
        val access = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"workspace"}}""")
        val tokens = CodexAuth(HttpTransport(), MemoryStore()).parseTokens("""{"access_token":"$access","id_token":"$id"}""")
        assertEquals("workspace", tokens.accountId)
    }

    @Test fun `invalid refresh grant requests new login instead of unreachable server`() = runBlocking {
        val server = MockWebServer(); server.start()
        val memory = MemoryStore().apply { saved = Tokens("old", "refresh", null, 1) }
        server.enqueue(MockResponse().setResponseCode(400))
        try {
            try { CodexAuth(HttpTransport(), memory, server.url("/").toString().trimEnd('/')).authorizedGet(server.url("/usage").toString()); fail("expected failure") }
            catch (error: HttpFailure) { assertEquals(401, error.status) }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `storage cancellation propagates and old sessions cannot publish`() = runTest {
        val session = SessionGeneration()
        val old = session.current()
        session.advance()
        assertFalse(session.isCurrent(old))
        try { persistAccountRecord { throw CancellationException("cancelled") }; fail("expected cancellation") }
        catch (_: CancellationException) { }
        assertNotNull(persistAccountRecord { throw IOException("disk") })
    }

    @Test fun `retry after stays active until final fraction of second expires`() {
        var clock = 0L
        val gate = RequestBackoff { clock }
        gate.defer(1)
        clock = 999_999_999
        assertEquals(1L, gate.remainingSeconds())
        clock = 1_000_000_000
        assertEquals(0L, gate.remainingSeconds())
        gate.defer(Long.MAX_VALUE)
        assertTrue(gate.remainingSeconds() > 0)
    }
}
