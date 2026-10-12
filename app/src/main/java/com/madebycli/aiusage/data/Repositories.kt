package com.madebycli.aiusage.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Instant

class UsageRepository(private val auth: CodexAuth) {
    private var blockedUntilNanos = 0L
    suspend fun fetch(): UsageSnapshot {
        checkBackoff()
        val usage = try {
            UsageParser.parse(auth.authorizedGet("https://chatgpt.com/backend-api/wham/usage"), Instant.now().epochSecond)
        } catch (e: HttpFailure) {
            if (e.status == 429) blockedUntilNanos = System.nanoTime() + (e.retrySeconds ?: 60) * 1_000_000_000
            throw e
        }
        if ((usage.availableResets ?: 0) == 0) return usage
        return try {
            usage.copy(resetCredits = UsageParser.credits(auth.authorizedGet("https://chatgpt.com/backend-api/wham/rate-limit-reset-credits")))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { usage.copy(creditsWarning = "Reset-Anzahl aktuell; Ablaufdetails konnten nicht geladen werden.") }
    }
    private fun checkBackoff() {
        val remaining = (blockedUntilNanos - System.nanoTime()) / 1_000_000_000
        if (remaining > 0) throw HttpFailure(429, remaining)
    }
}

data class FeedResult(val feed: ResetFeed?, val nextFetchSeconds: Long, val error: String? = null)

class ResetRepository(private val http: HttpTransport, private val clock: () -> Long = System::nanoTime) {
    private val feeds = mutableMapOf<ResetSource, ResetFeed>()
    private val nextAllowed = mutableMapOf<ResetSource, Long>()
    private val errors = mutableMapOf<ResetSource, String>()
    suspend fun fetch(source: ResetSource): FeedResult {
        val now = clock()
        val remaining = ((nextAllowed[source] ?: 0L) - now) / 1_000_000_000
        if (remaining > 0) return FeedResult(feeds[source], remaining, errors[source])
        nextAllowed[source] = now + source.minimumSeconds * 1_000_000_000
        try {
            val feed = ResetParser.parse(source, http.get(source.endpoint), Instant.now().epochSecond)
            feeds[source] = feed
            errors.remove(source)
            return FeedResult(feed, source.minimumSeconds)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is HttpFailure && e.retrySeconds != null)
                nextAllowed[source] = maxOf(nextAllowed[source] ?: 0L, now + e.retrySeconds * 1_000_000_000)
            errors[source] = safeError(e)
            return FeedResult(feeds[source], source.minimumSeconds, errors[source])
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
