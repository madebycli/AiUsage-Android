package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import java.time.Instant
import java.time.ZoneId

/** Realistic history for screenshots: several weeks of 5-hour cycles fed through the real usage tracker. */
object Fixtures {
    /** Three weeks of work at habitual hours; every cycle is read shortly after it starts and shortly before it ends. */
    fun history(provider: String, seed: Int, weekPerLimit: Double, now: Long): Pair<UsageLedger, UsageSnapshot> {
        val zone = ZoneId.systemDefault()
        val random = kotlin.random.Random(seed)
        var ledger = UsageLedger()
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        var weekEnd = today.minusDays(20).atTime(17, 0).atZone(zone).toEpochSecond() + ONE_WEEK
        var weekly = 0.0
        for (dayAgo in 20 downTo 1) {
            val day = today.minusDays(dayAgo.toLong())
            for (hour in listOf(8, 14, 20)) {
                if (random.nextInt(10) < 3) continue
                val start = day.atTime(hour, 5 + random.nextInt(50)).atZone(zone).toEpochSecond()
                if (start >= weekEnd) { weekEnd += ONE_WEEK; weekly = 0.0 }
                val end = start + FIVE_HOURS
                val peak = (20 + random.nextInt(81)).toDouble()
                val mid = peak / 2
                weekly = (weekly + mid / 100 * weekPerLimit).coerceAtMost(100.0)
                ledger = UsageTracker.record(ledger, snap(provider, start + 900, mid to end, weekly to weekEnd), provider)
                weekly = (weekly + (peak - mid) / 100 * weekPerLimit).coerceAtMost(100.0)
                ledger = UsageTracker.record(ledger, snap(provider, end - 240, peak to end, weekly to weekEnd), provider)
            }
        }
        if (now >= weekEnd) { weekEnd += ONE_WEEK; weekly = 0.0 }
        val fiveEnd = now + 2 * 3600 + 43 * 60
        val current = snap(provider, now, 42.0 to fiveEnd, (weekly + 5) to weekEnd)
        val earlier = UsageSnapshot(null, listOf(QuotaGroup(provider, listOf(UsageWindow(20.0, FIVE_HOURS, fiveEnd), UsageWindow(weekly + 2, ONE_WEEK, weekEnd)))), now - 600)
        ledger = UsageTracker.record(ledger, earlier, provider)
        ledger = UsageTracker.record(ledger, current, provider)
        return ledger to current.copy(plan = if (provider == Providers.CODEX) "Plus" else "Max 5x", availableResets = if (provider == Providers.CODEX) 2 else null,
            resetCredits = if (provider == Providers.CODEX) listOf(ResetCredit("Reset", "available", now - 86_400, now + 9 * 86_400), ResetCredit("Reset", "available", now - 3600, now + 20 * 86_400)) else emptyList())
    }

    fun snap(provider: String, at: Long, five: Pair<Double, Long>, week: Pair<Double, Long>) =
        UsageSnapshot(null, listOf(QuotaGroup(provider, listOf(UsageWindow(five.first, FIVE_HOURS, five.second), UsageWindow(week.first.coerceAtMost(100.0), ONE_WEEK, week.second)))), at)

}
