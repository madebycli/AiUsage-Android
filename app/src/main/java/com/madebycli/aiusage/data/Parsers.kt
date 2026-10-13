package com.madebycli.aiusage.data

import kotlinx.serialization.json.*

val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
open class SchemaException : Exception("Das Datenformat des Anbieters hat sich geändert.")
internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray
internal fun JsonObject.number(key: String): Double? = text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }
internal fun JsonObject.integer(key: String): Long? = text(key)?.toLongOrNull()

object UsageParser {
    fun parse(body: String, fetchedAt: Long): UsageSnapshot {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        if (!root.containsKey("rate_limit")) throw SchemaException()
        listOf("rate_limit", "code_review_rate_limit", "rate_limit_reset_credits").forEach { key ->
            val value = root[key]
            if (value != null && value != JsonNull && value !is JsonObject) throw SchemaException()
        }
        root["additional_rate_limits"]?.let { if (it != JsonNull && it !is JsonArray) throw SchemaException() }
        val groups = mutableListOf<QuotaGroup>()
        root.obj("rate_limit")?.let { groups += group("Codex", it, fetchedAt) }
        root.array("additional_rate_limits")?.forEach { element ->
            val item = element as? JsonObject ?: throw SchemaException()
            item.obj("rate_limit")?.let { groups += group(item.text("limit_name") ?: item.text("metered_feature") ?: "Zusätzliches Limit", it, fetchedAt) }
        }
        root.obj("code_review_rate_limit")?.let { groups += group("Code Review", it, fetchedAt) }
        val warnings = mutableListOf<String>()
        fun amount(obj: JsonObject, key: String): Double? {
            if (obj[key] == null || obj[key] == JsonNull) return null
            return obj.number(key)?.takeIf { kotlin.math.abs(it) <= 9_007_199_254_740_991.0 }.also {
                if (it == null) warnings += "Ein Zusatzwert konnte nicht gelesen werden."
            }
        }
        val credits = root.obj("credits")?.let { block -> AccountCredits(
            (block["has_credits"] as? JsonPrimitive)?.booleanOrNull, (block["unlimited"] as? JsonPrimitive)?.booleanOrNull, amount(block, "balance")) }
        val spending = root.obj("spend_control")?.obj("individual_limit")?.let { block -> SpendingLimit(
            amount(block, "limit"), amount(block, "used"), amount(block, "remaining_percent")?.takeIf { it in 0.0..100.0 },
            block.integer("resets_at")?.takeIf { it in 1..253_402_300_799L }) }
        return UsageSnapshot(root.text("plan_type"), groups, fetchedAt, count(root.obj("rate_limit_reset_credits")),
            warnings = warnings.distinct(), accountCredits = credits, spending = spending)
    }
    fun credits(body: String): List<ResetCredit> {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        val credits = root.array("credits") ?: throw SchemaException()
        return credits.map { element ->
            val credit = element as? JsonObject ?: throw SchemaException()
            ResetCredit(credit.text("title") ?: credit.text("reset_type") ?: "Gespeicherter Reset",
                credit.text("status") ?: "unknown", parseInstant(credit.text("granted_at")), parseInstant(credit.text("expires_at")))
        }
    }
    private fun count(block: JsonObject?): Int? {
        if (block == null) return null
        return block.integer("available_count")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: throw SchemaException()
    }
    private fun group(name: String, block: JsonObject, now: Long): QuotaGroup {
        val windows = listOf("primary_window", "secondary_window").mapNotNull { key ->
            val raw = block[key]
            if (raw == null || raw == JsonNull) return@mapNotNull null
            val obj = raw as? JsonObject ?: throw SchemaException()
            val used = obj.number("used_percent")?.takeIf { it in 0.0..101.0 } ?: throw SchemaException()
            val seconds = obj.integer("limit_window_seconds")?.takeIf { it in 1..315_360_000L } ?: throw SchemaException()
            val at = if (obj["reset_at"] != null && obj["reset_at"] != JsonNull) {
                obj.integer("reset_at")?.takeIf { it in 1..253_402_300_799L } ?: throw SchemaException()
            } else obj.integer("reset_after_seconds")?.takeIf { it in 0..315_360_000L }?.let { now + it }
            UsageWindow(used, seconds, at)
        }.sortedBy { it.seconds }
        if (windows.map { it.seconds }.distinct().size != windows.size) throw SchemaException()
        return QuotaGroup(name, windows)
    }
}

object ResetParser {
    fun parse(source: ResetSource, body: String, fetchedAt: Long): ResetFeed {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        val events = when (source) {
            ResetSource.CODEX_RESET -> {
                val rows = root.array("events") ?: throw SchemaException()
                rows.mapNotNull { element ->
                    val row = element as? JsonObject ?: throw SchemaException()
                    val isReset = row.text("group") == "reset" && row.text("announcement_state") == "announced"
                    // Supplemental banked state never advances the global-reset timestamp.
                    val banked = row.text("banked_state")
                    if (!isReset && banked == null) return@mapNotNull null
                    val at = parseInstant(row.text("announced_at")) ?: return@mapNotNull null
                    ResetEvent(row.text("id") ?: "${row.text("group")}:$at", at,
                        if (isReset) "Globaler Reset" else "Banked · $banked",
                        row.text("summary") ?: "Reset-Meldung", safeLink(row.text("url")))
                }
            }
            ResetSource.LUNARWERX -> {
                val rows = root.array("resets") ?: throw SchemaException()
                rows.mapNotNull { element ->
                    val row = element as? JsonObject ?: throw SchemaException()
                    val at = parseInstant(row.text("at")) ?: return@mapNotNull null
                    val announcement = row.obj("announcement")
                    // This ledger includes banked grants; its stable API does not classify individual rows.
                    ResetEvent("lunar:$at", at, "Ledger-Ereignis", announcement?.text("text") ?: "Ereignis im LunarWerx-Reset-Verlauf", safeLink(announcement?.text("url")))
                }
            }
        }
        return ResetFeed(source, events.distinctBy { it.id }.sortedByDescending { it.at },
            parseInstant(root.text(if (source == ResetSource.CODEX_RESET) "updated_at" else "generatedAt")), fetchedAt)
    }
    private fun safeLink(url: String?): String? = url?.takeIf {
        runCatching { val uri = java.net.URI(it); uri.scheme == "https" && uri.host != null && uri.userInfo == null }.getOrDefault(false)
    }
}
