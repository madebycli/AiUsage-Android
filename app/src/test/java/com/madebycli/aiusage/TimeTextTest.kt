package com.madebycli.aiusage

import com.madebycli.aiusage.data.TimeText
import com.madebycli.aiusage.data.parseInstant
import org.junit.Assert.assertNull
import com.madebycli.aiusage.data.formatPercent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeTextTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(text: String) = Instant.parse(text).epochSecond

    @Test fun `remaining time is words and never a clock lookalike`() {
        assertEquals("in unter 1 Min.", TimeText.remaining(30))
        assertEquals("in 12 Min.", TimeText.remaining(12 * 60 + 5))
        assertEquals("in 2 Std. 50 Min.", TimeText.remaining(2 * 3600 + 50 * 60))
        assertEquals("in 3 Std.", TimeText.remaining(3 * 3600))
        assertEquals("in 1 Tag 4 Std.", TimeText.remaining(86_400 + 4 * 3600))
        assertEquals("in 3 Tagen", TimeText.remaining(3 * 86_400))
        assertEquals("in unter 1 Min.", TimeText.remaining(-5))
    }

    @Test fun `clock time names the day when it is not today`() {
        val now = at("2026-10-24T10:00:00Z")
        assertEquals("17:40", TimeText.clock(at("2026-10-24T15:40:00Z"), now, zone))
        assertEquals("morgen 03:10", TimeText.clock(at("2026-10-25T02:10:00Z"), now, zone))
        assertEquals("gestern 09:00", TimeText.clock(at("2026-10-23T07:00:00Z"), now, zone))
        assertEquals("Do. 12:00", TimeText.clock(at("2026-10-29T11:00:00Z"), now, zone))
        assertEquals("12.11. 08:30", TimeText.clock(at("2026-11-12T07:30:00Z"), now, zone))
        assertEquals("heute 17:40 Uhr", TimeText.clockLong(at("2026-10-24T15:40:00Z"), now, zone))
    }

    @Test fun `percent keeps whole numbers whole`() {
        assertEquals("42", formatPercent(42.0))
        assertEquals("42,5", formatPercent(42.5))
    }

    @Test fun `reset times with numeric offsets parse like Z times`() {
        assertEquals(1791849600L, parseInstant("2026-10-13T00:00:00Z"))
        assertEquals(1791849600L, parseInstant("2026-10-13T00:00:00+00:00"))
        assertEquals(1791849600L, parseInstant("2026-10-13T02:00:00.000000+02:00"))
        assertEquals(1791849600L, parseInstant("2026-10-12T23:59:59.8+00:00"))
        assertNull(parseInstant("tomorrow"))
        assertNull(parseInstant(null))
    }
}
