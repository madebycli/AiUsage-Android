package com.madebycli.aiusage.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.time.Instant
import java.io.IOException
import java.util.Base64

data class DeviceCode(val id: String, val code: String, val interval: Long)

// Device-code and refresh protocol follows openai/codex, not a WebView/cookie scraper.
class CodexAuth(private val http: HttpTransport, private val store: TokenStore,
    private val issuer: String = "https://auth.openai.com") {
    private val mutex = Mutex()
    suspend fun begin(): DeviceCode {
        val result = appJson.parseToJsonElement(http.postJson("$issuer/api/accounts/deviceauth/usercode",
            buildJsonObject { put("client_id", CLIENT_ID) }.toString())).jsonObject
        return DeviceCode(result.text("device_auth_id")?.takeIf { it.isNotBlank() } ?: throw SchemaException(),
            (result.text("user_code") ?: result.text("usercode"))?.takeIf { it.isNotBlank() } ?: throw SchemaException(),
            result.integer("interval")?.coerceIn(1, 60) ?: 5)
    }
    suspend fun complete(device: DeviceCode, onRetry: (Exception) -> Unit = {}, onPhase: (LoginPhase) -> Unit = {}): Unit = withTimeout(15 * 60_000L) {
        while (true) {
            delay(device.interval * 1000)
            val response = try {
                http.postJson("$issuer/api/accounts/deviceauth/token", buildJsonObject {
                    put("device_auth_id", device.id); put("user_code", device.code)
                }.toString())
            } catch (e: HttpFailure) {
                if (e.status == 403 || e.status == 404) continue
                if (e.status == 429 || e.status in 500..599) {
                    onRetry(e)
                    delay((e.retrySeconds ?: device.interval).coerceIn(1, 900) * 1000)
                    continue
                }
                throw e
            } catch (e: IOException) {
                // Approval polling can safely retry after mobile network/background interruptions.
                // The one-time OAuth exchange below must never be replayed automatically.
                onRetry(e)
                continue
            }
            val code = appJson.parseToJsonElement(response).jsonObject
            onPhase(LoginPhase.EXCHANGING)
            val tokens = http.postForm("$issuer/oauth/token", mapOf(
                "grant_type" to "authorization_code", "client_id" to CLIENT_ID,
                "code" to (code.text("authorization_code") ?: throw SchemaException()),
                "redirect_uri" to "$issuer/deviceauth/callback",
                "code_verifier" to (code.text("code_verifier") ?: throw SchemaException())
            ))
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val parsed = parseTokens(tokens)
                onPhase(LoginPhase.SAVING)
                store.writeTokens(parsed)
                try { currentCoroutineContext().ensureActive() }
                catch (e: CancellationException) { store.clear(); throw e }
            }
            break
        }
    }
    suspend fun authorizedGet(url: String): String {
        // Only token access/rotation is serial. Slow GETs must not block another quota read.
        val tokens = mutex.withLock {
            val saved = store.readTokens() ?: throw IllegalStateException("Bitte zuerst deinen Codex-Account verbinden.")
            if (saved.expiresAt != null && saved.expiresAt < Instant.now().epochSecond + 60 && saved.refresh.isNotBlank())
                refresh(saved) else saved
        }
        return try { http.get(url, headers(tokens)) }
        catch (e: HttpFailure) {
            if (e.status != 401 || tokens.refresh.isBlank()) throw e
            val renewed = mutex.withLock {
                val latest = store.readTokens() ?: throw HttpFailure(401)
                // A parallel request may already have rotated this credential.
                if (latest.access != tokens.access) latest else refresh(latest)
            }
            http.get(url, headers(renewed))
        }
    }
    private suspend fun refresh(old: Tokens): Tokens {
        val body = try { http.postJson("$issuer/oauth/token", buildJsonObject {
            put("client_id", CLIENT_ID); put("grant_type", "refresh_token"); put("refresh_token", old.refresh)
        }.toString()) } catch (error: HttpFailure) {
            if (error.status == 400 || error.status == 401) throw HttpFailure(401)
            throw error
        }
        val tokens = parseTokens(body, old)
        // Persist a rotated refresh token before any further request.
        store.writeTokens(tokens)
        return tokens
    }
    private fun headers(tokens: Tokens): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${tokens.access}")
        tokens.accountId?.let { put("ChatGPT-Account-Id", it) }
    }
    internal fun parseTokens(body: String, old: Tokens? = null): Tokens {
        val obj = appJson.parseToJsonElement(body).jsonObject
        val access = obj.text("access_token")?.takeIf { it.isNotBlank() } ?: throw SchemaException()
        val claims = jwt(obj.text("id_token")) ?: jwt(access)
        val account = claims?.obj("https://api.openai.com/auth")?.text("chatgpt_account_id")
            ?: jwt(access)?.obj("https://api.openai.com/auth")?.text("chatgpt_account_id") ?: old?.accountId
        val expiry = obj.integer("expires_in")?.takeIf { it > 0 }?.let { Instant.now().epochSecond + it }
            ?: jwt(access)?.integer("exp") ?: claims?.integer("exp")
        return Tokens(access, obj.text("refresh_token")?.takeIf { it.isNotBlank() } ?: old?.refresh.orEmpty(), account, expiry)
    }
    private fun jwt(token: String?): JsonObject? = runCatching {
        val part = token?.split('.')?.getOrNull(1) ?: return null
        appJson.parseToJsonElement(Base64.getUrlDecoder().decode(part).toString(Charsets.UTF_8)).jsonObject
    }.getOrNull()
    companion object { const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann" }
}
