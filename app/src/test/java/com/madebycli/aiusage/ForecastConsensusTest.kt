package com.madebycli.aiusage

import com.madebycli.aiusage.data.*
import org.junit.Assert.*
import org.junit.Test

class ForecastConsensusTest {
    private val now = 100_000L
    private fun api(source: ResetSource, chances: List<ForecastChance>, published: Long? = now, fetched: Long = now, error: String? = null) =
        ForecastResult(ResetForecast(source, chances, published, fetched, null), error)
    private fun page(source: CommunitySource, hours: Int, percent: Double) =
        CommunityResult(CommunityReport(source, ForecastChance(hours, percent), null, now, null, emptyList()))
    @Test fun `same horizon is averaged while providers and horizons remain separate`() {
        val forecasts = mapOf(ResetSource.CODEX_RESET to api(ResetSource.CODEX_RESET, listOf(ForecastChance(24, 19.0), ForecastChance(48, 34.0))),
            ResetSource.LUNARWERX to api(ResetSource.LUNARWERX, listOf(ForecastChance(6, 1.1), ForecastChance(24, 23.4))))
        val community = mapOf(CommunitySource.VERCEL_CODEX to page(CommunitySource.VERCEL_CODEX, 48, 46.0),
            CommunitySource.VERCEL_CLAUDE to page(CommunitySource.VERCEL_CLAUDE, 48, 46.0),
            CommunitySource.CLAUDE_RESETS to page(CommunitySource.CLAUDE_RESETS, 168, 72.0))
        val codex = ForecastConsensus.combine("Codex", forecasts, community, now)
        assertEquals(listOf(6, 24, 48), codex.map { it.hours })
        assertEquals(21.2, codex[1].percent, .00001)
        assertEquals(40.0, codex[2].percent, .00001)
        assertEquals(2, codex[1].sources.size)
        val claude = ForecastConsensus.combine("Claude Code", forecasts, community, now)
        assertEquals(listOf(48, 168), claude.map { it.hours })
        assertEquals(72.0, claude.last().percent, .00001)
        assertEquals(1, claude.first().sources.size)
    }
    @Test fun `stale errored or future data do not vote and absent data is not zero`() {
        listOf(api(ResetSource.CODEX_RESET, listOf(ForecastChance(24, 0.0)), published = now - 7201),
            api(ResetSource.CODEX_RESET, listOf(ForecastChance(24, 0.0)), fetched = now - 7201),
            api(ResetSource.CODEX_RESET, listOf(ForecastChance(24, 0.0)), error = "Offline"),
            api(ResetSource.CODEX_RESET, listOf(ForecastChance(24, 0.0)), published = now + 301)).forEach {
            assertTrue(ForecastConsensus.combine("Codex", mapOf(ResetSource.CODEX_RESET to it), emptyMap(), now).isEmpty())
        }
        val zero = api(ResetSource.CODEX_RESET, listOf(ForecastChance(24, 0.0)), published = null)
        assertEquals(0.0, ForecastConsensus.combine("Codex", mapOf(ResetSource.CODEX_RESET to zero), emptyMap(), now).single().percent, 0.0)
    }
    @Test fun `a source cannot gain weight through duplicate horizons`() {
        val duplicated = api(ResetSource.CODEX_RESET, listOf(ForecastChance(24, 20.0), ForecastChance(24, 20.0)))
        val other = api(ResetSource.LUNARWERX, listOf(ForecastChance(24, 40.0)))
        val combined = ForecastConsensus.combine("Codex", mapOf(ResetSource.CODEX_RESET to duplicated, ResetSource.LUNARWERX to other), emptyMap(), now).single()
        assertEquals(30.0, combined.percent, 0.0)
        val conflict = api(ResetSource.CODEX_RESET, listOf(ForecastChance(24, 20.0), ForecastChance(24, 50.0)))
        assertTrue(ForecastConsensus.combine("Codex", mapOf(ResetSource.CODEX_RESET to conflict), emptyMap(), now).isEmpty())
    }
    @Test fun `invalid percentages and source identities are rejected`() {
        val values = listOf(ForecastChance(24, Double.NaN), ForecastChance(48, 101.0), ForecastChance(0, 5.0))
        assertTrue(ForecastConsensus.combine("Codex", mapOf(ResetSource.CODEX_RESET to api(ResetSource.CODEX_RESET, values)), emptyMap(), now).isEmpty())
        assertTrue(ForecastConsensus.combine("Codex", mapOf(ResetSource.CODEX_RESET to api(ResetSource.LUNARWERX, listOf(ForecastChance(24, 50.0)))), emptyMap(), now).isEmpty())
    }
}
