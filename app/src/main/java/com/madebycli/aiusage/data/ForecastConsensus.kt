package com.madebycli.aiusage.data

/** A display consensus, not a calibrated prediction or independent-event probability. */
data class ConsensusChance(val hours: Int, val percent: Double, val sources: List<String>)

object ForecastConsensus {
    const val MAX_AGE_SECONDS = 7200L
    fun isFresh(fetchedAt: Long, publishedAt: Long?, now: Long): Boolean =
        now - fetchedAt in -300L..MAX_AGE_SECONDS &&
            (publishedAt == null || now - publishedAt in -300L..MAX_AGE_SECONDS)
    fun isCommunityFresh(report: CommunityReport, now: Long): Boolean {
        if (now - report.fetchedAt !in -300L..MAX_AGE_SECONDS) return false
        val published = report.publishedAt ?: return true
        val until = report.validUntil
        // The tracker publishes every six hours. Its declared next sync is not a reset deadline.
        return if (until != null && until - published in 1L..21600L)
            now - published in -300L..21600L && now <= until
        else now - published in -300L..MAX_AGE_SECONDS
    }

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
                isCommunityFresh(report, now)) samples += source.label to report.chance
        }
        // One vote per source and horizon. Conflicting duplicate estimates are unusable.
        val unique = samples.filter { (_, chance) -> chance.hours in 1..336 && chance.percent.isFinite() && chance.percent in 0.0..100.0 }
            .groupBy { (source, chance) -> source to chance.hours }
            .mapNotNull { (_, votes) -> votes.distinctBy { it.second.percent }.singleOrNull() }
        return unique.groupBy { it.second.hours }.toSortedMap().map { (hours, votes) ->
            ConsensusChance(hours, votes.map { it.second.percent }.average(), votes.map { it.first }.sorted())
        }
    }
}
