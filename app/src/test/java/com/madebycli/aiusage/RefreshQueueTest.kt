package com.madebycli.aiusage

import com.madebycli.aiusage.data.RefreshQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RefreshQueueTest {
    @Test fun `every idle tap starts a new request`() = runTest {
        var calls = 0
        val queue = RefreshQueue(backgroundScope) { calls++ }
        repeat(5) { queue.request(); runCurrent() }
        assertEquals(5, calls)
        queue.cancel()
    }
    @Test fun `rapid taps keep active request and coalesce one followup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val queue = RefreshQueue(backgroundScope) { calls++; if (calls == 1) gate.await() }
        queue.request(); runCurrent()
        repeat(100) { queue.request() }
        runCurrent(); assertEquals(1, calls)
        gate.complete(Unit); runCurrent(); assertEquals(2, calls)
        queue.request(); runCurrent(); assertEquals(3, calls)
        queue.cancel()
    }
}
