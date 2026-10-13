package com.madebycli.aiusage.data

/** A display consensus, not a calibrated prediction or independent-event probability. */
data class ConsensusChance(val hours: Int, val percent: Double, val sources: List<String>)

object ForecastConsensus {
    const val MAX_AGE_SECONDS = 7200L
    fun isFresh(fetchedAt: Long, publishedAt: Long?, now: Long): Boolean =
        now - fetchedAt in -300L..MAX_AGE_SECONDS &&
            (publishedAt == null || now - publishedAt in -300L..MAX_AGE_SECONDS)

    fun combine(provider: String, forecasts: Map<ResetSource, ForecastResult>,
        community: Map<CommunitySource, CommunityResult>, now: Long): List<ConsensusChance> {
        val samples = mutableListOf<Pair<String, ForecastChance>>()
        if (provider == "Codex") forecasts.forEach { (source, result) ->
            val forecast = result.forecast
            if (result.error == null && forecast?.source == source && isFresh(forecast.fetchedAt, forecast.publishedAt, now)) {
                forecast.chances.forEach { samples += source.label to it }
            }
        }
        community.forEach { (source, result) ->
            val report = result.report
            if (source.provider == provider && result.error == null && report?.source == source &&
                isFresh(report.fetchedAt, report.publishedAt, now)) samples += source.label to report.chance
        }
        // One vote per source and horizon. Conflicting duplicate estimates are unusable.
        val unique = samples.filter { (_, chance) -> chance.hours in 1..168 && chance.percent.isFinite() && chance.percent in 0.0..100.0 }
            .groupBy { (source, chance) -> source to chance.hours }
            .mapNotNull { (_, votes) -> votes.distinctBy { it.second.percent }.singleOrNull() }
        return unique.groupBy { it.second.hours }.toSortedMap().map { (hours, votes) ->
            ConsensusChance(hours, votes.map { it.second.percent }.average(), votes.map { it.first }.sorted())
        }
    }
}
