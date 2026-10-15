package com.madebycli.aiusage

import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.madebycli.aiusage.background.*
import com.madebycli.aiusage.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    @Before fun init() {
        context.getSharedPreferences("alerts", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun snapshot(at: Long, percent: Double, provider: String = "Codex", reset: Long? = 200000) = UsageSnapshot(null,
        listOf(QuotaGroup(provider, listOf(UsageWindow(percent, 18000, reset)))), at)

    @Test fun `three hour work replaces retired schedules, stays unique and can be disabled`() {
        val work = WorkManager.getInstance(context)
        listOf("daily-account-measurement", "four-hour-account-refresh").forEach {
            work.enqueueUniquePeriodicWork(it, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<AccountRefreshWorker>(24, TimeUnit.HOURS).build())
        }
        AccountRefresh.sync(context, true)
        AccountRefresh.sync(context, true)
        assertTrue(work.getWorkInfosForUniqueWork("daily-account-measurement").get().all { it.state.isFinished })
        assertTrue(work.getWorkInfosForUniqueWork("four-hour-account-refresh").get().all { it.state.isFinished })
        assertEquals(TimeUnit.HOURS.toMillis(3), AccountRefresh.request().workSpec.intervalDuration)
        val infos = work.getWorkInfosForUniqueWork(AccountRefresh.NAME).get().filter { !it.state.isFinished }
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        context.getSharedPreferences("alerts", 0).edit().putBoolean("daily", false).commit()
        AccountRefresh.sync(context, true)
        assertTrue(work.getWorkInfosForUniqueWork(AccountRefresh.NAME).get().all { it.state.isFinished })
    }

    @Test fun `worker without credentials returns success without inventing a measurement`() = runBlocking {
        val result = TestListenableWorkerBuilder<AccountRefreshWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(context.noBackupFilesDir.resolve("usage.enc").exists())
        assertFalse(context.noBackupFilesDir.resolve("claude.usage.enc").exists())
    }

    @Test fun `alerts match the reported provider window and tolerate jitter or a rolled over window`() {
        val rule = ResetAlerts.rules.first { it.provider == "Codex" && it.seconds == 18000L }
        assertTrue(ResetAlerts.valid(rule, snapshot(10000, 10.0, reset = 15000), 15000, 15000))
        assertTrue(ResetAlerts.valid(rule, snapshot(10000, 10.0, reset = 15001), 15000, 15000))
        assertTrue(ResetAlerts.valid(rule, snapshot(10000, 10.0, reset = 33000), 15000, 15100))
        assertFalse(ResetAlerts.valid(rule, snapshot(10000, 10.0, "Claude Code", 15000), 15000, 15000))
        assertFalse(ResetAlerts.valid(rule, snapshot(10000, 10.0, reset = 14000), 15000, 15000))
        assertFalse(ResetAlerts.valid(rule, snapshot(10000, 10.0, reset = 15000), 15000, 36601))
        assertFalse(ResetAlerts.valid(rule, snapshot(10000, 10.0, reset = null), 15000, 15000))
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

    @Test fun `a refresh with the same reset within the jitter keeps the pending reminder`() {
        val prefs = ResetAlerts.prefs(context)
        val rule = ResetAlerts.rules.first { it.provider == "Claude Code" && it.seconds == 604800L }
        val future = java.time.Instant.now().epochSecond + 100_000
        prefs.edit().putBoolean("enabled", true).putString("generation.Claude Code", "g").putInt("lead.${rule.key}", 0).commit()
        fun usage(reset: Long) = UsageSnapshot(null, listOf(QuotaGroup("Claude Code", listOf(UsageWindow(40.0, 604800, reset)))), 1)
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ResetAlerts.schedule(context, "Claude Code", usage(future))
        val first = prefs.getString("scheduled.${rule.key}", null)
        assertNotNull(first)
        ResetAlerts.schedule(context, "Claude Code", usage(future + 1))
        assertEquals(first, prefs.getString("scheduled.${rule.key}", null))
    }
}
