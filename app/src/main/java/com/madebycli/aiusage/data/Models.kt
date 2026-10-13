package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class UsageWindow(val usedPercent: Double, val seconds: Long, val resetAt: Long? = null) {
    val title: String get() = when (seconds) {
        18_000L -> "5 Stunden"
        604_800L -> "Wöchentlich"
        else -> if (seconds % 86400 == 0L) "${seconds / 86400} Tage" else "${seconds / 3600.0} Stunden"
    }
    fun elapsed(now: Long): Float? = resetAt?.let { (1.0 - (it - now).toDouble() / seconds).toFloat().coerceIn(0f, 1f) }
}
@Serializable
data class QuotaGroup(val name: String, val windows: List<UsageWindow>)
@Serializable
data class ResetCredit(val title: String, val status: String, val grantedAt: Long?, val expiresAt: Long?)
@Serializable
data class UsageSnapshot(
    val plan: String?, val groups: List<QuotaGroup>, val fetchedAt: Long,
    val availableResets: Int? = null, val resetCredits: List<ResetCredit> = emptyList(),
    val creditsWarning: String? = null, val creditsFetchedAt: Long? = null,
    val extraUsage: ExtraUsage? = null, val warnings: List<String> = emptyList(),
    val accountCredits: AccountCredits? = null, val spending: SpendingLimit? = null
)
@Serializable
data class AccountCredits(val available: Boolean?, val unlimited: Boolean?, val balance: Double?)
@Serializable
data class SpendingLimit(val limit: Double?, val used: Double?, val remainingPercent: Double?, val resetAt: Long?)
@Serializable
data class ExtraUsage(val enabled: Boolean?, val used: Double?, val limit: Double?, val currency: String?, val decimals: Int?)
@Serializable
data class ResetObservation(val detectedAt: Long, val windowTitle: String, val before: Double, val after: Double)
@Serializable
data class PersonalRecord(val usage: UsageSnapshot? = null, val observations: List<ResetObservation> = emptyList(), val days: List<UsageDay> = emptyList())
@Serializable
data class ResetEvent(val id: String, val at: Long, val kind: String, val summary: String, val url: String?)
@Serializable
data class ResetFeed(val source: ResetSource, val events: List<ResetEvent>, val publishedAt: Long?, val fetchedAt: Long)
@Serializable
enum class ResetSource(val label: String, val site: String, val endpoint: String, val minimumSeconds: Long) {
    CODEX_RESET("codex-reset.com", "https://codex-reset.com", "https://codex-reset.com/api/timeline", 60),
    LUNARWERX("LunarWerx", "https://codex.lunarwerx.com", "https://codex.lunarwerx.com/api/v1/resets?limit=100", 300)
}

// Quota changes are observations, not proof of a goodwill reset or a precise event time.
fun observeResets(previous: UsageSnapshot?, current: UsageSnapshot): List<ResetObservation> {
    if (previous == null) return emptyList()
    val before = previous.groups.firstOrNull { it.name == "Codex" }?.windows.orEmpty()
    return current.groups.firstOrNull { it.name == "Codex" }?.windows.orEmpty().mapNotNull { window ->
        val old = before.firstOrNull { it.seconds == window.seconds } ?: return@mapNotNull null
        if (window.usedPercent < old.usedPercent && (old.resetAt == null || old.resetAt > previous.fetchedAt))
            ResetObservation(current.fetchedAt, window.title, old.usedPercent, window.usedPercent)
        else null
    }
}

fun usageRate(previous: UsageSnapshot?, current: UsageSnapshot, window: UsageWindow): Double? {
    val old = previous?.groups?.firstOrNull { it.name == "Codex" }?.windows?.firstOrNull { it.seconds == window.seconds }
        ?: return null
    val elapsed = current.fetchedAt - previous.fetchedAt
    if (elapsed <= 0 || old.resetAt == null || old.resetAt != window.resetAt || window.usedPercent < old.usedPercent) return null
    return (window.usedPercent - old.usedPercent) / elapsed
}

fun parseInstant(value: String?): Long? = value?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
