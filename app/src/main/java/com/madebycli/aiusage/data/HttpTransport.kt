package com.madebycli.aiusage.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpFailure(val status: Int, val retrySeconds: Long? = null) : Exception(
    when (status) {
        401 -> "Anmeldung abgelaufen. Bitte erneut verbinden."
        403 -> "Der Anbieter verweigert den Zugriff."
        429 -> "Anbieter-Limit erreicht. Erneut versuchen in ${retrySeconds ?: 60} Sekunden."
        else -> "Anbieter nicht erreichbar (HTTP $status)."
    }
)

class HttpTransport(val client: OkHttpClient = OkHttpClient.Builder()
    .cache(null).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).build()) {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = execute(
        request(url, headers).get().build()
    )
    suspend fun postJson(url: String, json: String): String = execute(
        request(url).post(json.toRequestBody("application/json".toMediaType())).build()
    )
    suspend fun postForm(url: String, fields: Map<String, String>): String {
        val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        return execute(request(url).post(form).build())
    }
    private fun request(url: String, headers: Map<String, String> = emptyMap()): Request.Builder = Request.Builder()
        .url(url).header("User-Agent", "AiUsage-Android/0.4 (+https://github.com/madebycli/AiUsage-Android)")
        .header("Accept", "application/json").header("Cache-Control", "no-cache, no-store")
        .header("Pragma", "no-cache").apply { headers.forEach { (name, value) -> header(name, value) } }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(IOException("Netzwerkfehler. Verbindung prüfen."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) throw HttpFailure(response.code, retryAfter(response.header("Retry-After")))
                        val source = response.body?.source() ?: throw SchemaException()
                        // Never log bodies: both success and error responses can contain secrets.
                        source.request(MAX_BODY_BYTES + 1)
                        if (source.buffer.size > MAX_BODY_BYTES) throw SchemaException()
                        val bytes = source.readByteArray()
                        if (!continuation.isCancelled) continuation.resume(bytes.toString(Charsets.UTF_8))
                    } catch (e: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
    private fun retryAfter(value: String?): Long? {
        val seconds = value?.toLongOrNull() ?: value?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() - Instant.now().epochSecond }.getOrNull()
        }
        return seconds?.coerceAtLeast(1)
    }
    companion object { private const val MAX_BODY_BYTES = 1_048_576L }
}
