package com.madebycli.aiusage.data

import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * What the public reset trackers say. None of it is personal account data, and none of it is combined into
 * one number: the sources use different methods and often disagree, so each one is shown on its own.
 */
enum class IntelSource(val label: String, val provider: String, val site: String, val minimumSeconds: Long, val method: String) {
    CODEX_RESET("codex-reset.com", "Codex", "https://codex-reset.com", 120, "Modell aus der Reset-Historie"),
    LUNARWERX("LunarWerx", "Codex", "https://codex.lunarwerx.com", 300, "Statistisches Modell mit Unsicherheitsbereich"),
    CODEX_TRACKER("Codex Reset Tracker", "Codex", "https://codexreset.vercel.app/en", 1800, "Spielerische Schätzung aus dem Rhythmus"),
    CLAUDE_TRACKER("Claude Reset Tracker", "Claude Code", "https://codexreset.vercel.app/en/claude", 1800, "Spielerische Schätzung aus dem Rhythmus"),
    CLAUDE_RESETS("claude-resets.net", "Claude Code", "https://claude-resets.net/", 1800, "Einschätzung der Community-Seite")
}

enum class ResetKind(val label: String) {
    GLOBAL("Globaler Reset"), PARTIAL("Teil-Reset"), BANKED("Gutschrift"), EVENT("Reset gemeldet")
}

/** [at] is the time the source gives; [timeKnown] is false when it only knows the day (00:00 UTC then). */
data class ResetEntry(
    val at: Long, val timeKnown: Boolean, val kind: ResetKind, val title: String, val detail: String?, val url: String?,
    val verified: Boolean, val source: IntelSource
)

data class Chance(val hours: Int, val percent: Double, val low: Double? = null, val high: Double? = null)
data class Rhythm(val medianDays: Double, val meanDays: Double, val longestDays: Double, val sample: Int)
/** Hours of the day in [zone], for example 3 to 6 UTC. */
data class DayWindow(val startHour: Int, val endHour: Int, val zone: String)

data class IntelReport(
    val source: IntelSource, val fetchedAt: Long, val publishedAt: Long? = null, val validUntil: Long? = null,
    val chances: List<Chance> = emptyList(), val lastResetAt: Long? = null, val expectedAt: Long? = null,
    val rhythm: Rhythm? = null, val window: DayWindow? = null, val headline: String? = null,
    val hints: List<String> = emptyList(), val entries: List<ResetEntry> = emptyList()
)

/** [partial] names a part that failed while the rest worked, for example the history of a source whose forecast loaded. */
data class IntelResult(val report: IntelReport? = null, val error: String? = null, val partial: String? = null)

object IntelParser {
    /** Cuts an answer text down to something readable: no entities, no short links, one line. */
    fun clean(raw: String?, max: Int = 240): String? {
        val text = Parser.unescapeEntities(raw.orEmpty(), false)
            .replace(Regex("https?://t\\.co/\\S+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        return if (text.length > max) text.take(max - 1).trimEnd() + "…" else text
    }

    private fun link(value: String?): String? = value?.takeIf {
        runCatching { val uri = java.net.URI(it); uri.scheme == "https" && uri.host != null && uri.userInfo == null }.getOrDefault(false)
    }

    private fun share(obj: JsonObject?, key: String): Double? = obj?.number(key)?.takeIf { it in 0.0..1.0 }?.times(100)

    // ---- codex-reset.com ------------------------------------------------------------------------------

    fun codexResetForecast(body: String, now: Long): IntelReport {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        val probabilities = root.obj("probabilities") ?: throw SchemaException()
        val chances = listOf(24, 48).map { hours ->
            val percent = probabilities.number("rounded_${hours}h")?.takeIf { it in 0.0..100.0 } ?: throw SchemaException()
            val range = probabilities.obj("range_${hours}h")
            Chance(hours, percent, share(range, "lower"), share(range, "upper"))
        }
        val wait = root.obj("wait_comparison")
        val rhythm = wait?.let {
            val median = it.number("median_days"); val mean = it.number("mean_days"); val longest = it.number("longest_days")
            val sample = it.integer("sample")?.toInt()
            if (median != null && mean != null && longest != null && sample != null && sample > 0) Rhythm(median, mean, longest, sample) else null
        }
        val window = root.obj("time_window")?.let {
            val start = it.integer("start_hour")?.toInt(); val end = it.integer("end_hour")?.toInt()
            if (start != null && end != null && start in 0..23 && end in 0..24) DayWindow(start, end, it.text("timezone") ?: "UTC") else null
        }
        // These fields are null on quiet days. When the site has something to say, it is shown as it is.
        val hints = listOfNotNull(root["official_signal"], root["teased_window"], root["latest_hint"], root["hint_copy"])
            .mapNotNull { element -> hintText(element) }.distinct()
        val context = root.obj("context_copy")?.text("en")?.let { clean(it, 280) }
        return IntelReport(IntelSource.CODEX_RESET, now, parseInstant(root.text("updated_at")), null, chances,
            parseInstant(root.text("last_reset_at")), null, rhythm, window, null, hints + listOfNotNull(context))
    }

    private fun hintText(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> clean(element.contentOrNull, 280)
        is JsonObject -> clean(element.text("text") ?: element.text("summary") ?: element.text("label") ?: element.text("copy"), 280)
        else -> null
    }

    fun codexResetTimeline(body: String): List<ResetEntry> {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        val rows = root.array("events") ?: throw SchemaException()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: throw SchemaException()
            val at = parseInstant(row.text("announced_at")) ?: return@mapNotNull null
            val group = row.text("group")
            val kindName = row.text("reset_kind")
            val announced = row.text("announcement_state") == "announced"
            val banked = kindName == "banked" || group == "credits" || row.text("banked_state") != null
            // "none" rows are teasers and side remarks. They are not resets.
            val kind = when {
                banked -> ResetKind.BANKED
                kindName == "hard" || announced -> ResetKind.GLOBAL
                else -> return@mapNotNull null
            }
            val state = row.text("banked_state")
            val title = clean(row.text("summary") ?: row.text("text")) ?: "Reset gemeldet"
            val detail = when (state) {
                "available" -> "Gutschrift verfügbar"; "arriving" -> "Gutschrift unterwegs"; "announced" -> "Gutschrift angekündigt"
                "unknown" -> "Gutschrift, Stand unklar"; else -> null
            }
            ResetEntry(at, true, kind, title, detail, link(row.text("url")),
                row.text("confidence") == "high" || row.text("observation_result") == "reset_observed", IntelSource.CODEX_RESET)
        }.distinctBy { it.url ?: "${it.at}:${it.title}" }.sortedByDescending { it.at }.take(120)
    }

    /** The latest teaser or remark the site did not count as a reset, if it is recent. */
    fun codexResetTeaser(body: String, now: Long): String? {
        val rows = (appJson.parseToJsonElement(body) as? JsonObject)?.array("events") ?: return null
        return rows.mapNotNull { it as? JsonObject }.filter { it.text("group") == "reset" && it.text("announcement_state") == "none" }
            .mapNotNull { row -> parseInstant(row.text("announced_at"))?.let { at -> at to clean(row.text("summary"), 200) } }
            .filter { (at, text) -> text != null && now - at in -300..36 * 3600 }.maxByOrNull { it.first }?.second
    }

    // ---- LunarWerx ------------------------------------------------------------------------------------

    fun lunarForecast(body: String, now: Long): IntelReport {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        fun chance(key: String, hours: Int): Chance {
            val p = root.obj(key) ?: throw SchemaException()
            val low = share(p, "low") ?: throw SchemaException()
            val high = share(p, "high") ?: throw SchemaException()
            val percent = share(p, "probability") ?: throw SchemaException()
            if (low > percent + 1e-9 || percent > high + 1e-9) throw SchemaException()
            return Chance(hours, percent, low, high)
        }
        val near = root.obj("nearTerm")?.number("hours")?.takeIf { it in 1.0..168.0 && it % 1 == 0.0 }?.toInt() ?: throw SchemaException()
        return IntelReport(IntelSource.LUNARWERX, now, parseInstant(root.text("generatedAt")), null,
            listOf(chance("nearTerm", near), chance("next24Hours", 24)), parseInstant(root.text("lastResetAt")), null, null, null,
            clean(root.text("headline"), 200))
    }

    fun lunarResets(body: String): List<ResetEntry> {
        val root = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
        val rows = root.array("resets") ?: throw SchemaException()
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: throw SchemaException()
            val at = parseInstant(row.text("at")) ?: return@mapNotNull null
            val announcement = row.obj("announcement")
            // The ledger lists every recorded reset, banked grants included, without classifying them.
            ResetEntry(at, true, ResetKind.EVENT, clean(announcement?.text("text")) ?: "Reset im Verlauf", null, link(announcement?.text("url")), true, IntelSource.LUNARWERX)
        }.distinctBy { it.at }.sortedByDescending { it.at }.take(120)
    }

    // ---- Trackers and community pages -----------------------------------------------------------------

    fun tracker(source: IntelSource, html: String, now: Long): IntelReport {
        val doc = Jsoup.parse(html, source.site)
        val name = if (source == IntelSource.CODEX_TRACKER) "Codex" else "Claude"
        if (doc.selectFirst("h1")?.text() != "Did $name Reset Today?") throw SchemaException()
        val panel = doc.selectFirst("section#prediction") ?: throw SchemaException()
        if (!panel.selectFirst("h2")?.text().orEmpty().contains("48 hours")) throw SchemaException()
        val percent = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*%$").matchEntire(panel.selectFirst(".prob-value")?.text().orEmpty().trim())
            ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: throw SchemaException()
        fun sync(label: String) = doc.select(".timeline-sync-bar .sync-item").firstOrNull { it.text().startsWith(label) }
            ?.selectFirst("time[datetime]")?.attr("datetime")?.let(::parseInstant)
        val history = doc.select("section").firstOrNull { it.attr("aria-label") == "Reset history" } ?: throw SchemaException()
        val entries = history.select("li").mapNotNull { row ->
            val at = row.selectFirst("time[datetime]")?.attr("datetime")?.let(::parseInstant) ?: return@mapNotNull null
            ResetEntry(at, true, ResetKind.EVENT, "Reset gemeldet", null, row.selectFirst("a[href]")?.absUrl("href")?.let(::link), true, source)
        }.distinctBy { it.at }.sortedByDescending { it.at }
        val expected = panel.selectFirst(".next-reset time[datetime]")?.attr("datetime")?.let(::parseInstant)
        return IntelReport(source, now, sync("Last synced"), sync("Next sync"), listOf(Chance(48, percent)), entries.firstOrNull()?.at, expected,
            null, null, null, emptyList(), entries)
    }

    private val monthDay = Regex("^[A-Z][a-z]{2} \\d{1,2}, \\d{4}$")

    fun claudeResets(html: String, now: Long): IntelReport {
        val doc = Jsoup.parse(html, IntelSource.CLAUDE_RESETS.site)
        val panel = doc.select("aside").firstOrNull { it.selectFirst("h2")?.text() == "Claude resets odds · next 7 days" } ?: throw SchemaException()
        val value = panel.selectFirst("svg[aria-label]")?.attr("aria-label")?.removePrefix("Reset odds ")?.trim() ?: throw SchemaException()
        val percent = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*%$").matchEntire(value)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: throw SchemaException()
        val history = doc.select("h2").firstOrNull { it.text() == "Claude resets history" }?.parent()?.parent() ?: throw SchemaException()
        val entries = history.select("ol > li").mapNotNull { row ->
            val at = row.selectFirst("time[datetime]")?.attr("datetime")?.let(::parseInstant) ?: return@mapNotNull null
            val paragraphs = row.select("p")
            val titleParagraph = paragraphs.firstOrNull() ?: return@mapNotNull null
            val unverified = titleParagraph.text().contains("unverified", true)
            val title = clean(titleParagraph.ownText().ifBlank { titleParagraph.text().replace("unverified", "", true) }) ?: return@mapNotNull null
            val facts = paragraphs.drop(1).joinToString(" · ") { it.text() }.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
            val scope = facts.firstOrNull()
            val plans = facts.getOrNull(1)
            val reason = facts.getOrNull(2)?.takeIf { it != "No reason given" }
            val lowered = title.lowercase()
            val kind = when {
                "saved reset" in scope.orEmpty().lowercase() || "card" in lowered -> ResetKind.BANKED
                "all users" in lowered -> ResetKind.GLOBAL
                else -> ResetKind.PARTIAL
            }
            val detail = listOfNotNull(scope?.let(::scopeText), plans?.let { if (it.equals("All plans", true)) "alle Tarife" else it }, reason).joinToString(" · ").ifEmpty { null }
            ResetEntry(at, false, kind, title, detail, row.selectFirst("a[href]")?.absUrl("href")?.let(::link), !unverified, IntelSource.CLAUDE_RESETS)
        }.distinctBy { "${it.at}:${it.title}" }.sortedByDescending { it.at }  // one article can cover several resets, so the link does not identify a row
        val last = panel.select("p").firstOrNull { it.text().startsWith("Last Claude reset:") }?.text()
        val lastAt = last?.let { Regex("Last Claude reset: ([A-Z][a-z]{2} \\d{1,2}, \\d{4})").find(it)?.groupValues?.get(1) }?.takeIf { monthDay.matches(it) }
            ?.let { text -> runCatching { java.time.LocalDate.parse(text, java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() }.getOrNull() }
        val label = panel.select("p").firstOrNull { it.className().contains("signal") }?.text()?.let { clean(it, 60) }
        return IntelReport(IntelSource.CLAUDE_RESETS, now, null, null, listOf(Chance(168, percent)), lastAt ?: entries.firstOrNull()?.at, null, null, null,
            label?.let { "Einschätzung der Seite: ${siteLabel(it)}" }, emptyList(), entries)
    }

    /** The few phrases the page uses for its own rating, in German; anything else stays as written. */
    private fun siteLabel(text: String) = when (text.trim().lowercase()) {
        "likely soon" -> "bald wahrscheinlich"; "overdue" -> "überfällig"; "unlikely" -> "unwahrscheinlich"
        "possible" -> "möglich"; "too early" -> "noch zu früh"; else -> text
    }

    private fun scopeText(scope: String): String = when (scope.lowercase()) {
        "5-hour + weekly" -> "5 Std. und Woche"; "weekly" -> "nur Woche"; "5-hour" -> "nur 5 Std."
        else -> scope.replace("5-hour", "5 Std.").replace("weekly", "Woche").replace("One saved reset", "Ein gespeicherter Reset")
    }
}

/** Merges the two answers of a source: the forecast is required, the history is a bonus. */
fun IntelReport.withHistory(entries: List<ResetEntry>, teaser: String? = null): IntelReport =
    copy(entries = entries, hints = hints + listOfNotNull(teaser?.let { "Zuletzt gemeldet: $it" }),
        lastResetAt = listOfNotNull(lastResetAt, entries.filter { it.kind != ResetKind.BANKED }.maxOfOrNull { it.at }).maxOrNull())
