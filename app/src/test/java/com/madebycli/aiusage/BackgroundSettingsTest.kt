package com.madebycli.aiusage

import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.SynchronousExecutor
import com.madebycli.aiusage.background.*
import com.madebycli.aiusage.data.StatsBeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    @Before fun init() {
        context.getSharedPreferences("alerts", 0).edit().clear().commit()
        context.getSharedPreferences("features", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @Test fun `four hour work migrates old daily schedule is unique and disabled removes it`() {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork("daily-account-measurement", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyUsageWorker>(24, java.util.concurrent.TimeUnit.HOURS).build())
        DailyRefresh.sync(context, true)
        DailyRefresh.sync(context, true)
        assertTrue(work.getWorkInfosForUniqueWork("daily-account-measurement").get().all { it.state.isFinished })
        assertEquals(java.util.concurrent.TimeUnit.HOURS.toMillis(4), DailyRefresh.request().workSpec.intervalDuration)
        val infos = work.getWorkInfosForUniqueWork(DailyRefresh.NAME).get().filter { !it.state.isFinished }
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        context.getSharedPreferences("alerts", 0).edit().putBoolean("daily", false).commit()
        DailyRefresh.sync(context, true)
        assertTrue(work.getWorkInfosForUniqueWork(DailyRefresh.NAME).get().all { it.state.isFinished })
    }
    @Test fun `worker without credentials returns success without inventing a measurement`() = runBlocking {
        val result = TestListenableWorkerBuilder<DailyUsageWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(context.noBackupFilesDir.resolve("usage.enc").exists())
        assertFalse(context.noBackupFilesDir.resolve("claude.usage.enc").exists())
    }
    @Test fun `statistics opt in persists but stale background reports are rejected after opt out`() {
        assertFalse(StatsBeta.enabled(context))
        StatsBeta.setEnabled(context, true)
        val revision = StatsBeta.revision(context)
        var writes = 0
        StatsBeta.ifCurrent(context, revision) { writes++ }
        assertEquals(1, writes)
        StatsBeta.setEnabled(context, false)
        StatsBeta.ifCurrent(context, revision) { writes++ }
        StatsBeta.setEnabled(context, true)
        StatsBeta.ifCurrent(context, revision) { writes++ }
        assertEquals(1, writes)
        assertTrue(StatsBeta.enabled(context))
        // Quota refresh and reminder configuration are independent of the beta switch.
        DailyRefresh.sync(context, true)
        StatsBeta.setEnabled(context, false)
        DailyRefresh.sync(context, true)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(DailyRefresh.NAME).get().count { !it.state.isFinished })
    }
    @Test fun `notification workers ignore disabled stale account generations`() = runBlocking {
        val rule = ResetAlerts.rules.first()
        val prefs = ResetAlerts.prefs(context)
        prefs.edit().putBoolean("enabled", false).putString("generation.Codex", "new").putString("scheduled.${rule.key}", "old:200000:0").commit()
        val worker = TestListenableWorkerBuilder<ResetAlertWorker>(context)
            .setInputData(workDataOf("rule" to rule.key, "reset" to 200000L, "generation" to "old", "scheduled" to "old:200000:0")).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertNull(prefs.getString("delivered.${rule.key}", null))
        assertNull(prefs.getString("scheduled.${rule.key}", null))
        ResetAlerts.invalidate(context, "Codex")
        assertNotEquals("new", prefs.getString("generation.Codex", null))
        assertNull(prefs.getString("generation.Claude Code", null))
    }
    @Test fun `a replaced reminder cannot remove the new schedule`() = runBlocking {
        val rule = ResetAlerts.rules.first()
        val prefs = ResetAlerts.prefs(context)
        prefs.edit().putString("scheduled.${rule.key}", "new:200000:15").commit()
        val worker = TestListenableWorkerBuilder<ResetAlertWorker>(context)
            .setInputData(workDataOf("rule" to rule.key, "reset" to 200000L, "generation" to "old", "scheduled" to "old:200000:0")).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("new:200000:15", prefs.getString("scheduled.${rule.key}", null))
    }

}
