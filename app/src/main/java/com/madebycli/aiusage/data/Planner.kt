package com.madebycli.aiusage.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor

/**
 * Plans which account to use when, from what the providers report and what the app measured.
 *
 * Inputs are the current windows of every account, the learned cost of a full 5-hour limit in weekly points
 * ([Calibration.weekPerLimit]) and the hours at which the user usually starts working. Nothing is invented:
 * without a calibration the plan has no sessions, only budgets and resets.
 */
object Planner {
    /** Weekly points below this are treated as used up. */
    const val EMPTY_WEEK = 2.0
    const val HORIZON_DAYS = 7
    /** Default working start hours while the history is too short. */
    val DEFAULT_HOURS = listOf(9, 14, 19)

    data class Input(
        val accountId: String,
        val five: UsageWindow?,
        val week: UsageWindow?,
        /** Weekly points one full 5-hour limit costs; null until measured. */
        val weekPerLimit: Double?
    )

    /** The state of one account right now. */
    data class Budget(
        val accountId: String,
        val weekLeft: Double?,
        val weekReset: Long?,
        val fiveUsed: Double?,
        val fiveReset: Long?,
        /** Full 5-hour limits the weekly budget still allows; null without calibration. */
        val sessionsLeft: Double?,
        /** Weekly points per remaining day until the weekly reset. */
        val perDay: Double?,
        /** Weekly points that expire per hour if unused: what makes an account urgent. */
        val urgency: Double,
        /** When the account can be used again: now, or the end of a full 5-hour or weekly window. */
        val availableAt: Long
    ) {
        val blockedByFive get() = (fiveUsed ?: 0.0) >= 100.0
        val weekEmpty get() = weekLeft != null && weekLeft < EMPTY_WEEK
    }

    /** A suggested block of work on one account. */
    data class Session(val accountId: String, val start: Long, val end: Long, val share: Double, val current: Boolean)

    data class Plan(
        val budgets: List<Budget>,
        /** Accounts in the order they should be used from now on. */
        val order: List<String>,
        val sessions: List<Session>,
        /** Weekly points that will expire before they can be used in the planned hours, per account. */
        val expiring: Map<String, Double>,
        /** Pairs of accounts whose weekly resets fall within 24 hours of each other. */
        val overlaps: List<Pair<String, String>>,
        val hours: List<Int>
    )

    fun budget(input: Input, now: Long): Budget {
        val week = input.week
        val five = input.five
        val weekLeft = week?.usedPercent?.let { (100 - it).coerceIn(0.0, 100.0) }
        val weekReset = week?.resetAt?.takeIf { it > now }
        val hoursLeft = weekReset?.let { ((it - now) / 3600.0).coerceAtLeast(1.0) }
        val sessions = if (weekLeft != null && input.weekPerLimit != null && input.weekPerLimit > 0) weekLeft / input.weekPerLimit else null
        val perDay = if (weekLeft != null && hoursLeft != null) weekLeft / (hoursLeft / 24).coerceAtLeast(1.0 / 24) else null
        val fiveReset = five?.resetAt?.takeIf { it > now }
        val fiveFull = (five?.usedPercent ?: 0.0) >= 100
        val weekFull = weekLeft != null && weekLeft < EMPTY_WEEK
        val available = when {
            weekFull -> weekReset ?: Long.MAX_VALUE
            fiveFull -> fiveReset ?: now
            else -> now
        }
        val urgency = if (weekLeft != null && hoursLeft != null) weekLeft / hoursLeft else 0.0
        return Budget(input.accountId, weekLeft, weekReset, five?.usedPercent, fiveReset, sessions, perDay, urgency, available)
    }

    /** Start hours the user usually begins a 5-hour window at, at most three that do not overlap. */
    fun habitualHours(cycleStarts: List<Long>, zone: ZoneId = ZoneId.systemDefault(), now: Long): List<Int> {
        val recent = cycleStarts.filter { it > now - 28 * 86_400L && it <= now }
        if (recent.size < 6) return DEFAULT_HOURS
        val counts = IntArray(24)
        recent.forEach { counts[Instant.ofEpochSecond(it).atZone(zone).hour]++ }
        val picked = mutableListOf<Int>()
        counts.withIndex().sortedByDescending { it.value }.filter { it.value > 0 }.forEach { (hour, _) ->
            if (picked.size < 3 && picked.none { other -> val d = Math.floorMod(hour - other, 24); d < 5 || d > 19 }) picked += hour
        }
        return picked.sorted().ifEmpty { DEFAULT_HOURS }
    }

    /** Start times of the working slots in the horizon: now, then every habitual hour. */
    fun slots(now: Long, hours: List<Int>, zone: ZoneId = ZoneId.systemDefault(), days: Int = HORIZON_DAYS): List<Long> {
        val start = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        val result = sortedSetOf(now)
        for (d in 0..days) for (h in hours) {
            val at = start.plusDays(d.toLong()).atTime(h, 0).atZone(zone).toEpochSecond()
            if (at > now && at < now + days * 86_400L) result += at
        }
        // A slot lasts five hours; later slots that start inside one are dropped.
        val spaced = mutableListOf<Long>()
        result.forEach { if (spaced.isEmpty() || it >= spaced.last() + FIVE_HOURS) spaced += it }
        return spaced
    }

    fun plan(inputs: List<Input>, now: Long, cycleStarts: List<Long>, zone: ZoneId = ZoneId.systemDefault()): Plan {
        val budgets = inputs.map { budget(it, now) }
        val hours = habitualHours(cycleStarts, zone, now)
        // Use first what is available now and expires soonest; among equals the one with more left.
        val order = budgets.sortedWith(compareBy<Budget>({ it.availableAt > now }, { it.weekEmpty }, { it.weekReset ?: Long.MAX_VALUE }, { -(it.weekLeft ?: 0.0) }))
            .map { it.accountId }

        val sessions = mutableListOf<Session>()
        val expiring = mutableMapOf<String, Double>()
        val calibrated = inputs.associate { it.accountId to it.weekPerLimit }
        if (calibrated.values.any { it != null }) {
            val left = budgets.associate { it.accountId to (it.sessionsLeft ?: 0.0) }.toMutableMap()
            val resets = budgets.associate { it.accountId to it.weekReset }.toMutableMap()
            val busyUntil = budgets.associate { it.accountId to if (it.blockedByFive) (it.fiveReset ?: now) else now }.toMutableMap()
            // The window that is running now counts as the current session of its account.
            budgets.filter { (it.fiveUsed ?: 0.0) > 0 && !it.blockedByFive && it.fiveReset != null && !it.weekEmpty && calibrated[it.accountId] != null }
                .maxByOrNull { it.urgency }?.let { running ->
                    val share = ((100 - (running.fiveUsed ?: 0.0)) / 100).coerceAtMost(left[running.accountId] ?: 0.0)
                    if (share > 0) {
                        sessions += Session(running.accountId, running.fiveReset!! - FIVE_HOURS, running.fiveReset, share, current = true)
                        left[running.accountId] = (left[running.accountId] ?: 0.0) - share
                        busyUntil[running.accountId] = running.fiveReset
                    }
                }
            val taken = sessions.map { it.start to it.end }.toMutableList()
            for (slot in slots(now, hours, zone)) {
                if (taken.any { slot < it.second && slot + FIVE_HOURS > it.first }) continue
                // A weekly reset during the horizon refills that account.
                budgets.forEach { b ->
                    val reset = resets[b.accountId] ?: return@forEach
                    val k = calibrated[b.accountId] ?: return@forEach
                    if (slot >= reset) {
                        expiring[b.accountId] = (expiring[b.accountId] ?: 0.0) + (left[b.accountId] ?: 0.0) * k
                        left[b.accountId] = 100 / k
                        resets[b.accountId] = reset + ONE_WEEK
                    }
                }
                // The budget that expires first; among resets within an hour of each other, the one with the most left,
                // so a slot is filled as far as possible.
                val candidates = budgets.filter { calibrated[it.accountId] != null && (left[it.accountId] ?: 0.0) >= .25 && (busyUntil[it.accountId] ?: now) <= slot }
                val soonest = candidates.minOfOrNull { resets[it.accountId] ?: Long.MAX_VALUE } ?: continue
                val pick = candidates.filter { (resets[it.accountId] ?: Long.MAX_VALUE) - soonest <= 3600 }.maxByOrNull { left[it.accountId] ?: 0.0 } ?: continue
                val share = floor(minOf(1.0, left.getValue(pick.accountId)) * 100) / 100
                sessions += Session(pick.accountId, slot, slot + FIVE_HOURS, share, current = false)
                taken += slot to slot + FIVE_HOURS
                left[pick.accountId] = left.getValue(pick.accountId) - share
                busyUntil[pick.accountId] = slot + FIVE_HOURS
            }
            // What is still left at a reset inside the horizon is lost.
            budgets.forEach { b ->
                val reset = resets[b.accountId] ?: return@forEach
                val k = calibrated[b.accountId] ?: return@forEach
                if (reset < now + HORIZON_DAYS * 86_400L && reset == b.weekReset) expiring[b.accountId] = (expiring[b.accountId] ?: 0.0) + (left[b.accountId] ?: 0.0) * k
            }
        }
        val withReset = budgets.filter { it.weekReset != null }
        val overlaps = withReset.flatMapIndexed { i, a -> withReset.drop(i + 1).filter { b -> kotlin.math.abs(a.weekReset!! - b.weekReset!!) <= 86_400 }.map { a.accountId to it.accountId } }
        return Plan(budgets, order, sessions.sortedBy { it.start }, expiring.filterValues { it >= 1.0 }, overlaps, hours)
    }
}
