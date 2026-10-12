package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

// Imported CLI credentials are read-only. Never rotate a copied refresh token:
// doing so could invalidate the user's desktop Claude Code session.
@Serializable
class ClaudeCredential(val access: String, val expiresAt: Long? = null, val plan: String? = null) {
    override fun toString() = "ClaudeCredential(redacted)"
}

object ClaudeParser {
    fun credential(raw: String): ClaudeCredential {
        if (raw.length !in 1..65_536) throw SchemaException()
        val value = raw.trim()
        if (!value.startsWith("{")) {
            require(value.startsWith("sk-ant-oat") && value.none { it.isWhitespace() }) { "Claude-Code-OAuth-Token erforderlich." }
            return ClaudeCredential(value)
        }
        val root = appJson.parseToJsonElement(value) as? JsonObject ?: throw SchemaException()
        val oauth = root.obj("claudeAiOauth") ?: throw SchemaException()
        val access = oauth.text("accessToken")?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) } ?: throw SchemaException()
        val expiry = oauth.number("expiresAt")?.takeIf { it > 0 && it < 253_402_300_799_000.0 }?.toLong()?.div(1000)
        val sub = oauth.text("subscriptionType")?.take(80)?.replaceFirstChar { it.titlecase() }
        val tier = oauth.text("rateLimitTier").orEmpty()
        val plan = sub?.let { it + when { "20x" in tier -> " 20x"; "5x" in tier -> " 5x"; else -> "" } }
        return ClaudeCredential(access, expiry, plan)
    }
    fun parse(body: String, at: Long, plan: String?): UsageSnapshot {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        if (listOf("five_hour", "seven_day", "limits", "extra_usage").none(root::containsKey)) throw SchemaException()
        fun window(obj: JsonObject, seconds: Long, field: String = "utilization"): UsageWindow {
            val percent = obj.number(field)?.takeIf { it in 0.0..101.0 } ?: throw SchemaException()
            val reset = obj.text("resets_at")?.let { parseInstant(it) ?: throw SchemaException() }
            return UsageWindow(percent.coerceAtMost(100.0), seconds, reset)
        }
        val groups = mutableListOf<QuotaGroup>()
        val main = listOf("five_hour" to 18000L, "seven_day" to 604800L).mapNotNull { (key, seconds) ->
            val value = root[key] ?: return@mapNotNull null
            if (value == JsonNull) null else window(value as? JsonObject ?: throw SchemaException(), seconds)
        }
        if (main.isNotEmpty()) groups += QuotaGroup("Claude Code", main)
        root.entries.filter { it.key.startsWith("seven_day_") && it.value != JsonNull }.forEach { (key, value) ->
            val label = key.removePrefix("seven_day_").split('_').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
            groups += QuotaGroup(label, listOf(window(value as? JsonObject ?: throw SchemaException(), 604800)))
        }
        root.array("limits")?.forEach { element ->
            val entry = element as? JsonObject ?: throw SchemaException()
            if (entry.text("kind") == "weekly_scoped") {
                val name = entry.obj("scope")?.obj("model")?.text("display_name") ?: return@forEach
                val scoped = window(entry, 604800, "percent")
                if (groups.none { it.name.equals(name, true) }) groups += QuotaGroup(name.take(120), listOf(scoped))
            }
        }
        val block = root.obj("cedar_ember")
        val grants = block?.array("grants").orEmpty().map { it as? JsonObject ?: throw SchemaException() }
        fun count(obj: JsonObject) = obj.integer("resets_left")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: throw SchemaException()
        val credits = grants.map { grant ->
            val remaining = count(grant)
            val usable = grant["usable_now"]?.jsonPrimitive?.booleanOrNull == true
            val paused = grant["paused"]?.jsonPrimitive?.booleanOrNull == true
            ResetCredit(grant.text("label")?.take(240) ?: "Gespeicherter Reset", when {
                remaining == 0 -> "redeemed"; paused -> "paused"; usable -> "available"; else -> "unavailable"
            }, null, parseInstant(grant.text("ends_at")))
        }
        val available = if (block == null) null else grants.filter {
            it["usable_now"]?.jsonPrimitive?.booleanOrNull == true && it["paused"]?.jsonPrimitive?.booleanOrNull != true
        }.sumOf { count(it).toLong() }.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw SchemaException()
        val extra = root.obj("extra_usage")?.let { e ->
            fun money(key: String): Double? = if (e[key] == null || e[key] == JsonNull) null else
                e.number(key)?.takeIf { it >= 0 && it % 1.0 == 0.0 && it <= 9_007_199_254_740_991.0 } ?: throw SchemaException()
            val currency = e.text("currency")?.also { if (!it.matches(Regex("[A-Z]{3}"))) throw SchemaException() }
            val decimals = e.number("decimal_places")?.let { if (it % 1.0 != 0.0 || it !in 0.0..6.0) throw SchemaException(); it.toInt() }
            ExtraUsage(e["is_enabled"]?.jsonPrimitive?.booleanOrNull == true, money("used_credits"), money("monthly_limit"), currency, decimals)
        }
        return UsageSnapshot(plan, groups, at, available, credits, creditsFetchedAt = at, extraUsage = extra)
    }
}

class ClaudeRepository(private val http: HttpTransport, private val endpoint: String = "https://api.anthropic.com/api/oauth/usage?cedar_ember=1") {
    private val backoff = RequestBackoff()
    suspend fun fetch(credential: ClaudeCredential): UsageSnapshot {
        backoff.check()
        if (credential.expiresAt?.let { it <= Instant.now().epochSecond } == true) throw HttpFailure(401)
        try {
            val body = http.get(endpoint, mapOf("Authorization" to "Bearer ${credential.access}",
                "anthropic-beta" to "oauth-2025-04-20"))
            return ClaudeParser.parse(body, Instant.now().epochSecond, credential.plan)
        } catch (e: HttpFailure) { if (e.status == 429) backoff.defer(e.retrySeconds ?: 60); throw e }
    }
}
