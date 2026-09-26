package com.evanchubbuck.jobtracker

import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

/** Dispatch through the view tree; older Espresso input injection is incompatible with Android 37. */
class DeleteSwipeUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var cardBounds = Rect.Zero

    private fun testCard(enabled: Boolean, test: (View, Float, AtomicInteger, AtomicInteger, AtomicInteger) -> Unit) {
        val confirm = AtomicInteger(); val delete = AtomicInteger(); val move = AtomicInteger()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().padding(20.dp)) {
                            SwipeDeleteContainer("Delete test item", enabled,
                                onDelete = { confirm.incrementAndGet() }, onDeleteImmediately = { delete.incrementAndGet() },
                                onMoveToTop = { move.incrementAndGet() }) {
                                Card(Modifier.fillMaxWidth().height(120.dp).onGloballyPositioned {
                                    cardBounds = it.boundsInWindow()
                                }) { Text("Test item") }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            var content: View? = null
            var density = 1f
            val drawn = CountDownLatch(1)
            scenario.onActivity { activity ->
                content = activity.findViewById(android.R.id.content)
                density = activity.resources.displayMetrics.density
                content!!.postOnAnimation { content!!.postOnAnimation { drawn.countDown() } }
            }
            assertTrue("Test card should draw before touch input", drawn.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(200)
            instrumentation.waitForIdleSync()
            test(content!!, density, confirm, delete, move)
        }
    }

    private fun drag(view: View, density: Float, from: Float, to: Float, beforeRelease: () -> Unit = {}) {
        val bounds = cardBounds
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationInWindow(location) }
        val width = bounds.width
        assertTrue("Test card must have a measured width", width > 0f)
        val y = bounds.center.y - location[1]
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, time: Long) {
            instrumentation.runOnMainSync {
                MotionEvent.obtain(downTime, time, action, bounds.left + width * x - location[0], y, 0).also {
                    it.source = InputDevice.SOURCE_TOUCHSCREEN
                    view.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
        }
        event(MotionEvent.ACTION_DOWN, from, downTime)
        for (step in 1..12) {
            SystemClock.sleep(25)
            event(MotionEvent.ACTION_MOVE, from + (to - from) * step / 12f, SystemClock.uptimeMillis())
        }
        instrumentation.waitForIdleSync()
        beforeRelease()
        event(MotionEvent.ACTION_UP, to, SystemClock.uptimeMillis())
        instrumentation.waitForIdleSync()
    }

    @Test fun longSwipeDeletesOnlyAfterReleaseWhenEnabled() = testCard(true) { view, density, confirm, delete, move ->
        drag(view, density, .95f, .1f) { assertEquals(0, delete.get()) }
        assertEquals(1, delete.get())
        assertEquals(0, confirm.get())
        assertEquals(0, move.get())
    }

    @Test fun longSwipeStillConfirmsWhenDisabled() = testCard(false) { view, density, confirm, delete, _ ->
        drag(view, density, .95f, .1f)
        assertEquals(1, confirm.get())
        assertEquals(0, delete.get())
    }

    @Test fun shorterSwipeConfirmsAndRightSwipeMovesToTop() = testCard(true) { view, density, confirm, delete, move ->
        drag(view, density, .95f, .3f)
        assertEquals(1, confirm.get())
        assertEquals(0, delete.get())
        drag(view, density, .1f, .9f)
        assertEquals(1, move.get())
        assertEquals(0, delete.get())
    }
}
