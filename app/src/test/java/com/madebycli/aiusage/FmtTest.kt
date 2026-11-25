package com.madebycli.aiusage

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.madebycli.aiusage.data.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** Times and numbers read naturally in both languages and never look like a clock when they are a duration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FmtTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun res(): Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private fun at(text: String) = Instant.parse(text).epochSecond

    @Test @Config(qualifiers = "de") fun `German durations clock times and limits`() {
        assertEquals("in 2 Std. 50 Min.", Fmt.remaining(res(), 2 * 3600 + 50 * 60))
        assertEquals("in 3 Std.", Fmt.remaining(res(), 3 * 3600))
        assertEquals("in 1 Tag 4 Std.", Fmt.remaining(res(), 86_400 + 4 * 3600))
        assertEquals("in 3 Tagen", Fmt.remaining(res(), 3 * 86_400))
        assertEquals("in unter 1 Min.", Fmt.remaining(res(), 30))
        // 20 October, summer time in Berlin (UTC+2).
        val now = at("2026-10-20T10:00:00Z")
        assertEquals("17:40", Fmt.clock(res(), at("2026-10-20T15:40:00Z"), now, zone))
        assertEquals("morgen 03:10", Fmt.clock(res(), at("2026-10-21T01:10:00Z"), now, zone))
        assertEquals("Fr. 08:00", Fmt.clock(res(), at("2026-10-23T06:00:00Z"), now, zone))
        // The night the clocks go back: 01:10 UTC is 02:10 local time.
        assertEquals("02:10", Fmt.clockOnly(res(), at("2026-10-25T01:10:00Z"), zone))
        assertEquals("2,4 Limits", Fmt.limits(res(), 240.0))
        assertEquals("1 Limit", Fmt.limits(res(), 100.0))
        assertEquals("vor 35 Min.", Fmt.ago(res(), 35 * 60))
    }

    @Test @Config(qualifiers = "en") fun `English durations clock times and limits`() {
        assertEquals("in 2 h 50 min", Fmt.remaining(res(), 2 * 3600 + 50 * 60))
        assertEquals("in 3 days", Fmt.remaining(res(), 3 * 86_400))
        val now = at("2026-10-20T10:00:00Z")
        assertEquals("tomorrow 3:10 AM", Fmt.clock(res(), at("2026-10-21T01:10:00Z"), now, zone).replace("\u202F", " "))
        assertEquals("2.4 limits", Fmt.limits(res(), 240.0))
        assertEquals("1 limit", Fmt.limits(res(), 100.0))
    }

    @Test fun `percentages are whole and a missing value is a dash`() {
        assertEquals("42", Fmt.percent(41.6))
        assertEquals("–", Fmt.percent(Double.NaN))
    }
}
