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

    @Test fun `a firing reminder follows the providers newest report`() {
        val rule = ResetAlerts.rules.first { it.provider == "Codex" && it.seconds == 18000L }
        fun report(reset: Long?) = snapshot(10000, 10.0, reset = reset)
        // ahead of the reset
        assertEquals(Decision.ANNOUNCE, ResetAlerts.decide(rule, report(15000), true, 15000, 14000))
        assertEquals(Decision.ANNOUNCE, ResetAlerts.decide(rule, null, false, 15000, 14000))
        assertEquals(Decision.RESCHEDULE, ResetAlerts.decide(rule, report(20000), true, 15000, 14000))
        // at or after the reset
        assertEquals(Decision.UNVERIFIED, ResetAlerts.decide(rule, null, false, 15000, 15000))
        assertEquals(Decision.RESET, ResetAlerts.decide(rule, report(33000), true, 15000, 15010))
        assertEquals(Decision.RESET, ResetAlerts.decide(rule, report(null), true, 15000, 15010))
        assertEquals(Decision.RESET, ResetAlerts.decide(rule, UsageSnapshot(null, emptyList(), 1), true, 15000, 15010))
        assertEquals(Decision.RECHECK, ResetAlerts.decide(rule, report(15001), true, 15000, 15010))
        assertEquals(Decision.OVERDUE, ResetAlerts.decide(rule, report(15001), true, 15000, 15000 + ResetAlerts.RECHECK_SECONDS + 1))
        // another provider's window never counts
        assertEquals(Decision.RESET, ResetAlerts.decide(rule, snapshot(10000, 10.0, "Claude Code", 15000), true, 15000, 15010))
    }

    private fun claudeUsage(reset: Long) = UsageSnapshot(null, listOf(QuotaGroup("Claude Code", listOf(UsageWindow(40.0, 18000, reset)))), 1)

    @Test fun `scheduling arms one alarm at the reminder time and follows a moved reset`() {
        val prefs = ResetAlerts.prefs(context)
        val rule = ResetAlerts.rules.first { it.provider == "Claude Code" && it.seconds == 18000L }
        val reset = java.time.Instant.now().epochSecond + 100_000
        prefs.edit().putBoolean("enabled", true).putString("generation.Claude Code", "g").putInt("lead.${rule.key}", 15).commit()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val alarms = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        ResetAlerts.schedule(context, "Claude Code", claudeUsage(reset))
        assertEquals((reset - 900) * 1000, alarms.peekNextScheduledAlarm()!!.triggerAtTime)
        assertTrue(ResetAlarms.isArmed(context, rule))
        ResetAlerts.schedule(context, "Claude Code", claudeUsage(reset + 1))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals((reset - 900) * 1000, alarms.peekNextScheduledAlarm()!!.triggerAtTime)
        ResetAlerts.schedule(context, "Claude Code", claudeUsage(reset + 5000))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals((reset + 5000 - 900) * 1000, alarms.peekNextScheduledAlarm()!!.triggerAtTime)
        assertNotNull(ResetAlerts.nextReminder(context, rule))
    }

    @Test fun `alarms come back after a reboot and disappear with the account`() {
        val prefs = ResetAlerts.prefs(context)
        val rule = ResetAlerts.rules.first { it.provider == "Claude Code" && it.seconds == 18000L }
        val reset = java.time.Instant.now().epochSecond + 50_000
        prefs.edit().putBoolean("enabled", true).putString("generation.Claude Code", "g").commit()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ResetAlerts.schedule(context, "Claude Code", claudeUsage(reset))
        ResetAlarms.cancel(context, rule)
        assertFalse(ResetAlarms.isArmed(context, rule))
        ResetAlerts.rearm(context)
        assertTrue(ResetAlarms.isArmed(context, rule))
        ResetAlerts.invalidate(context, "Claude Code")
        assertFalse(ResetAlarms.isArmed(context, rule))
        assertNull(ResetAlerts.nextReminder(context, rule))
    }

    @Test fun `notification workers ignore disabled stale account generations`() = runBlocking {
        val rule = ResetAlerts.rules.first()
        val prefs = ResetAlerts.prefs(context)
        prefs.edit().putBoolean("enabled", false).putString("generation.Codex", "new").putString("scheduled.${rule.key}", "old:200000:0").commit()
        val worker = TestListenableWorkerBuilder<ResetAlertWorker>(context)
            .setInputData(workDataOf("rule" to rule.key, "reset" to 200000L, "generation" to "old", "scheduled" to "old:200000:0")).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertNull(prefs.getString("delivered.${rule.key}", null))
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
