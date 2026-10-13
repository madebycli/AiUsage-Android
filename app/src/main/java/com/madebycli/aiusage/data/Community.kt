package com.madebycli.aiusage.data

import org.jsoup.Jsoup
import kotlinx.coroutines.CancellationException
import java.time.Instant

// Public pages, not an undocumented API. Missing/changed selectors fail closed.
enum class CommunitySource(val label: String, val provider: String, val site: String, val minimumSeconds: Long) {
    CLAUDE_RESETS("claude-resets.net", "Claude Code", "https://claude-resets.net/", 1800),
    VERCEL_CODEX("Codex Reset Tracker", "Codex", "https://codexreset.vercel.app/en", 1800),
    VERCEL_CLAUDE("Claude Reset Tracker", "Claude Code", "https://codexreset.vercel.app/en/claude", 1800)
}
data class CommunityReport(val source: CommunitySource, val chance: ForecastChance, val publishedAt: Long?, val fetchedAt: Long,
    val expectedAt: Long?, val events: List<ResetEvent>, val validUntil: Long? = null)
data class CommunityResult(val report: CommunityReport?, val error: String? = null)
object CommunityParser {
    fun parse(source: CommunitySource, html: String, fetchedAt: Long): CommunityReport {
        val doc = Jsoup.parse(html, source.site)
        fun percent(text: String): Double = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*%$").matchEntire(text.trim())
            ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: throw SchemaException()
        fun link(value: String): String? = runCatching {
            val uri = java.net.URI(value)
            value.takeIf { uri.scheme == "https" && uri.host != null && uri.userInfo == null }
        }.getOrNull()
        if (source == CommunitySource.CLAUDE_RESETS) {
            val panel = doc.select("aside").firstOrNull { it.selectFirst("h2")?.text() == "Claude resets odds · next 7 days" } ?: throw SchemaException()
            val value = panel.selectFirst("svg[aria-label]")?.attr("aria-label")?.removePrefix("Reset odds ") ?: throw SchemaException()
            val history = doc.select("h2").firstOrNull { it.text() == "Claude resets history" }?.parent()?.parent() ?: throw SchemaException()
            val events = history.select("ol > li").mapNotNull { row ->
                val at = parseInstant(row.selectFirst("time[datetime]")?.attr("datetime")) ?: return@mapNotNull null
                val summary = row.select("p").joinToString(" · ") { it.text() }.take(600)
                val unverified = row.text().contains("unverified", true)
                ResetEvent("claude-resets:$at", at, if (unverified) "Unbestätigte Meldung" else "Community-Meldung", summary,
                    row.selectFirst("a[href]")?.absUrl("href")?.let(::link))
            }
            return CommunityReport(source, ForecastChance(168, percent(value)), null, fetchedAt, null, events)
        }
        val expectedProvider = if (source == CommunitySource.VERCEL_CODEX) "Codex" else "Claude"
        if (doc.selectFirst("h1")?.text() != "Did $expectedProvider Reset Today?") throw SchemaException()
        val panel = doc.selectFirst("section#prediction") ?: throw SchemaException()
        if (!panel.selectFirst("h2")?.text().orEmpty().contains("48 hours")) throw SchemaException()
        val value = panel.selectFirst(".prob-value")?.text() ?: throw SchemaException()
        val published = doc.select(".timeline-sync-bar .sync-item").firstOrNull { it.text().startsWith("Last synced") }
            ?.selectFirst("time[datetime]")?.attr("datetime")?.let(::parseInstant)
        val nextSync = doc.select(".timeline-sync-bar .sync-item").firstOrNull { it.text().startsWith("Next sync") }
            ?.selectFirst("time[datetime]")?.attr("datetime")?.let(::parseInstant)
        val history = doc.selectFirst("section[aria-label=Reset history]") ?: throw SchemaException()
        val events = history.select("li").mapNotNull { row ->
            val time = row.selectFirst("time[datetime]") ?: return@mapNotNull null
            val at = parseInstant(time.attr("datetime")) ?: return@mapNotNull null
            ResetEvent("${source.name}:$at", at, "Community-Meldung", "Öffentlicher Reset-Verlauf · $expectedProvider",
                row.selectFirst("a[href]")?.absUrl("href")?.let(::link))
        }
        val expected = panel.selectFirst(".next-reset time[datetime]")?.attr("datetime")?.let(::parseInstant)
        return CommunityReport(source, ForecastChance(48, percent(value)), published, fetchedAt, expected, events, nextSync)
    }
}
class CommunityRepository(private val http: HttpTransport) {
    private val cache = java.util.concurrent.ConcurrentHashMap<CommunitySource, CommunityResult>()
    private val backoffs = java.util.concurrent.ConcurrentHashMap<CommunitySource, RequestBackoff>()
    suspend fun fetch(source: CommunitySource): CommunityResult {
        val backoff = backoffs.getOrPut(source) { RequestBackoff() }
        if (backoff.remainingSeconds() > 0) return cache[source] ?: CommunityResult(null)
        backoff.defer(source.minimumSeconds)
        val result = try { CommunityResult(CommunityParser.parse(source, http.get(source.site, mapOf("Accept" to "text/html")), Instant.now().epochSecond)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (e is HttpFailure && e.retrySeconds != null) backoff.defer(e.retrySeconds); CommunityResult(cache[source]?.report, safeError(e)) }
        cache[source] = result
        return result
    }
}
