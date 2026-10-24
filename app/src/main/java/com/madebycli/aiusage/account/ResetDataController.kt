package com.madebycli.aiusage.account

import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** Public reset history, forecasts and community estimates. None of it is personal account data. */
class ResetDataController(
    scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    http: HttpTransport
) {
    private val history = ResetRepository(http)
    private val forecasts = ForecastRepository(http)
    private val community = CommunityRepository(http)
    private val feedQueue = RefreshQueue(scope) { readFeed() }
    private val forecastQueue = RefreshQueue(scope) { readForecasts() }

    fun refresh() {
        if (state.value.hasProvider(Providers.CODEX)) feedQueue.request()
        if (state.value.anyConnected) forecastQueue.request()
    }

    fun setSource(source: ResetSource) {
        state.update { it.copy(source = source, feed = null, feedError = null, nextFeedSeconds = 0) }
        if (state.value.hasProvider(Providers.CODEX)) feedQueue.request()
    }

    private suspend fun readFeed() {
        if (!state.value.hasProvider(Providers.CODEX)) return
        val source = state.value.source
        state.update { it.copy(feedLoading = true) }
        try {
            val result = history.fetch(source)
            if (state.value.source == source) state.update {
                it.copy(feed = result.feed, feedError = result.error ?: if (result.feed == null) it.feedError else null,
                    nextFeedSeconds = result.nextFetchSeconds)
            }
        } finally { state.update { it.copy(feedLoading = false) } }
    }

    private suspend fun readForecasts() {
        if (!state.value.anyConnected) return
        state.update { it.copy(forecastLoading = true) }
        try {
            // Sources are independent: a slow or failed source never removes the others.
            coroutineScope {
                val codexJobs = if (state.value.hasProvider(Providers.CODEX)) ResetSource.entries.map { source -> launch {
                    val result = forecasts.fetch(source)
                    state.update { if (!it.hasProvider(Providers.CODEX)) it else it.copy(forecasts = it.forecasts + (source to result)) }
                } } else emptyList()
                val communityJobs = CommunitySource.entries.filter { state.value.hasProvider(it.provider) }.map { source -> launch {
                    val result = community.fetch(source)
                    state.update { if (!it.hasProvider(source.provider)) it else it.copy(community = it.community + (source to result)) }
                } }
                (codexJobs + communityJobs).joinAll()
            }
        } finally { state.update { it.copy(forecastLoading = false) } }
    }
}
