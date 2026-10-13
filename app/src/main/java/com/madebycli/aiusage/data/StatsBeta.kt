package com.madebycli.aiusage.data

import android.content.Context
import androidx.core.content.edit

/** Explicit opt-in shared by the UI and account worker; old installs stay opted out. */
object StatsBeta {
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("features", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("usageStatsBeta", false)
    fun revision(context: Context) = prefs(context).getLong("usageStatsRevision", 0)
    fun setEnabled(context: Context, enabled: Boolean) = synchronized(lock) {
        prefs(context).edit {
            putBoolean("usageStatsBeta", enabled)
            putLong("usageStatsRevision", revision(context) + 1)
        }
    }
    fun isCurrent(context: Context, revision: Long) = enabled(context) && revision(context) == revision
    /** Discard a report fetched before an opt-out, even if the user opted back in. */
    fun ifCurrent(context: Context, revision: Long, action: () -> Unit) = synchronized(lock) {
        if (isCurrent(context, revision)) action()
    }
}
