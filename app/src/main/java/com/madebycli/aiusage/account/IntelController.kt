package com.madebycli.aiusage.account

import com.madebycli.aiusage.UiState
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Public reset estimates and history. None of it is personal account data. */
class IntelController(scope: CoroutineScope, private val state: MutableStateFlow<UiState>, http: HttpTransport) {
    private val repository = IntelRepository(http)
    private val queue = RefreshQueue(scope) { read() }
    @Volatile private var forceNext = false

    /** [force] ignores the pause between reads; it is for the refresh button, not for opening a page. */
    fun refresh(force: Boolean = false) {
        if (!state.value.anyConnected) return
        if (force) forceNext = true
        queue.request()
    }

    private suspend fun read() {
        val force = forceNext
        forceNext = false
        val providers = state.value.connectedProviders
        if (providers.isEmpty()) return
        state.update { it.copy(intelLoading = true) }
        try {
            // Sources are independent: a slow or failed one never removes the others.
            coroutineScope {
                IntelSource.entries.filter { it.provider in providers }.forEach { source ->
                    launch {
                        val result = repository.fetch(source, force)
                        state.update { current ->
                            if (!current.hasProvider(source.provider)) current else current.copy(intel = current.intel + (source to result))
                        }
                    }
                }
            }
        } finally { state.update { it.copy(intelLoading = false) } }
    }
}
