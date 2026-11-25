package com.madebycli.aiusage.data

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

class SessionGeneration {
    private val value = AtomicLong()
    fun current(): Long = value.get()
    @Synchronized fun advance(): Long = value.incrementAndGet()
    fun isCurrent(generation: Long): Boolean = value.get() == generation
    @Synchronized fun commitIfCurrent(generation: Long, action: () -> Unit): Boolean {
        if (!isCurrent(generation)) return false
        action()
        return true
    }
}

suspend fun persistAccountRecord(write: suspend () -> Unit): String? = try {
    write()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    "Live-Daten geladen; der Verlauf konnte nicht gespeichert werden."
}


enum class LoginPhase(@androidx.annotation.StringRes val label: Int) {
    REQUEST_CODE(com.madebycli.aiusage.R.string.phase_request),
    WAITING(com.madebycli.aiusage.R.string.phase_waiting),
    EXCHANGING(com.madebycli.aiusage.R.string.phase_exchange),
    SAVING(com.madebycli.aiusage.R.string.phase_exchange),
    LOADING_USAGE(com.madebycli.aiusage.R.string.phase_usage)
}

fun loginFailure(phase: LoginPhase, error: Exception): String {
    val advice = when {
        phase == LoginPhase.SAVING -> "Anmeldung konnte auf diesem Gerät nicht gespeichert werden. Lokale Account-Daten zurücksetzen und erneut verbinden."
        phase == LoginPhase.REQUEST_CODE && error is HttpFailure && error.status in listOf(403, 404) ->
            "OpenAI verweigert den Gerätecode. Gerätecode-Anmeldung in ChatGPT unter Einstellungen → Sicherheit aktivieren; bei Workspaces den Administrator prüfen lassen."
        phase == LoginPhase.EXCHANGING && error is HttpFailure && error.status in listOf(400, 401) ->
            "Browser-Freigabe konnte nicht übernommen werden. Bitte mit einem neuen Code erneut verbinden."
        else -> safeError(error)
    }
    val status = (error as? HttpFailure)?.let { " · HTTP ${it.status}" }.orEmpty()
    return "${com.madebycli.aiusage.Texts.get(phase.label)}$status. $advice"
}

// Only a phase, numeric status and class name. Never bodies, codes, URLs, tokens or stack traces.
fun diagnosticFailure(operation: String, error: Exception): String =
    "$operation failed kind=${error.javaClass.simpleName} status=${(error as? HttpFailure)?.status ?: "none"}" +
        (error as? ClaudeUsageFormatException)?.let { " section=${it.section.name}" }.orEmpty()
