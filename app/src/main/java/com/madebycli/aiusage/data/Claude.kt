package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

// Only independently obtained phone logins retain a refresh token. Imports
// remain read-only so rotation cannot invalidate the desktop session.
@Serializable
class ClaudeCredential(val access: String, val expiresAt: Long? = null, val plan: String? = null,
    val refresh: String? = null, val phoneLogin: Boolean = false) {
    override fun toString() = "ClaudeCredential(redacted)"
}

object ClaudeParser {
    fun profilePlan(body: String): String? {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        val org = root.obj("organization")
        val account = root.obj("account")
        val tier = org?.text("rate_limit_tier").orEmpty()
        val plan = when (org?.text("organization_type")) {
            "claude_enterprise", "enterprise" -> "Enterprise"
            "claude_team", "team" -> "Team"
            "claude_max" -> "Max"
            "claude_pro" -> "Pro"
            else -> when {
                account?.get("has_claude_max")?.jsonPrimitive?.booleanOrNull == true -> "Max"
                account?.get("has_claude_pro")?.jsonPrimitive?.booleanOrNull == true -> "Pro"
                else -> null
            }
        }
        // False Pro/Max flags do not mean Free: Team/Enterprise also have them.
        return plan?.let { it + if (it == "Max") when { "20x" in tier -> " 20x"; "5x" in tier -> " 5x"; else -> "" } else "" }
    }
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
    fun parse(body: String, at: Long, plan: String?): UsageSnapshot = ClaudeUsageParser.parse(body, at, plan)

}

class ClaudeRepository(private val http: HttpTransport, private val endpoint: String = "https://api.anthropic.com/api/oauth/usage?cedar_ember=1") {
    private val backoff = RequestBackoff()
    suspend fun profilePlan(credential: ClaudeCredential): String? = ClaudeParser.profilePlan(http.get(
        "https://api.anthropic.com/api/oauth/profile", mapOf("Authorization" to "Bearer ${credential.access}", "anthropic-beta" to "oauth-2025-04-20")))
    suspend fun fetch(credential: ClaudeCredential): UsageSnapshot {
        backoff.check()
        if (credential.expiresAt?.let { it <= Instant.now().epochSecond } == true) throw HttpFailure(401)
        try {
            val body = http.get(endpoint, mapOf("Authorization" to "Bearer ${credential.access}",
                "anthropic-beta" to "oauth-2025-04-20",
                // Compatibility marker from the reference; retain our explicit app identity.
                "User-Agent" to "claude-cli/2.1.281 (external, cli) AiUsage-Android/0.4.1 (+https://github.com/madebycli/AiUsage-Android)"))
            return ClaudeParser.parse(body, Instant.now().epochSecond, credential.plan)
        } catch (e: HttpFailure) { if (e.status == 429) backoff.defer(e.retrySeconds ?: 60); throw e }
        catch (e: SchemaException) {
            if (e is ClaudeUsageFormatException) throw e
            throw ClaudeUsageFormatException(ClaudeUsageSection.RESPONSE)
        }
    }
}
