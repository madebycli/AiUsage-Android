package com.madebycli.aiusage.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

class UsageRepository(private val auth: CodexAuth) {
    private val usageBackoff = RequestBackoff()
    private val creditsBackoff = RequestBackoff()
    suspend fun fetch(): UsageSnapshot {
        usageBackoff.check()
        return try {
            UsageParser.parse(auth.authorizedGet("https://chatgpt.com/backend-api/wham/usage"), Instant.now().epochSecond)
        } catch (e: HttpFailure) {
            if (e.status == 429) usageBackoff.defer(e.retrySeconds ?: 60)
            throw e
        }
    }
    suspend fun fetchCredits(usage: UsageSnapshot): UsageSnapshot {
        if ((usage.availableResets ?: 0) == 0) return usage
        return try {
            creditsBackoff.check()
            val details = withTimeoutOrNull(5_000) {
                UsageParser.credits(auth.authorizedGet("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"))
            }
            if (details == null) usage.copy(creditsWarning = "Reset-Anzahl aktuell; Ablaufdetails konnten nicht geladen werden.")
            else usage.copy(resetCredits = details, creditsFetchedAt = Instant.now().epochSecond, creditsWarning = null)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is HttpFailure && e.status == 429) creditsBackoff.defer(e.retrySeconds ?: 60)
            usage.copy(creditsWarning = "Reset-Anzahl aktuell; Ablaufdetails konnten nicht geladen werden.")
        }
    }
}

class RequestBackoff(private val clock: () -> Long = System::nanoTime) {
    private var until: Long? = null
    fun remainingSeconds(): Long {
        val remaining = until?.minus(clock())?.coerceAtLeast(0) ?: return 0
        return remaining / 1_000_000_000 + if (remaining % 1_000_000_000 > 0) 1 else 0
    }
    fun defer(seconds: Long) {
        // Cap arithmetic to a safe monotonic interval, even for malformed Retry-After values.
        val safeSeconds = seconds.coerceIn(1, Long.MAX_VALUE / 4 / 1_000_000_000)
        if (safeSeconds >= remainingSeconds()) until = clock() + safeSeconds * 1_000_000_000
    }
    fun check() { remainingSeconds().takeIf { it > 0 }?.let { throw HttpFailure(429, it) } }
}

data class FeedResult(val feed: ResetFeed?, val nextFetchSeconds: Long, val error: String? = null)

class ResetRepository(private val http: HttpTransport, private val clock: () -> Long = System::nanoTime) {
    private val feeds = mutableMapOf<ResetSource, ResetFeed>()
    private val backoffs = mutableMapOf<ResetSource, RequestBackoff>()
    private val errors = mutableMapOf<ResetSource, String>()
    suspend fun fetch(source: ResetSource): FeedResult {
        val backoff = backoffs.getOrPut(source) { RequestBackoff(clock) }
        val remaining = backoff.remainingSeconds()
        if (remaining > 0) return FeedResult(feeds[source], remaining, errors[source])
        backoff.defer(source.minimumSeconds)
        try {
            val feed = ResetParser.parse(source, http.get(source.endpoint), Instant.now().epochSecond)
            feeds[source] = feed
            errors.remove(source)
            return FeedResult(feed, source.minimumSeconds)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is HttpFailure && e.retrySeconds != null)
                backoff.defer(e.retrySeconds)
            errors[source] = safeError(e)
            return FeedResult(feeds[source], backoff.remainingSeconds(), errors[source])
        }
    }
}

// An active request is never cancelled by tapping again. One fresh read is queued.
class RefreshQueue(scope: CoroutineScope, private val refresh: suspend () -> Unit) {
    private val channel = Channel<Unit>(Channel.CONFLATED)
    private val job = scope.launch { for (request in channel) refresh() }
    fun request() { channel.trySend(Unit) }
    fun cancel() { channel.close(); job.cancel() }
}

fun safeError(error: Exception): String = when (error) {
    is HttpFailure -> error.message.orEmpty()
    is SchemaException, is kotlinx.serialization.SerializationException, is IllegalArgumentException -> "Das Datenformat des Anbieters hat sich geändert."
    is IllegalStateException -> "Bitte zuerst deinen Codex-Account verbinden."
    else -> "Abruf fehlgeschlagen. Verbindung prüfen und erneut aktualisieren."
}
