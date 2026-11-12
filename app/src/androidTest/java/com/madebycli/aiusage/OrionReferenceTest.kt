package com.madebycli.aiusage

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Screenshots of the unmodified, official Orion 2.7.5 APK on the same emulator. */
@RunWith(AndroidJUnit4::class)
class OrionReferenceTest {
    @Test fun settingsReference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val theme = InstrumentationRegistry.getArguments().getString("referenceTheme") ?: "dark"
        fun capture(name: String) {
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "visual-audit/orion/$theme").apply { mkdirs() }
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            check(bitmap.width == 1080 && bitmap.height == 2424)
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        device.executeShellCommand("am start -W -n com.orion.store/.MainActivity")
        check(device.wait(Until.hasObject(By.pkg("com.orion.store")), 30000))
        repeat(8) {
            listOf("Let's go", "Not now", "Later", "Skip", "Got it", "Maybe later", "No thanks").forEach { label ->
                device.findObject(By.text(label))?.click()
            }
            if (device.hasObject(By.desc("Settings"))) return@repeat
            if (device.hasObject(By.text("Native at last"))) device.swipe(540, 1800, 540, 700, 25)
            SystemClock.sleep(500)
        }
        capture("home")
        check(device.wait(Until.hasObject(By.desc("Settings")), 15000)) { "Orion settings entry unavailable; inspect reference-home and logcat" }
        device.findObject(By.desc("Settings")).click()
        SystemClock.sleep(1200)
        capture("settings")
        listOf("Look & feel" to "appearance", "Interface" to "navigation").forEach { (title, file) ->
            var item = device.findObject(By.textContains(title))
            repeat(5) {
                if (item == null) {
                    device.swipe(540, 1900, 540, 700, 25)
                    SystemClock.sleep(300)
                    item = device.findObject(By.textContains(title))
                }
            }
            checkNotNull(item) { "Missing original Orion category $title" }.click()
            SystemClock.sleep(1200)
            capture(file)
            device.pressBack()
            SystemClock.sleep(700)
        }
    }
}
