package com.madebycli.aiusage.account

import com.madebycli.aiusage.ProviderState
import com.madebycli.aiusage.Providers
import com.madebycli.aiusage.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun MutableStateFlow<UiState>.updateProvider(provider: String, change: ProviderState.() -> ProviderState) =
    update { if (provider == Providers.CODEX) it.copy(codex = it.codex.change()) else it.copy(claude = it.claude.change()) }

internal fun MutableStateFlow<UiState>.updateCodex(change: ProviderState.() -> ProviderState) = updateProvider(Providers.CODEX, change)
internal fun MutableStateFlow<UiState>.updateClaude(change: ProviderState.() -> ProviderState) = updateProvider(Providers.CLAUDE, change)
