package com.evanchubbuck.jobtracker

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.evanchubbuck.jobtracker.ui.theme.JobTrackerTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class HistoryUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val opened = AtomicReference("")
    private val navigated = AtomicReference("")

    @Before fun requireUnlockedPhone() {
        val context = instrumentation.targetContext
        assumeTrue(context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    }

    private fun screen(job: Job, dark: Boolean, test: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                JobTrackerTheme(darkTheme = dark) {
                    Surface(color = UiCanvas) {
                        HistoryScreen(listOf(job), Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                            onOpen = { opened.set(it) }, onNavigate = { navigated.set(it) })
                    }
                }
            } }
            test()
        }
    }

    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    private fun text(label: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return find(instrumentation.uiAutomation.rootInActiveWindow, label)
    }

    private fun click(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var node = text(label)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(100)
        }
        fail("Could not click $label")
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("historyScreenshots") != "true") return
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "history-preview-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun completedJobCanNavigateWithoutOpeningOrRestoringIt() {
        val job = Job(id = "completed-example", state = Job.COMPLETED,
            title = "Completed kitchen backsplash and cabinet refresh",
            address = "123 Example Lane\nSampletown, IN 46000")
        screen(job, dark = true) {
            capture("dark")
            click("Navigate")
            assertEquals(job.address, navigated.get())
            assertEquals("", opened.get())
            click(job.title)
            assertEquals(job.id, opened.get())
        }
    }

    @Test fun completedJobWithoutAnAddressCanOpenButDoesNotOfferNavigation() {
        val job = Job(id = "no-address-example", state = Job.COMPLETED, title = "Completed job without an address")
        screen(job, dark = false) {
            instrumentation.waitForIdleSync()
            capture("light-no-address")
            assertNull(text("Navigate"))
            click(job.title)
            assertEquals(job.id, opened.get())
            assertEquals("", navigated.get())
        }
    }
}
