package com.madebycli.aiusage.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

interface ClaudeCredentialStore {
    fun readClaude(): ClaudeCredential?
    fun writeClaude(value: ClaudeCredential)
    fun readClaudeLogin(): ClaudePendingLogin?
    fun writeClaudeLogin(value: ClaudePendingLogin)
    fun clearClaudeLogin()
}

@Serializable
class ClaudePendingLogin(val verifier: String, val state: String, val expiresAt: Long, val replaceId: String? = null) {
    override fun toString() = "ClaudePendingLogin(redacted)"
}

class ClaudeLoginFailure(message: String) : Exception(message)

// An independent phone grant. Never refresh a credential copied from Claude Code.
// Protocol reference: G-biggy/claude-pulse-android, TokenManager.kt (MIT).
class ClaudeAuth(
    private val http: HttpTransport,
    private val store: ClaudeCredentialStore,
    private val tokenEndpoint: String = TOKEN_ENDPOINT,
    private val clock: () -> Long = { Instant.now().epochSecond }
) {
    private val mutex = Mutex()
    private val backoff = RequestBackoff()

    suspend fun begin(replaceId: String? = null): String = mutex.withLock {
        val pending = ClaudePendingLogin(random(), random(), clock() + 600, replaceId)
        store.writeClaudeLogin(pending)
        url(pending)
    }

    suspend fun pendingUrl(): String? = mutex.withLock {
        val pending = store.readClaudeLogin() ?: return@withLock null
        if (pending.expiresAt <= clock()) { store.clearClaudeLogin(); null } else url(pending)
    }

    /** The account a pending sign-in is meant to renew, if any. */
    suspend fun pendingReplaceId(): String? = mutex.withLock { store.readClaudeLogin()?.replaceId }

    suspend fun cancel() = mutex.withLock { store.clearClaudeLogin() }

    suspend fun complete(pasted: String): ClaudeCredential = mutex.withLock {
        val pending = store.readClaudeLogin() ?: throw ClaudeLoginFailure("Bitte eine neue Browser-Anmeldung starten.")
        if (pending.expiresAt <= clock()) {
            store.clearClaudeLogin()
            throw ClaudeLoginFailure("Anmeldung abgelaufen. Bitte neu starten.")
        }
        // The hosted callback displays code#state. Require the whole value, not
        // merely a code from an unrelated login. No clipboard reads or URL fetches.
        val parts = pasted.trim().split('#')
        if (pasted.length > 4096 || parts.size != 2 ||
            !parts[0].matches(Regex("[A-Za-z0-9._~+\\-/=]{1,2048}")) ||
            !MessageDigest.isEqual(parts[1].toByteArray(), pending.state.toByteArray())) {
            throw ClaudeLoginFailure("Bitte den vollständigen Code aus dieser Browser-Freigabe einfügen, einschließlich # und dem Text dahinter.")
        }
        backoff.check()
        // Consume before sending. A failed/uncertain exchange must not replay a
        // one-time code, including after process death.
        store.clearClaudeLogin()
        token(mapOf("grant_type" to "authorization_code", "code" to parts[0],
            "redirect_uri" to REDIRECT_URI, "client_id" to CLIENT_ID,
            "code_verifier" to pending.verifier, "state" to pending.state), null)
    }

    suspend fun credential(forceRefresh: Boolean = false): ClaudeCredential = mutex.withLock {
        val current = store.readClaude() ?: throw HttpFailure(401)
        if (!forceRefresh && (current.expiresAt == null || current.expiresAt > clock() + 60)) return@withLock current
        if (!current.phoneLogin || current.refresh == null) {
            if (forceRefresh || current.expiresAt?.let { it <= clock() } == true) throw HttpFailure(401)
            return@withLock current
        }
        backoff.check()
        val next = try {
            token(mapOf("grant_type" to "refresh_token", "refresh_token" to current.refresh, "client_id" to CLIENT_ID), current)
        } catch (e: HttpFailure) {
            // Do not keep retrying a definitively rejected rotating chain.
            if (e.status in listOf(400, 401)) {
                withContext(NonCancellable) { store.writeClaude(ClaudeCredential(current.access, 0, current.plan, phoneLogin = true)) }
                throw HttpFailure(401)
            }
            throw e
        }
        // Persist the rotated refresh token before using the new access token.
        withContext(NonCancellable) { store.writeClaude(next) }
        next
    }

    private suspend fun token(fields: Map<String, String>, previous: ClaudeCredential?): ClaudeCredential {
        val body = try { http.postJson(tokenEndpoint, JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()) }
        catch (e: HttpFailure) { if (e.status == 429) backoff.defer(e.retrySeconds ?: 60); throw e }
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        fun secret(key: String): String? = root.text(key)?.also {
            if (it.length !in 1..65_536 || it.any(Char::isWhitespace)) throw SchemaException()
        }
        val access = secret("access_token") ?: throw SchemaException()
        val refresh = secret("refresh_token") ?: previous?.refresh
        val seconds = root.integer("expires_in")?.takeIf { it in 1..31_536_000 } ?: throw SchemaException()
        root.text("token_type")?.let { if (!it.equals("Bearer", true)) throw SchemaException() }
        root.text("scope")?.let { if ("user:profile" !in it.split(' ')) throw ClaudeLoginFailure("Die Freigabe enthält keinen Zugriff auf Verbrauchsdaten. Bitte neu anmelden.") }
        val organization = root.obj("organization")
        val tier = organization?.text("rate_limit_tier").orEmpty()
        val plan = previous?.plan ?: when {
            "20x" in tier -> "Max 20x"
            "5x" in tier -> "Max 5x"
            else -> null
        }
        return ClaudeCredential(access, clock() + seconds, plan, refresh, phoneLogin = true)
    }

    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun url(pending: ClaudePendingLogin): String = AUTHORIZE_URI.toHttpUrl().newBuilder()
        .addQueryParameter("code", "true").addQueryParameter("client_id", CLIENT_ID)
        .addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", REDIRECT_URI)
        .addQueryParameter("scope", "user:profile user:inference")
        .addQueryParameter("code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pending.verifier.toByteArray(Charsets.US_ASCII))))
        .addQueryParameter("code_challenge_method", "S256").addQueryParameter("state", pending.state)
        .build().toString()

    companion object {
        const val AUTHORIZE_URI = "https://claude.com/cai/oauth/authorize"
        const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
        const val TOKEN_ENDPOINT = "https://platform.claude.com/v1/oauth/token"
        // Public Claude Code identifier, not a private app registration or secret.
        const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    }
}
