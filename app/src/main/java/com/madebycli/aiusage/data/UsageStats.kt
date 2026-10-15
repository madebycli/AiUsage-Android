package com.madebycli.aiusage.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Serializable
data class StatsPoint(val day: String?, val label: String, val metric: String, val unit: String, val value: Double)
@Serializable
data class StatsReport(val id: String, val title: String, val scope: String, val fetchedAt: Long,
    val points: List<StatsPoint> = emptyList(), val publishedAt: Long? = null,
    val error: String? = null, val notice: String? = null)

@Serializable
data class StatsBundle(val reports: List<StatsReport> = emptyList()) {
    /** Replaces a report in place, so the report tabs keep their order while data loads. */
    fun merge(report: StatsReport): StatsBundle =
        if (reports.any { it.id == report.id }) copy(reports = reports.map { if (it.id == report.id) report else it })
        else copy(reports = reports + report)

    /** Report tabs always appear in the same order, whatever order the answers arrive in. */
    fun ordered(): List<StatsReport> = reports.sortedBy { report -> CodexReport.entries.indexOfFirst { it.id == report.id }.takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

/** The account reports the Codex client itself reads for a signed-in personal account. */
enum class CodexReport(val title: String, val route: String) {
    PROFILE("Profil", "profiles/me"),
    USAGE("Nutzung", "usage/daily-token-usage-breakdown"),
    CREDITS("Credit-Verlauf", "usage/credit-usage-events");
    val id get() = "codex.$name"
    fun url(now: Long): String {
        val end = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate()
        val query = when (this) {
            PROFILE, CREDITS -> ""
            USAGE -> "?start_date=${end.minusDays(29)}&end_date=$end&group_by=day"
        }
        return "https://chatgpt.com/backend-api/wham/$route$query"
    }
}

object StatsParser {
    private fun root(body: String) = appJson.parseToJsonElement(body) as? JsonObject ?: throw SchemaException()
    private fun day(raw: String?): String? = raw?.take(10)?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }
    private fun value(raw: JsonElement?): Double? = (raw as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 9_007_199_254_740_991.0 }
    private fun unit(key: String): String = when {
        key.contains("tokens", true) -> "Tokens"
        key.contains("credits", true) -> "Credits"
        key.endsWith("percentage") -> "%"
        key.endsWith("_sec") || key.contains("duration", true) -> "Sekunden"
        key.contains("streak") -> "Tage"
        else -> "Anzahl"
    }
    private fun numeric(obj: JsonObject, date: String?, label: String, prefix: String, out: MutableList<StatsPoint>) {
        obj.forEach { (key, raw) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (raw is JsonObject) numeric(raw, date, label, path, out)
            else value(raw)?.let { out += StatsPoint(date, label.take(160), path, unit(path), it) }
        }
    }

    fun codex(kind: CodexReport, body: String, at: Long): StatsReport {
        val root = root(body)
        val points = mutableListOf<StatsPoint>()
        val published = parseInstant(root.text("data_freshness_ts")) ?: parseInstant(root.obj("metadata")?.text("stats_as_of"))
        when (kind) {
            CodexReport.PROFILE -> profile(root, points)
            CodexReport.CREDITS -> rows(root).forEach { (date, row) ->
                val amount = row.number("credit_amount") ?: throw SchemaException()
                // Negative credit events are adjustments or refunds and keep their sign.
                points += StatsPoint(date, row.text("product_surface") ?: "Unbekannt", "Credits", "Credits", amount)
            }
            CodexReport.USAGE -> rows(root).forEach { (date, row) -> usageRow(root, date, row, points) }
        }
        return StatsReport(kind.id, kind.title, "Account · UTC", at, points.take(20000), published,
            notice = root.obj("metadata")?.text("stats_error")?.let { "Der Anbieter meldet unvollständige Profilwerte." })
    }

    private fun rows(root: JsonObject): List<Pair<String, JsonObject>> =
        (root.array("data") ?: throw SchemaException()).take(2000).map { raw ->
            val row = raw as? JsonObject ?: throw SchemaException()
            (day(row.text("date")) ?: throw SchemaException()) to row
        }

    private fun profile(root: JsonObject, points: MutableList<StatsPoint>) {
        val stats = root.obj("stats") ?: throw SchemaException()
        numeric(stats, null, "Gesamt", "", points)
        val seen = mutableSetOf<Pair<String, Double>>()
        stats.array("daily_usage_buckets")?.forEach { raw ->
            val row = raw as? JsonObject ?: throw SchemaException()
            val date = day(row.text("start_date")) ?: throw SchemaException()
            val tokens = row.number("tokens")?.takeIf { it >= 0 } ?: throw SchemaException()
            // A bucket repeated verbatim by the API must not be counted twice.
            if (seen.add(date to tokens)) points += StatsPoint(date, "Codex", "tokens", "Tokens", tokens)
        }
        stats.array("top_invocations")?.forEach { raw ->
            val row = raw as? JsonObject ?: return@forEach
            row.number("usage_count")?.takeIf { it >= 0 }?.let { points += StatsPoint(null,
                row.text("plugin_name") ?: row.text("skill_name") ?: "Unbekannt", row.text("type") ?: "Aufrufe", "Anzahl", it) }
        }
        stats.text("most_used_reasoning_effort")?.let { effort ->
            stats.number("most_used_reasoning_effort_percentage")?.let { points += StatsPoint(null, effort, "Reasoning", "%", it) }
        }
    }

    private fun usageRow(root: JsonObject, date: String, row: JsonObject, points: MutableList<StatsPoint>) {
        val reportedUnit = root.text("units") ?: root.text("unit") ?: "Einheiten"
        listOf("product_surface_usage_values", "values").forEach { key ->
            row.obj(key)?.forEach { (label, rawValue) -> value(rawValue)?.let { points += StatsPoint(date, label, "Verbrauch", reportedUnit, it) } }
        }
        row.obj("premium_usage_values")?.forEach { (metric, rawValues) ->
            (rawValues as? JsonObject)?.forEach { (label, rawValue) -> value(rawValue)?.let { points += StatsPoint(date, label, metric, unit(metric), it) } }
        }
        row.obj("totals")?.let { numeric(it, date, "Gesamt", "", points) }
        listOf("models", "clients", "groups", "attribution").forEach { key ->
            row.array(key)?.forEach { entry ->
                val item = entry as? JsonObject ?: return@forEach
                val dimensions = item.obj("dimensions")?.entries?.joinToString(" · ") { "${it.key}: ${(it.value as? JsonPrimitive)?.contentOrNull.orEmpty()}" }
                val label = item.text("display_name") ?: item.text("model") ?: item.text("client_id") ?: dimensions ?: item.text("surface") ?: key
                item.forEach { (metric, rawValue) ->
                    value(rawValue)?.let { points += StatsPoint(date, "$key · $label", metric, if (metric == "cost_usd") "USD" else if (metric == "value") reportedUnit else unit(metric), it) }
                }
            }
        }
    }
}

/** Daily token totals exactly as the profile report lists them. */
fun reportedActivity(stats: StatsBundle): List<UsageDay> {
    val report = stats.reports.firstOrNull { it.id == CodexReport.PROFILE.id } ?: return emptyList()
    return report.points.filter { it.day != null && it.metric == "tokens" && it.value >= 0 }
        .groupBy { it.day!! }
        .map { (date, points) -> UsageDay(date, null, 0, report.fetchedAt, points.sumOf { it.value }, "Tokens", report.scope) }
        .sortedByDescending { it.date }.take(366)
}

class CodexStatsRepository(private val auth: CodexAuth) {
    suspend fun fetch(kind: CodexReport, at: Long) = StatsParser.codex(kind, auth.authorizedGet(kind.url(at)), at)
}
