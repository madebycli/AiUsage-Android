package com.madebycli.aiusage.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/**
 * Gemini usage of personal Google accounts (Google AI Pro, Ultra, free tier).
 *
 * Since 18 June 2026 Google serves these accounts through Antigravity instead of the Gemini CLI. The sign-in and the
 * quota summary follow OpenUsage (github.com/robinebers/openusage, MIT, Sources/OpenUsage/Services/BrowserProviderLogins.swift
 * and Providers/Antigravity): Google OAuth with PKCE and a loopback redirect, the Antigravity installed-app client, then
 * `loadCodeAssist` and `retrieveUserQuotaSummary`. Both endpoints are internal to Google and may change without notice.
 */
@Serializable
class GeminiCredential(val access: String, val refresh: String?, val expiresAt: Long?, val email: String? = null, val id: String? = null) {
    override fun toString() = "GeminiCredential(redacted)"
}

object GeminiOAuth {
    /**
     * Google installed-app client, set at build time (`gemini.clientId` / `gemini.clientSecret` in local.properties or
     * the GEMINI_OAUTH_CLIENT_ID / GEMINI_OAUTH_CLIENT_SECRET environment). Not kept in the repository; without it Gemini is hidden.
     */
    val CLIENT_ID: String = com.madebycli.aiusage.BuildConfig.GEMINI_CLIENT_ID
    val CLIENT_SECRET: String = com.madebycli.aiusage.BuildConfig.GEMINI_CLIENT_SECRET
    val configured: Boolean get() = CLIENT_ID.isNotBlank() && CLIENT_SECRET.isNotBlank()
    const val PORT = 51121
    const val REDIRECT = "http://localhost:$PORT/oauth-callback"
    const val SCOPES = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email"
    const val AUTHORIZE = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN = "https://oauth2.googleapis.com/token"
    const val USERINFO = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json"

    class Attempt(val verifier: String, val state: String, val url: String)

    private fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun attempt(): Attempt {
        val verifier = random()
        val state = random()
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val url = AUTHORIZE.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID).addQueryParameter("redirect_uri", REDIRECT)
            .addQueryParameter("response_type", "code").addQueryParameter("scope", SCOPES)
            .addQueryParameter("code_challenge", challenge).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state).addQueryParameter("access_type", "offline").addQueryParameter("prompt", "consent")
            .build().toString()
        return Attempt(verifier, state, url)
    }

    /** Reads `code` from the address the browser was sent to; rejects a different state. */
    fun codeFrom(address: String, state: String): String {
        val url = runCatching { address.trim().toHttpUrl() }.getOrNull() ?: throw GeminiLoginFailure(GeminiLoginFailure.Reason.ADDRESS)
        url.queryParameter("error")?.let { throw GeminiLoginFailure(GeminiLoginFailure.Reason.DENIED) }
        if (url.queryParameter("state") != state) throw GeminiLoginFailure(GeminiLoginFailure.Reason.STATE)
        return url.queryParameter("code")?.takeIf { it.isNotBlank() } ?: throw GeminiLoginFailure(GeminiLoginFailure.Reason.ADDRESS)
    }

    suspend fun exchange(http: HttpTransport, code: String, verifier: String): GeminiCredential {
        val body = http.postForm(TOKEN, mapOf("client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET, "code" to code,
            "grant_type" to "authorization_code", "redirect_uri" to REDIRECT, "code_verifier" to verifier))
        val token = GeminiParser.token(body, Instant.now().epochSecond)
        val info = runCatching { appJson.parseToJsonElement(http.get(USERINFO, mapOf("Authorization" to "Bearer ${token.access}"))) as? JsonObject }.getOrNull()
        return GeminiCredential(token.access, token.refresh, token.expiresAt, info?.text("email")?.take(200), info?.text("id")?.take(100))
    }

    suspend fun refresh(http: HttpTransport, credential: GeminiCredential): GeminiCredential {
        val refresh = credential.refresh ?: throw HttpFailure(401)
        val body = http.postForm(TOKEN, mapOf("client_id" to CLIENT_ID, "client_secret" to CLIENT_SECRET, "refresh_token" to refresh, "grant_type" to "refresh_token"))
        val token = GeminiParser.token(body, Instant.now().epochSecond)
        return GeminiCredential(token.access, token.refresh ?: refresh, token.expiresAt, credential.email, credential.id)
    }

    /**
     * Waits for the browser to come back to `localhost:51121/oauth-callback` and answers it with a short page.
     * Returns the full callback address, or null when [timeoutMs] passes.
     */
    suspend fun awaitCallback(timeoutMs: Int, opened: (ServerSocket) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4)
            // Cancelling a coroutine does not interrupt accept(); the caller closes the socket to stop waiting.
            opened(server)
            server.soTimeout = timeoutMs
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { return@withContext null } catch (_: java.net.SocketException) { return@withContext null }
                socket.use {
                    it.soTimeout = 5_000
                    val line = it.getInputStream().bufferedReader().readLine().orEmpty()
                    val path = line.split(' ').getOrNull(1).orEmpty()
                    val page = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>AI Usage</title>" +
                        "<body style='font-family:sans-serif;background:#0c0e12;color:#e4e5ee;display:grid;place-items:center;height:90vh'><p>✓ AI Usage</p></body>"
                    val bytes = page.toByteArray()
                    it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                    it.getOutputStream().flush()
                    if (path.startsWith("/oauth-callback")) return@withContext "http://localhost:$PORT$path"
                }
            }
            null
        }
    }
}

class GeminiLoginFailure(val reason: Reason) : Exception(reason.name) { enum class Reason { ADDRESS, STATE, DENIED, TIMEOUT, NO_QUOTA } }

object GeminiParser {
    class Token(val access: String, val refresh: String?, val expiresAt: Long?)

    fun token(body: String, now: Long): Token {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        val access = root.text("access_token")?.takeIf { it.isNotBlank() } ?: throw SchemaException()
        return Token(access, root.text("refresh_token"), root.integer("expires_in")?.let { now + it })
    }

    /** "Google AI Pro" and friends from the tier `loadCodeAssist` reports. */
    fun plan(body: String): String? {
        val root = runCatching { appJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val paid = root.obj("paidTier"); val current = root.obj("currentTier")
        when (paid?.text("id")) { "g1-pro-tier" -> return "Google AI Pro"; "g1-ultra-tier" -> return "Google AI Ultra"; "g1-ultra-lite-tier" -> return "Google AI Ultra Lite" }
        val name = (paid?.text("name") ?: current?.text("name"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        listOf("Ultra Lite", "Ultra", "Pro").forEach { tier -> if (name.contains(tier, ignoreCase = true)) return "Google AI $tier" }
        return name.take(60)
    }

    fun project(body: String): String? = runCatching { (appJson.parseToJsonElement(body) as? JsonObject)?.text("cloudaicompanionProject") }.getOrNull()?.takeIf { it.isNotBlank() }

    private const val ARGON = "Gemini 4 Argon"
    const val GEMINI = "Gemini"
    const val OTHER = "Claude & GPT"
    private val buckets = listOf("gemini-5h" to (GEMINI to FIVE_HOURS), "gemini-weekly" to (GEMINI to ONE_WEEK), "3p-5h" to (OTHER to FIVE_HOURS), "3p-weekly" to (OTHER to ONE_WEEK))

    private fun groups(body: String): List<JsonObject>? {
        val root = runCatching { appJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val list = (root.obj("response")?.array("groups") ?: root.array("groups")) ?: return null
        return list.mapNotNull { it as? JsonObject }
    }

    fun isComplete(body: String): Boolean {
        val usable = groups(body)?.filter { it.text("displayName") != ARGON }?.flatMap { it.array("buckets").orEmpty() }
            ?.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.number("remainingFraction") != null }?.text("bucketId") }?.toSet() ?: return false
        return buckets.all { it.first in usable }
    }

    /**
     * Merges the answers of both hosts: for every known bucket the first usable value wins, in the order given
     * (daily host first, which has the live counters). Used percent is `(1 − remainingFraction) × 100`.
     */
    fun summary(bodies: List<String>, plan: String?, fetchedAt: Long): UsageSnapshot {
        val all = bodies.mapNotNull(::groups)
        if (all.isEmpty()) throw SchemaException()
        val found = linkedMapOf<String, UsageWindow>()
        var argon: UsageWindow? = null
        all.forEach { groups ->
            groups.forEach { group ->
                group.array("buckets").orEmpty().mapNotNull { it as? JsonObject }.forEach { bucket ->
                    val id = bucket.text("bucketId") ?: return@forEach
                    val fraction = bucket.number("remainingFraction")?.takeIf { it.isFinite() } ?: return@forEach
                    val reset = bucket.text("resetTime")?.let(::parseInstant)
                    val used = ((1 - fraction.coerceIn(0.0, 1.0)) * 100)
                    if (group.text("displayName") == ARGON) {
                        if (bucket.text("window") == "weekly" && argon == null && (bucket["disabled"] as? JsonPrimitive)?.booleanOrNull != true) argon = UsageWindow(used, ONE_WEEK, reset)
                    } else buckets.firstOrNull { it.first == id }?.let { (_, target) -> found.putIfAbsent(id, UsageWindow(used, target.second, reset)) }
                }
            }
        }
        if (found.isEmpty() && argon == null) throw SchemaException()
        val groupsOut = listOf(GEMINI, OTHER).mapNotNull { name ->
            val windows = buckets.filter { it.second.first == name }.mapNotNull { found[it.first] }.sortedBy { it.seconds }
            windows.takeIf { it.isNotEmpty() }?.let { QuotaGroup(name, it) }
        } + listOfNotNull(argon?.let { QuotaGroup("Gemini Argon", listOf(it)) })
        return UsageSnapshot(plan, groupsOut, fetchedAt)
    }
}

class GeminiRepository(private val http: HttpTransport) {
    private val backoff = RequestBackoff()
    private val hosts = listOf("https://cloudcode-pa.googleapis.com", "https://daily-cloudcode-pa.googleapis.com")
    private val metadata = """{"metadata":{"ideType":"ANTIGRAVITY","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}}"""
    private fun headers(token: String) = mapOf("Authorization" to "Bearer $token", "User-Agent" to "antigravity/hub/2.24.0 (aidev_client; os_type=linux; arch=arm64)")

    suspend fun fetch(credential: GeminiCredential): UsageSnapshot {
        backoff.check()
        if (credential.expiresAt?.let { it <= Instant.now().epochSecond + 30 } == true) throw HttpFailure(401)
        try {
            var load: String? = null
            for (host in hosts) {
                try { load = http.postJson("$host/v1internal:loadCodeAssist", metadata, headers(credential.access)); break }
                catch (e: HttpFailure) { if (e.status in listOf(401, 403)) throw e }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            val plan = load?.let(GeminiParser::plan)
            val body = GeminiParser.project(load.orEmpty())?.let { buildJsonObject { put("project", it) }.toString() } ?: "{}"
            val answers = mutableListOf<String>()
            var refused: HttpFailure? = null
            // The daily host has the live counters; production fills buckets the daily host left out.
            for (host in hosts.reversed()) {
                try {
                    val answer = http.postJson("$host/v1internal:retrieveUserQuotaSummary", body, headers(credential.access))
                    answers += answer
                    if (answers.size == 1 && GeminiParser.isComplete(answer)) break
                } catch (e: HttpFailure) { refused = refused ?: e }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            if (answers.isEmpty()) throw refused ?: HttpFailure(503)
            return GeminiParser.summary(answers, plan, Instant.now().epochSecond)
        } catch (e: HttpFailure) { if (e.status == 429) backoff.defer(e.retrySeconds ?: 60); throw e }
    }
}

/** A Gemini credential that is fresh enough to use, refreshed and stored when it expired. */
suspend fun SecureStore.freshGemini(http: HttpTransport, force: Boolean = false): GeminiCredential {
    val stored = readGemini() ?: throw IllegalStateException()
    if (!force && (stored.expiresAt ?: Long.MAX_VALUE) > Instant.now().epochSecond + 120) return stored
    return GeminiOAuth.refresh(http, stored).also(::writeGemini)
}
