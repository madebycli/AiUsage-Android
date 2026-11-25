package com.madebycli.aiusage.background

import android.content.Context
import com.madebycli.aiusage.data.HttpFailure
import com.madebycli.aiusage.data.appJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** The last background reads, so it can be checked on the phone that they really happen. No tokens, no values. */
object BackgroundLog {
    const val MAX = 100
    private const val KEY = "entries"

    @Serializable data class Result(val account: String, val ok: Boolean, val reason: String? = null)
    @Serializable data class Entry(val at: Long, val trigger: SyncTrigger, val millis: Long, val results: List<Result>) {
        val ok get() = results.isNotEmpty() && results.all { it.ok }
    }

    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("background-log", 0)

    fun read(context: Context): List<Entry> = synchronized(lock) {
        prefs(context).getString(KEY, null)?.let { runCatching { appJson.decodeFromString<List<Entry>>(it) }.getOrNull() }.orEmpty()
    }

    fun add(context: Context, entry: Entry) = synchronized(lock) {
        val list = (listOf(entry) + read(context)).take(MAX)
        prefs(context).edit().putString(KEY, appJson.encodeToString(list)).apply()
    }

    fun clear(context: Context) = synchronized(lock) { prefs(context).edit().remove(KEY).apply() }

    /** A short, stable code instead of a message: messages can carry provider text. */
    fun reason(error: Exception): String = when (error) {
        is HttpFailure -> "http-${error.status}"
        is java.io.IOException -> "network"
        is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
        else -> error::class.simpleName?.take(40) ?: "error"
    }
}
