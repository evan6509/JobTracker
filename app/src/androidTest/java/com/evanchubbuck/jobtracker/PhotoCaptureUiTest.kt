package com.evanchubbuck.jobtracker

import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import java.io.File

/** Use accessibility actions; this device's Android version cannot use the old Espresso injector. */
class PhotoCaptureUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun wakeDisplay() {
        listOf("input keyevent 224", "input keyevent 82", "wm dismiss-keyguard").forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
    }

    private fun waitFor(description: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    private fun find(node: AccessibilityNodeInfo?, text: String, button: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) {
            if (!button) return node
            var control: AccessibilityNodeInfo? = node
            while (control != null) {
                if (control.isClickable && control.isEnabled) return control
                control = control.parent
            }
        }
        for (index in 0 until node.childCount) find(node.getChild(index), text, button)?.let { return it }
        return null
    }

    private fun click(text: String) {
        waitFor("$text button") {
            find(instrumentation.uiAutomation.rootInActiveWindow, text, true)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun waitForReview() = waitFor("photo review") {
        find(instrumentation.uiAutomation.rootInActiveWindow, "Preview photo", false) != null
    }

    private fun captures() = File(context.cacheDir, "captures").listFiles().orEmpty().toSet()

    @Test fun cameraCapturesRetakesAndReturnsARealPhotoAfterRotation() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        assumeTrue(context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val before = captures()
        val jobs = JobStore(context).jobs()
        var resultFile: File? = null
        try {
            ActivityScenario.launchActivityForResult(PhotoCaptureActivity::class.java).use { scenario ->
                click("Take photo")
                waitForReview()
                val first = (captures() - before).single()
                assertTrue(first.length() > 0)
                scenario.recreate()
                waitForReview()
                assertTrue("Review photo survives activity recreation", first.exists())
                click("Retake")
                waitFor("discarded first photo") { !first.exists() }
                click("Take photo")
                waitForReview()
                click("Use photo")
                val result = scenario.result
                assertEquals(Activity.RESULT_OK, result.resultCode)
                resultFile = File(result.resultData!!.getStringExtra(PhotoCaptureActivity.PHOTO_PATH)!!)
                assertEquals(File(context.filesDir, "photos").canonicalFile, resultFile!!.canonicalFile.parentFile)
                assertTrue(resultFile!!.length() > 0)
                val bitmap = sampledBitmap(resultFile!!.path)
                assertNotNull("Camera result is a decodable full-resolution image", bitmap)
                bitmap?.recycle()
            }
            waitFor("temporary photo cleanup") { captures() == before }
            assertEquals("Camera doesn't change any jobs before its caller attaches the result", jobs, JobStore(context).jobs())
        } finally {
            resultFile?.delete()
            (captures() - before).forEach { it.delete() }
        }
    }

    @Test fun cancellingAReviewDiscardsThePicture() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        assumeTrue(context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        val before = captures()
        try {
            ActivityScenario.launchActivityForResult(PhotoCaptureActivity::class.java).use { scenario ->
                click("Take photo")
                waitForReview()
                assertTrue((captures() - before).isNotEmpty())
                click("Back")
                assertEquals(Activity.RESULT_CANCELED, scenario.result.resultCode)
            }
            waitFor("cancelled photo cleanup") { captures() == before }
        } finally { (captures() - before).forEach { it.delete() } }
    }
}
