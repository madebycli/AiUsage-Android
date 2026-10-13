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
    fun merge(report: StatsReport) = copy(reports = reports.filter { it.id != report.id } + report)
}

// Contracts read from openai/codex backend-client analytics.rs/profile.rs (2026-10-13).
// Separate reports are never added together: totals, attribution and billing overlap.
enum class CodexReport(val title: String, val route: String, val extra: String = "") {
    PROFILE("Profil", "profiles/me"),
    USAGE("Nutzung", "usage/daily-token-usage-breakdown"),
    CREDITS("Credit-Verlauf", "usage/credit-usage-events"),
    WORKSPACE("Work & Codex", "usage/daily-workspace-user-token-usage-breakdown"),
    MODELS("Modell-Tokens", "usage/daily-workspace-user-token-usage-breakdown", "&breakdown_by=model&modes=codex&modes=work"),
    MESSAGES("Aktivitäten", "analytics/daily-workspace-usage-counts", "&workspace_user=true"),
    PLUGINS("Plugins", "analytics/daily-plugin-usage-metrics", "&workspace_user=true&top_plugin_limit=10"),
    SKILLS("Skills", "analytics/daily-skill-usage-metrics", "&workspace_user=true&top_skill_limit=10"),
    SURFACE("Credits · Produkt", "usage/daily-workspace-user-credit-usage", "surface"),
    MODEL_CREDITS("Credits · Modell", "usage/daily-workspace-user-credit-usage", "model"),
    REASONING("Credits · Reasoning", "usage/daily-workspace-user-credit-usage", "reasoning_effort"),
    SPEED("Credits · Tempo", "usage/daily-workspace-user-credit-usage", "speed");
    val id get() = "codex.${name}"
    fun url(now: Long): String {
        val end = Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC).toLocalDate()
        val query = when {
            this == PROFILE || this == CREDITS -> ""
            this in listOf(SURFACE, MODEL_CREDITS, REASONING, SPEED) -> "?start_date=${end.minusDays(29)}&end_date=$end&breakdown=$extra"
            else -> "?start_date=${end.minusDays(29)}&end_date=$end&group_by=day$extra"
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
        if (kind == CodexReport.PROFILE) {
            val stats = root.obj("stats") ?: throw SchemaException()
            numeric(stats, null, "Gesamt", "", points)
            stats.array("daily_usage_buckets")?.forEach { raw ->
                val row = raw as? JsonObject ?: throw SchemaException()
                val date = day(row.text("start_date")) ?: throw SchemaException()
                val tokens = row.number("tokens")?.takeIf { it >= 0 } ?: throw SchemaException()
                points += StatsPoint(date, "Codex", "tokens", "Tokens", tokens)
            }
            stats.array("top_invocations")?.forEach { raw ->
                val row = raw as? JsonObject ?: return@forEach
                row.number("usage_count")?.takeIf { it >= 0 }?.let { points += StatsPoint(null,
                    row.text("plugin_name") ?: row.text("skill_name") ?: "Unbekannt", row.text("type") ?: "Aufrufe", "Anzahl", it) }
            }
            stats.text("most_used_reasoning_effort")?.let { effort ->
                stats.number("most_used_reasoning_effort_percentage")?.let { points += StatsPoint(null, effort, "Reasoning", "%", it) }
            }
        } else {
            val rows = root.array("data") ?: throw SchemaException()
            rows.take(2000).forEach { raw ->
                val row = raw as? JsonObject ?: throw SchemaException()
                val date = day(row.text("date")) ?: throw SchemaException()
                val reportedUnit = root.text("units") ?: root.text("unit") ?: "Einheiten"
                if (kind == CodexReport.CREDITS) {
                    val amount = row.number("credit_amount") ?: throw SchemaException()
                    // Negative credit events retain their sign (adjustment/refund); never abs().
                    points += StatsPoint(date, row.text("product_surface") ?: "Unbekannt", "Credits", "Credits", amount)
                }
                listOf("product_surface_usage_values", "values").forEach { key ->
                    row.obj(key)?.forEach { (label, rawValue) -> value(rawValue)?.let { points += StatsPoint(date, label, "Verbrauch", reportedUnit, it) } }
                }
                row.obj("premium_usage_values")?.forEach { (metric, rawValues) ->
                    (rawValues as? JsonObject)?.forEach { (label, rawValue) -> value(rawValue)?.let { points += StatsPoint(date, label, metric, unit(metric), it) } }
                }
                row.obj("totals")?.let { numeric(it, date, "Gesamt", "", points) }
                listOf("models", "clients", "groups", "attribution", "plugin_usage_overviews", "skill_usage_overviews").forEach { key ->
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
        return StatsReport(kind.id, kind.title, "Account · UTC", at, points.take(20000), published,
            notice = root.obj("metadata")?.text("stats_error")?.let { "Der Anbieter meldet unvollständige Profilwerte." })
    }

    fun claudeFile(body: String, at: Long): StatsReport {
        val root = root(body)
        if (root.array("dailyActivity") == null && root.array("dailyModelTokens") == null) throw SchemaException()
        val points = mutableListOf<StatsPoint>()
        listOf("totalSessions", "totalMessages").forEach { key -> root.number(key)?.takeIf { it >= 0 }?.let { points += StatsPoint(null, "Gesamt", key, "Anzahl", it) } }
        root.array("dailyActivity")?.forEach { raw ->
            val row = raw as? JsonObject ?: throw SchemaException()
            val date = day(row.text("date")) ?: throw SchemaException()
            listOf("messageCount" to "Nachrichten", "sessionCount" to "Sitzungen", "toolCallCount" to "Tool-Aufrufe").forEach { (key, metric) ->
                row.number(key)?.takeIf { it >= 0 }?.let { points += StatsPoint(date, "Claude Code", metric, "Anzahl", it) }
            }
        }
        root.array("dailyModelTokens")?.forEach { raw ->
            val row = raw as? JsonObject ?: throw SchemaException()
            val date = day(row.text("date")) ?: throw SchemaException()
            row.obj("tokensByModel")?.forEach { (model, rawValue) -> value(rawValue)?.takeIf { it >= 0 }?.let { points += StatsPoint(date, model, "Tokens", "Tokens", it) } }
        }
        root.obj("modelUsage")?.forEach { (model, raw) ->
            (raw as? JsonObject)?.forEach { (metric, rawValue) -> value(rawValue)?.takeIf { it >= 0 }?.let {
                // totalCostUSD is a CLI estimate, not an API invoice or subscription charge.
                points += StatsPoint(null, model, metric, if (metric == "costUSD" || metric == "totalCostUSD") "USD (CLI-Schätzung)" else unit(metric), it)
            } }
        }
        root.obj("hourCounts")?.forEach { (hour, raw) -> value(raw)?.takeIf { it >= 0 }?.let { points += StatsPoint(null, "$hour Uhr", "Aktivitäten · alle Zeiten", "Anzahl", it) } }
        return StatsReport("claude.file", "Claude-Code-Datei", "Import · dieses Gerät/Export", at, points.take(20000),
            root.text("lastComputedDate")?.let { day(it)?.let { date -> LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toEpochSecond() } },
            notice = "Lokaler Claude-Code-Bericht. Enthält nur die im Export erfasste Nutzung; kein vollständiger Claude-/Cowork-Verlauf.")
    }

    fun api(provider: String, id: String, title: String, body: String, at: Long): StatsReport {
        val root = root(body)
        val points = mutableListOf<StatsPoint>()
        (root.array("data") ?: throw SchemaException()).forEach { raw ->
            val bucket = raw as? JsonObject ?: throw SchemaException()
            val date = if (provider == "Codex") bucket.integer("start_time")?.let { Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                else day(bucket.text("starting_at"))
            if (date == null) throw SchemaException()
            (bucket.array("results") ?: throw SchemaException()).forEach { rawResult ->
                val result = rawResult as? JsonObject ?: throw SchemaException()
                val label = result.text("model") ?: result.text("line_item") ?: result.text("description") ?: "Gesamt"
                if (id.endsWith("cost")) {
                    if (provider == "Codex") {
                        val amount = result.obj("amount")
                        val currency = amount?.text("currency")?.uppercase()
                        amount?.number("value")?.let { points += StatsPoint(date, label, "Kosten", currency ?: "Währung unbekannt", it) }
                    } else {
                        val currency = result.text("currency")?.uppercase()
                        result.number("amount")?.let { points += StatsPoint(date, label, "Kosten", if (currency == "USD") "USD" else "${currency ?: "Unbekannt"} Untereinheiten", if (currency == "USD") it / 100 else it) }
                    }
                } else numeric(result, date, label, "", points)
            }
        }
        return StatsReport(id, title, "API · gesamte Organisation · UTC", at, points.take(20000),
            notice = if ((root["has_more"] as? JsonPrimitive)?.booleanOrNull == true) "Weitere Seiten vorhanden. Diese Anzeige ist ein Teilbericht." else null)
    }
    fun claudeCodeApi(body: String, at: Long): StatsReport {
        val root = root(body)
        val points = mutableListOf<StatsPoint>()
        (root.array("data") ?: throw SchemaException()).forEach { raw ->
            val row = raw as? JsonObject ?: throw SchemaException()
            val date = day(row.text("date")) ?: throw SchemaException()
            val actor = row.obj("actor")
            val label = "${actor?.text("email_address") ?: actor?.text("api_key_name") ?: "Organisation"} · ${row.text("terminal_type") ?: "Claude Code"}"
            row.obj("core_metrics")?.let { numeric(it, date, label, "", points) }
            row.obj("tool_actions")?.let { numeric(it, date, label, "", points) }
            row.array("model_breakdown")?.forEach { rawModel ->
                val model = rawModel as? JsonObject ?: return@forEach
                val name = "$label · ${model.text("model") ?: "Modell unbekannt"}"
                model.obj("tokens")?.forEach { (metric, rawValue) -> value(rawValue)?.let { points += StatsPoint(date, name, metric, "Tokens", it) } }
                val cost = model.obj("estimated_cost")
                val currency = cost?.text("currency")
                cost?.number("amount")?.let { points += StatsPoint(date, name, "Geschätzte Kosten", if (currency == "USD") "USD (Anbieterschätzung)" else "${currency ?: "Unbekannt"} Untereinheiten", if (currency == "USD") it / 100 else it) }
            }
        }
        return StatsReport("api.code", "Claude Code · heute", "Organisation · UTC · Tagesbericht", at, points.take(20000),
            notice = if ((root["has_more"] as? JsonPrimitive)?.booleanOrNull == true) "Weitere Benutzerseiten vorhanden. Dieser Tagesbericht ist unvollständig." else "Tagesanalyse der Organisation; bis zu einer Stunde Meldeverzug.")
    }
}

fun reportedActivity(stats: StatsBundle, provider: String): List<UsageDay> {
    val report = stats.reports.firstOrNull { it.id == if (provider == "Codex") CodexReport.PROFILE.id else "claude.file" } ?: return emptyList()
    val metric = if (provider == "Codex") "tokens" else "Nachrichten"
    return report.points.filter { it.day != null && it.metric == metric && it.value >= 0 }
        .groupBy { it.day!! }.map { (date, points) -> UsageDay(date, null, 0, report.fetchedAt, points.sumOf { it.value }, if (provider == "Codex") "Tokens" else "Nachrichten", report.scope) }
        .sortedByDescending { it.date }.take(366)
}

class CodexStatsRepository(private val auth: CodexAuth) {
    suspend fun fetch(kind: CodexReport, at: Long) = StatsParser.codex(kind, auth.authorizedGet(kind.url(at)), at)
}

class ApiStatsRepository(private val http: HttpTransport) {
    suspend fun claudeCode(key: String, at: Long): StatsReport {
        val day = Instant.ofEpochSecond(at).atZone(ZoneOffset.UTC).toLocalDate()
        return StatsParser.claudeCodeApi(http.get("https://api.anthropic.com/v1/organizations/usage_report/claude_code?starting_at=$day&limit=1000",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")), at)
    }
    suspend fun fetch(provider: String, key: String, costs: Boolean, at: Long): StatsReport {
        val end = Instant.ofEpochSecond(at).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
        val start = end.minusDays(30)
        val url = if (provider == "Codex") {
            val route = if (costs) "costs" else "usage/completions"
            "https://api.openai.com/v1/organization/$route?start_time=${start.atStartOfDay(ZoneOffset.UTC).toEpochSecond()}&end_time=${end.atStartOfDay(ZoneOffset.UTC).toEpochSecond()}&bucket_width=1d&limit=30&group_by=${if (costs) "line_item" else "model"}"
        } else {
            val route = if (costs) "cost_report" else "usage_report/messages"
            "https://api.anthropic.com/v1/organizations/$route?starting_at=${start}T00%3A00%3A00Z&ending_at=${end}T00%3A00%3A00Z&bucket_width=1d&limit=31&group_by[]=${if (costs) "description" else "model"}"
        }
        val headers = if (provider == "Codex") mapOf("Authorization" to "Bearer $key") else mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        return StatsParser.api(provider, "api.${if (costs) "cost" else "usage"}", if (costs) "API-Kosten" else "API-Tokens", http.get(url, headers), at)
    }
}
