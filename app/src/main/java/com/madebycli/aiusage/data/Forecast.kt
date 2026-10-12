package com.madebycli.aiusage.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Instant

// Reset probability is community data, unrelated to a personal quota or Claude.
data class ForecastChance(val hours: Int, val percent: Double, val low: Double? = null, val high: Double? = null)
data class ResetForecast(val source: ResetSource, val chances: List<ForecastChance>, val publishedAt: Long?, val fetchedAt: Long,
    val lastReset: Long?, val confidence: String? = null, val note: String? = null, val accuracy: String? = null)
data class ForecastResult(val forecast: ResetForecast?, val error: String? = null)
object ForecastParser {
    fun parse(source: ResetSource, body: String, fetchedAt: Long): ResetForecast {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        fun number(obj: JsonObject, key: String, max: Double): Double = obj.number(key)?.takeIf { it in 0.0..max } ?: throw SchemaException()
        val chances = when (source) {
            ResetSource.CODEX_RESET -> {
                val p = root.obj("probabilities") ?: throw SchemaException()
                listOf(24, 48).map { hours -> ForecastChance(hours, number(p, "rounded_${hours}h", 100.0)) }
            }
            ResetSource.LUNARWERX -> {
                fun chance(key: String, hours: Int): ForecastChance {
                    val p = root.obj(key) ?: throw SchemaException()
                    val low = number(p, "low", 1.0) * 100
                    val high = number(p, "high", 1.0) * 100
                    val percent = number(p, "probability", 1.0) * 100
                    if (low > percent || percent > high) throw SchemaException()
                    return ForecastChance(hours, percent, low, high)
                }
                val hours = root.obj("nearTerm")?.number("hours")?.takeIf { it in 1.0..168.0 && it % 1 == 0.0 }?.toInt() ?: throw SchemaException()
                listOf(chance("nearTerm", hours), chance("next24Hours", 24))
            }
        }
        val accuracy = root.obj("liveAccuracy")?.obj("twentyFourHours")?.let { a ->
            val brier = a.number("brier")
            val windows = a.integer("windows")
            if (brier != null && brier in 0.0..1.0 && windows != null && windows >= 0) "24h Brier: %.3f · %d Messfenster".format(java.util.Locale.GERMAN, brier, windows) else null
        }
        return ResetForecast(source, chances, parseInstant(root.text(if (source == ResetSource.CODEX_RESET) "updated_at" else "generatedAt")),
            fetchedAt, parseInstant(root.text(if (source == ResetSource.CODEX_RESET) "last_reset_at" else "lastResetAt")),
            root.text("confidence"), root.text(if (source == ResetSource.CODEX_RESET) "confidence_note" else "caveat")?.take(500), accuracy)
    }
}
class ForecastRepository(private val http: HttpTransport) {
    private val cache = java.util.concurrent.ConcurrentHashMap<ResetSource, ForecastResult>()
    private val backoffs = java.util.concurrent.ConcurrentHashMap<ResetSource, RequestBackoff>()
    suspend fun fetch(source: ResetSource): ForecastResult {
        val backoff = backoffs.getOrPut(source) { RequestBackoff() }
        if (backoff.remainingSeconds() > 0) return cache[source] ?: ForecastResult(null)
        backoff.defer(source.minimumSeconds)
        val url = when (source) {
            ResetSource.CODEX_RESET -> "https://codex-reset.com/api/forecast"
            ResetSource.LUNARWERX -> "https://codex.lunarwerx.com/api/v1/forecast"
        }
        val result = try { ForecastResult(ForecastParser.parse(source, http.get(url), Instant.now().epochSecond)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (e is HttpFailure && e.retrySeconds != null) backoff.defer(e.retrySeconds); ForecastResult(cache[source]?.forecast, safeError(e)) }
        cache[source] = result
        return result
    }
}
