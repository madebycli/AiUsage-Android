package com.madebycli.aiusage

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

class AiUsageApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Texts.init(this)
    }
}

/**
 * Text for code without a Compose context: data layer errors, notifications, widgets. Always the app's current
 * language, read from the same string resources as the UI.
 */
object Texts {
    @Volatile private var context: Context? = null
    fun init(context: Context) { this.context = context.applicationContext }
    private val res: Resources? get() = context?.resources
    fun get(@StringRes id: Int, vararg args: Any): String = res?.getString(id, *args) ?: fallback(id)
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = res?.getQuantityString(id, count, *args) ?: fallback(id)
    /** Only in plain JVM tests without Android resources. */
    private fun fallback(id: Int) = "#$id"
}
