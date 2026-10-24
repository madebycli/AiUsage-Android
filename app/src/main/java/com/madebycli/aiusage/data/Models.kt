package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime

const val FIVE_HOURS = 18_000L
const val ONE_WEEK = 604_800L

@Serializable
data class UsageWindow(val usedPercent: Double, val seconds: Long, val resetAt: Long? = null) {
    val title: String get() = when {
        seconds == FIVE_HOURS -> "5 Stunden"
        seconds == ONE_WEEK -> "Wöchentlich"
        seconds % 86_400 == 0L -> "${seconds / 86_400} Tage"
        seconds % 3_600 == 0L -> "${seconds / 3_600} Stunden"
        else -> "${seconds / 60} Minuten"
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
) {
    fun windows(provider: String): List<UsageWindow> = groups.firstOrNull { it.name == provider }?.windows.orEmpty()
}
@Serializable
data class AccountCredits(val available: Boolean?, val unlimited: Boolean?, val balance: Double?)
@Serializable
data class SpendingLimit(val limit: Double?, val used: Double?, val remainingPercent: Double?, val resetAt: Long?)
@Serializable
data class ExtraUsage(val enabled: Boolean?, val used: Double?, val limit: Double?, val currency: String?, val decimals: Int?)
@Serializable
data class PersonalRecord(val usage: UsageSnapshot? = null, val days: List<UsageDay> = emptyList(), val ledger: UsageLedger = UsageLedger())
/** Accepts `Z` and numeric offsets such as `+00:00`; older Android runtimes only parse the former. */
fun parseInstant(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val instant = runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: return null
    return instant.epochSecond + if (instant.nano >= 500_000_000) 1 else 0
}
