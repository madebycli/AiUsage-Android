package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test

class UsageStatsTest {
    @Test fun `optional credit balance and individual spend values preserve fractional amounts`() {
        val usage = UsageParser.parse("""{"rate_limit":null,"credits":{"has_credits":true,"unlimited":false,"balance":"8.25"},"spend_control":{"individual_limit":{"limit":"25000","used":"8000.5","remaining_percent":68,"resets_at":200000}}}""", 1)
        assertEquals(8.25, usage.accountCredits!!.balance!!, 0.0)
        assertEquals(8000.5, usage.spending!!.used!!, 0.0)
        assertEquals(68.0, usage.spending!!.remainingPercent!!, 0.0)
        val old = appJson.decodeFromString<UsageSnapshot>("""{"plan":null,"groups":[],"fetchedAt":1}""")
        assertNull(old.accountCredits)
    }
}
