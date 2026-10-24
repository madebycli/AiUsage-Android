package com.madebycli.aiusage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*
import com.madebycli.aiusage.MainActivity
import com.madebycli.aiusage.R
import com.madebycli.aiusage.background.AccountSync
import com.madebycli.aiusage.data.AccountRegistry
import kotlinx.coroutines.*
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The home-screen widget. All drawing happens in [WidgetRenderer]; this class only connects it to Android. */
class UsageWidgetProvider : AppWidgetProvider() {
    /** Reading the encrypted measurements and drawing take a moment, so they run off the main thread. */
    private fun async(block: () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { try { block() } finally { pending.finish() } }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        async {
            WidgetUpdater.update(context, appWidgetIds)
            WidgetUpdater.syncIfOutdated(context)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        async { WidgetUpdater.update(context, intArrayOf(appWidgetId)) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetConfigStore.delete(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) async { WidgetUpdater.refresh(context, id) }
            }
            Intent.ACTION_LOCALE_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_DATE_CHANGED -> async { WidgetUpdater.updateAll(context) }
            else -> super.onReceive(context, intent)
        }
    }

    companion object { const val ACTION_REFRESH = "com.madebycli.aiusage.WIDGET_REFRESH" }
}

object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    @Volatile private var collector: Job? = null

    private fun ids(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
    }

    fun hasWidgets(context: Context) = ids(context).isNotEmpty()

    /** Asks for a redraw soon. Several requests in a row become one. */
    fun requestUpdate(context: Context) {
        val app = context.applicationContext
        if (!hasWidgets(app)) return
        synchronized(this) {
            if (collector?.isActive != true) collector = scope.launch {
                for (request in pending) { delay(400); runCatching { updateAll(app) } }
            }
        }
        pending.trySend(Unit)
    }

    fun updateAll(context: Context) = update(context, ids(context))

    fun update(context: Context, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val accounts = WidgetData.load(app)
        val now = Instant.now().epochSecond
        widgetIds.forEach { id -> runCatching { manager.updateAppWidget(id, views(app, manager, id, accounts, now)) } }
    }

    /** Widget size in dp, from what the launcher reports. */
    fun sizeDp(context: Context, manager: AppWidgetManager, id: Int): Pair<Int, Int> {
        val options = manager.getAppWidgetOptions(id)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return (if (width > 0) width else 250) to (if (height > 0) height else 120)
    }

    private fun views(context: Context, manager: AppWidgetManager, id: Int, accounts: List<WidgetAccount>, now: Long): RemoteViews {
        val config = WidgetConfigStore.read(context, id)
        val (width, height) = sizeDp(context, manager, id)
        val refreshing = WidgetConfigStore.refreshingUntil(context, id) > now
        val bitmap = WidgetRenderer.render(context, config, accounts, width, height, now, refreshing)
        val views = RemoteViews(context.packageName, R.layout.widget_root)
        views.setImageViewBitmap(R.id.widget_image, bitmap)
        val shown = WidgetData.select(accounts, config)
        views.setContentDescription(R.id.widget_image, shown.joinToString(". ") { a ->
            "${a.title}: " + listOfNotNull(a.window(com.madebycli.aiusage.data.FIVE_HOURS)?.let { "5 Stunden ${com.madebycli.aiusage.data.formatPercent(it.usedPercent)} Prozent" },
                a.window(com.madebycli.aiusage.data.ONE_WEEK)?.let { "Woche ${com.madebycli.aiusage.data.formatPercent(it.usedPercent)} Prozent" }).joinToString(", ").ifEmpty { "keine Messung" }
        }.ifEmpty { "AI Usage" })
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        shown.singleOrNull()?.let { open.putExtra("account", it.account.id) }
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val refreshVisible = config.showRefresh && height >= 100
        views.setViewVisibility(R.id.widget_refresh, if (refreshVisible) android.view.View.VISIBLE else android.view.View.GONE)
        if (refreshVisible) {
            val refresh = Intent(context, UsageWidgetProvider::class.java).setAction(UsageWidgetProvider.ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).setData(android.net.Uri.parse("aiusage://widget/$id"))
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return views
    }

    /** The refresh button: shows "Aktualisiere…" and reads the shown accounts in the background. */
    fun refresh(context: Context, widgetId: Int) {
        val app = context.applicationContext
        val now = Instant.now().epochSecond
        WidgetConfigStore.markRefreshing(app, widgetId, now + 45)
        update(app, intArrayOf(widgetId))
        val ids = WidgetConfigStore.read(app, widgetId).accounts.toTypedArray()
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInputData(workDataOf("ids" to ids, "widget" to widgetId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork("widget-refresh-$widgetId", ExistingWorkPolicy.REPLACE, request)
    }

    /** Widgets on screen with data far older than the background interval: the periodic job was held back, read once. */
    fun syncIfOutdated(context: Context) {
        val app = context.applicationContext
        val now = Instant.now().epochSecond
        val newest = WidgetData.load(app).mapNotNull { it.fetchedAt }.maxOrNull()
        if (newest != null && now - newest < 2 * com.madebycli.aiusage.background.AccountRefresh.hours(app) * 3600) return
        if (AccountRegistry(app).list().isEmpty()) return
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(app).enqueueUniqueWork("widget-outdated", ExistingWorkPolicy.KEEP, request)
    }
}

class WidgetRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val widget = inputData.getInt("widget", -1)
        val ids = inputData.getStringArray("ids")?.toSet()?.takeIf { it.isNotEmpty() }
        try { AccountSync.run(applicationContext, ids) }
        finally {
            if (widget >= 0) WidgetConfigStore.markRefreshing(applicationContext, widget, 0)
            WidgetUpdater.updateAll(applicationContext)
        }
        return Result.success()
    }
}
