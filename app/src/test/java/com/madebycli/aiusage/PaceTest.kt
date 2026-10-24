package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class PaceTest {
    private fun at(text: String) = Instant.parse(text).epochSecond
    private val end = at("2026-10-20T15:00:00Z")

    private fun window(used: Double, reset: Long? = end) = UsageWindow(used, FIVE_HOURS, reset)

    @Test fun `no pace without a reset time or outside the window`() {
        assertNull(Pace.of(window(40.0, null), at("2026-10-20T12:00:00Z")))
        assertNull(Pace.of(window(40.0), at("2026-10-20T15:30:00Z")))
        assertNull(Pace.of(window(40.0), at("2026-10-20T09:00:00Z")))
    }

    @Test fun `too little elapsed time or no usage gives no estimate`() {
        assertNull(Pace.of(window(40.0), at("2026-10-20T10:05:00Z")))
        assertNull(Pace.of(window(0.0), at("2026-10-20T13:00:00Z")))
    }

    @Test fun `slow usage stays on track`() {
        val pace = Pace.of(window(20.0), at("2026-10-20T12:30:00Z"))!!
        assertEquals(Pace.Status.ON_TRACK, pace.status)
        assertEquals(0.5, pace.elapsed, 0.0001)
        assertEquals(40.0, pace.projectedPercent, 0.0001)
        assertNull(pace.fullAt)
    }

    @Test fun `fast usage runs out before the reset at the computed time`() {
        // 60 % after half the window: full after 5/6 of it, at 14:10
        val pace = Pace.of(window(60.0), at("2026-10-20T12:30:00Z"))!!
        assertEquals(Pace.Status.WILL_RUN_OUT, pace.status)
        assertEquals(at("2026-10-20T14:10:00Z"), pace.fullAt)
    }

    @Test fun `a full limit is reported as full`() {
        val pace = Pace.of(window(100.0), at("2026-10-20T11:00:00Z"))!!
        assertEquals(Pace.Status.FULL, pace.status)
    }
}
