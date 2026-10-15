package com.madebycli.aiusage.data

data class HistorySource(val id: String, val label: String)

fun historySources(provider: String): List<HistorySource> = buildList {
    if (provider == "Codex") ResetSource.entries.forEach { add(HistorySource("api:${it.name}", it.label)) }
    CommunitySource.entries.filter { it.provider == provider }.forEach { add(HistorySource("page:${it.name}", it.label)) }
}

fun historyEvents(provider: String, sourceId: String, feed: ResetFeed?, community: Map<CommunitySource, CommunityResult>): List<ResetEvent> {
    val events = if (sourceId.startsWith("api:") && provider == "Codex") {
        val source = ResetSource.entries.firstOrNull { "api:${it.name}" == sourceId }
        feed?.takeIf { it.source == source }?.events.orEmpty()
    } else {
        val source = CommunitySource.entries.firstOrNull { "page:${it.name}" == sourceId && it.provider == provider }
        community[source]?.report?.takeIf { it.source == source }?.events.orEmpty()
    }
    return events.sortedByDescending { it.at }.distinctBy { event ->
        event.url?.let { runCatching { val uri = java.net.URI(it); "${uri.host?.lowercase()}${uri.path}" }.getOrNull() }
            ?: "${event.at}:${event.kind}:${event.summary.trim()}"
    }.take(100)
}
