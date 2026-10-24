package com.madebycli.aiusage.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Reads the public sources. Each one has its own pause between reads and keeps its last good answer when a read fails. */
class IntelRepository(private val http: HttpTransport, private val clock: () -> Long = System::nanoTime) {
    private val cache = ConcurrentHashMap<IntelSource, IntelResult>()
    private val backoffs = ConcurrentHashMap<IntelSource, RequestBackoff>()

    suspend fun fetch(source: IntelSource, force: Boolean = false): IntelResult {
        val backoff = backoffs.getOrPut(source) { RequestBackoff(clock) }
        if (!force && backoff.remainingSeconds() > 0) return cache[source] ?: IntelResult()
        backoff.defer(source.minimumSeconds)
        val now = Instant.now().epochSecond
        val result = try { read(source, now) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is HttpFailure && e.retrySeconds != null) backoff.defer(e.retrySeconds)
            // The last good report stays visible next to the error, with its original fetch time.
            IntelResult(cache[source]?.report, safeError(e))
        }
        cache[source] = result
        return result
    }

    private suspend fun read(source: IntelSource, now: Long): IntelResult = when (source) {
        IntelSource.CODEX_RESET -> coroutineScope {
            val forecast = async { http.get("https://codex-reset.com/api/forecast") }
            val timeline = async { runCatching { http.get("https://codex-reset.com/api/timeline") } }
            val report = IntelParser.codexResetForecast(forecast.await(), now)
            val body = timeline.await()
            body.getOrNull()?.let { text ->
                try { IntelResult(report.withHistory(IntelParser.codexResetTimeline(text), IntelParser.codexResetTeaser(text, now))) }
                catch (e: SchemaException) { IntelResult(report, partial = "Der Verlauf von ${source.label} hat ein neues Format.") }
            } ?: run {
                val failure = body.exceptionOrNull()
                if (failure is CancellationException) throw failure
                IntelResult(report, partial = "Der Verlauf von ${source.label} konnte nicht geladen werden.")
            }
        }
        IntelSource.LUNARWERX -> coroutineScope {
            val forecast = async { http.get("https://codex.lunarwerx.com/api/v1/forecast") }
            val resets = async { runCatching { http.get("https://codex.lunarwerx.com/api/v1/resets?limit=100") } }
            val report = IntelParser.lunarForecast(forecast.await(), now)
            val body = resets.await()
            body.getOrNull()?.let { text ->
                try { IntelResult(report.withHistory(IntelParser.lunarResets(text))) }
                catch (e: SchemaException) { IntelResult(report, partial = "Der Verlauf von ${source.label} hat ein neues Format.") }
            } ?: run {
                val failure = body.exceptionOrNull()
                if (failure is CancellationException) throw failure
                IntelResult(report, partial = "Der Verlauf von ${source.label} konnte nicht geladen werden.")
            }
        }
        IntelSource.CODEX_TRACKER, IntelSource.CLAUDE_TRACKER -> IntelResult(IntelParser.tracker(source, http.get(source.site, mapOf("Accept" to "text/html")), now))
        IntelSource.CLAUDE_RESETS -> IntelResult(IntelParser.claudeResets(http.get(source.site, mapOf("Accept" to "text/html")), now))
    }
}
