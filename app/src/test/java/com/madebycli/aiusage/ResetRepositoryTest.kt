package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ResetRepositoryTest {
    @Test fun `public refresh interval does not silently clear a failure`() = runBlocking {
        var nanos = 0L
        var requests = 0
        val http = HttpTransport(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests++
            val body = if (requests == 1) """{"events":[],"updated_at":"2026-10-12T10:00:00Z"}""" else "unavailable"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (requests == 1) 200 else 503)
                .message("test").body(body.toResponseBody()).build()
        }).build())
        val repo = ResetRepository(http) { nanos }
        val first = repo.fetch(ResetSource.CODEX_RESET)
        assertNotNull(first.feed)
        repo.fetch(ResetSource.CODEX_RESET); assertEquals(1, requests)
        nanos = 61_000_000_000L
        val failure = repo.fetch(ResetSource.CODEX_RESET)
        assertNotNull(failure.error)
        assertEquals(first.feed, failure.feed)
        val cached = repo.fetch(ResetSource.CODEX_RESET)
        assertEquals(failure.error, cached.error)
        assertEquals(2, requests)
    }
}
