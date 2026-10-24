package com.madebycli.aiusage.data

/**
 * How the usage so far compares with the time that has passed in a window. This is arithmetic on two reported
 * numbers (percent used and the window's start and end), shown as an estimate and never stored.
 */
data class Pace(
    /** Share of the window's time that has passed, 0 to 1. */
    val elapsed: Double,
    /** Usage at the reset if the pace stays the same, in percent. */
    val projectedPercent: Double,
    /** When the limit would be full at this pace, if that happens before the reset. */
    val fullAt: Long?,
    val status: Status
) {
    enum class Status { FULL, WILL_RUN_OUT, ON_TRACK }

    companion object {
        /** Too little of the window has passed to say anything. */
        const val MIN_ELAPSED = 0.08

        fun of(window: UsageWindow, now: Long): Pace? {
            val end = window.resetAt ?: return null
            val start = end - window.seconds
            if (now >= end || now <= start) return null
            val elapsed = ((now - start).toDouble() / window.seconds).coerceIn(0.0, 1.0)
            val used = window.usedPercent.coerceIn(0.0, 100.0)
            if (used >= 100.0) return Pace(elapsed, 100.0, null, Status.FULL)
            if (elapsed < MIN_ELAPSED || used <= 0.0) return null
            val projected = used / elapsed
            if (projected < 100.0) return Pace(elapsed, projected, null, Status.ON_TRACK)
            val fullAt = start + ((now - start) * (100.0 / used)).toLong()
            return Pace(elapsed, projected.coerceAtMost(999.0), fullAt.takeIf { it < end }, Status.WILL_RUN_OUT)
        }
    }
}
