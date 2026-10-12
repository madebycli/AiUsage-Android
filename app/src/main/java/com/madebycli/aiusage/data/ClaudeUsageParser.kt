package com.madebycli.aiusage.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.text.NumberFormat
import java.util.Locale

enum class ClaudeUsageSection { RESPONSE, WINDOWS }
class ClaudeUsageFormatException(val section: ClaudeUsageSection) : SchemaException()

// A malformed optional field must never erase independently valid quota data.
object ClaudeUsageParser {
    fun parse(body: String, at: Long, plan: String?): UsageSnapshot {
        val root = try { appJson.parseToJsonElement(body) as? JsonObject }
        catch (_: SerializationException) { null }
            ?: throw ClaudeUsageFormatException(ClaudeUsageSection.RESPONSE)
        if (listOf("five_hour", "seven_day", "limits", "extra_usage").none(root::containsKey))
            throw ClaudeUsageFormatException(ClaudeUsageSection.RESPONSE)
        val warnings = linkedSetOf<String>()
        var invalidWindow = false
        val main = linkedMapOf<Long, UsageWindow>()
        val models = mutableListOf<QuotaGroup>()
        fun window(value: JsonElement?, seconds: Long, field: String = "utilization"): UsageWindow? {
            if (value == null || value == JsonNull) return null
            val obj = value as? JsonObject ?: run {
                invalidWindow = true
                warnings += "Einzelne Claude-Zeitfenster konnten nicht gelesen werden."
                return null
            }
            val raw = obj[field]
            if (raw == null || raw == JsonNull) return null
            val percent = obj.number(field)?.takeIf { it in 0.0..101.0 }
            if (percent == null) {
                invalidWindow = true
                warnings += "Einzelne Claude-Zeitfenster konnten nicht gelesen werden."
                return null
            }
            val reset = obj.text("resets_at")?.let(::parseInstant)
            if (obj["resets_at"] != null && obj["resets_at"] != JsonNull && reset == null)
                warnings += "Eine Reset-Zeit wurde nicht erkannt."
            return UsageWindow(percent.coerceAtMost(100.0), seconds, reset)
        }
        listOf("five_hour" to 18000L, "seven_day" to 604800L).forEach { (key, seconds) ->
            window(root[key], seconds)?.let { main[seconds] = it }
        }
        root.entries.filter { it.key.startsWith("seven_day_") }.forEach { (key, value) ->
            // Future codename metadata is not necessarily a quota window.
            if (value !is JsonObject || !value.containsKey("utilization")) return@forEach
            window(value, 604800)?.let {
                val label = key.removePrefix("seven_day_").split('_').joinToString(" ") { part -> part.replaceFirstChar(Char::titlecase) }
                models += QuotaGroup(label.take(120), listOf(it))
            }
        }
        val limits = root["limits"]
        if (limits != null && limits != JsonNull && limits !is JsonArray)
            warnings += "Modelllimits konnten teilweise nicht gelesen werden."
        (limits as? JsonArray)?.forEach { element ->
            val entry = element as? JsonObject
            if (entry == null) { warnings += "Modelllimits konnten teilweise nicht gelesen werden."; return@forEach }
            when (entry.text("kind")) {
                "session" -> if (main[18000L] == null) window(entry, 18000, "percent")?.let { main[18000L] = it }
                "weekly_all" -> if (main[604800L] == null) window(entry, 604800, "percent")?.let { main[604800L] = it }
                "weekly_scoped" -> {
                    val name = entry.obj("scope")?.obj("model")?.text("display_name") ?: return@forEach
                    window(entry, 604800, "percent")?.let { scoped ->
                        if (models.none { it.name.equals(name, true) }) models += QuotaGroup(name.take(120), listOf(scoped))
                    }
                }
            }
        }
        if (main.isEmpty() && models.isEmpty() && invalidWindow)
            throw ClaudeUsageFormatException(ClaudeUsageSection.WINDOWS)

        val extra = optionalObject(root, "extra_usage", warnings, "Zusatzverbrauch konnte teilweise nicht gelesen werden.")?.let { e ->
            fun bad() { warnings += "Zusatzverbrauch konnte teilweise nicht gelesen werden." }
            fun money(key: String): Double? {
                if (e[key] == null || e[key] == JsonNull) return null
                // The API reports fractional minor units as well as integer values.
                return e.number(key)?.takeIf { it >= 0 && it <= 9_007_199_254_740_991.0 }
                    .also { if (it == null) bad() }
            }
            val currency = e.text("currency")?.uppercase(Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z]{3}")) }
            if (e["currency"] != null && e["currency"] != JsonNull && currency == null) bad()
            val decimals = e.number("decimal_places")?.takeIf { it % 1.0 == 0.0 && it in 0.0..6.0 }?.toInt()
            if (e["decimal_places"] != null && e["decimal_places"] != JsonNull && decimals == null) bad()
            val enabled = (e["is_enabled"] as? JsonPrimitive)?.booleanOrNull
            if (e["is_enabled"] != null && e["is_enabled"] != JsonNull && enabled == null) bad()
            ExtraUsage(enabled, money("used_credits"), money(if (e.containsKey("monthly_limit")) "monthly_limit" else "monthly_credit_limit"), currency, decimals)
        }
        val block = optionalObject(root, "cedar_ember", warnings, "Reset-Details konnten nicht gelesen werden.")
        var credits = emptyList<ResetCredit>()
        var available: Int? = null
        var creditWarning: String? = null
        if (block != null && (block["eligible"] as? JsonPrimitive)?.booleanOrNull != false) {
            try {
                val raw = block["grants"]
                if (raw != null && raw != JsonNull) {
                    val grants = raw as? JsonArray ?: throw SchemaException()
                    var unknown = false
                    var count = 0L
                    credits = grants.map { element ->
                        val grant = element as? JsonObject ?: throw SchemaException()
                        fun integer(key: String): Int? = if (grant[key] == null || grant[key] == JsonNull) null else
                            grant.number(key)?.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() && it % 1.0 == 0.0 }?.toInt() ?: throw SchemaException()
                        fun bound(key: String): Long? = if (grant[key] == null || grant[key] == JsonNull) null else
                            grant.text(key)?.let(::parseInstant) ?: throw SchemaException()
                        val left = integer("resets_left") ?: throw SchemaException()
                        val total = integer("resets_total")
                        if (total != null && left > total) throw SchemaException()
                        val starts = bound("starts_at")
                        val ends = bound("ends_at")
                        val explicit = (grant["usable_now"] as? JsonPrimitive)?.booleanOrNull
                        val paused = (grant["paused"] as? JsonPrimitive)?.booleanOrNull
                        if (grant["usable_now"] != null && grant["usable_now"] != JsonNull && explicit == null) throw SchemaException()
                        if (grant["paused"] != null && grant["paused"] != JsonNull && paused == null) throw SchemaException()
                        val status = when {
                            left == 0 -> "redeemed"
                            paused == true -> "paused"
                            ends != null && ends <= at -> "expired"
                            starts != null && starts > at -> "unavailable"
                            explicit == false -> "unavailable"
                            explicit == true || paused == false -> "available"
                            else -> { unknown = true; "unknown" }
                        }
                        if (status == "available") count += left
                        ResetCredit(grant.text("label")?.take(240) ?: "Gespeicherter Reset", status, starts, ends)
                    }
                    if ((block["eligible"] as? JsonPrimitive)?.booleanOrNull == true && !unknown && count <= Int.MAX_VALUE)
                        available = count.toInt()
                    if (unknown || count > Int.MAX_VALUE) creditWarning = "Reset-Verfügbarkeit konnte nicht vollständig bestätigt werden."
                }
            } catch (_: SchemaException) {
                // Malformed personal inventory is unknown, never zero or a partial sum.
                credits = emptyList()
                creditWarning = "Reset-Details konnten nicht gelesen werden."
                warnings += "Reset-Details konnten nicht gelesen werden."
            }
        }
        val groups = buildList {
            if (main.isNotEmpty()) add(QuotaGroup("Claude Code", main.values.sortedBy { it.seconds }))
            addAll(models)
        }
        return UsageSnapshot(plan, groups, at, available, credits, creditWarning,
            creditsFetchedAt = if (block != null) at else null, extraUsage = extra, warnings = warnings.toList())
    }

    private fun optionalObject(root: JsonObject, key: String, warnings: MutableSet<String>, warning: String): JsonObject? {
        val value = root[key]
        if (value == null || value == JsonNull) return null
        return (value as? JsonObject).also { if (it == null) warnings += warning }
    }
}

fun extraAmountText(value: Double?, currency: String?, decimals: Int?): String {
    if (value == null) return "Nicht gemeldet"
    val format = NumberFormat.getNumberInstance(Locale.GERMANY).apply { maximumFractionDigits = 6 }
    if (currency == null || decimals == null)
        return "${format.format(value)} gemeldete Einheiten${currency?.let { " ($it)" }.orEmpty()}"
    format.minimumFractionDigits = decimals
    format.maximumFractionDigits = decimals
    return "${format.format(value / Math.pow(10.0, decimals.toDouble()))} $currency"
}
