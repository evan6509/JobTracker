package com.evanchubbuck.jobtracker

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class JobSiteEditorUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val value = AtomicReference("")
    private val example = AddressSuggestion("123 Main Street", "Madison, Wisconsin", AddressPoint(43.07, -89.38))

    @Before fun requireUnlockedDisplay() {
        val context = instrumentation.targetContext
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        org.junit.Assume.assumeTrue("Screen tests require an awake, unlocked phone",
            power.isInteractive && !keyguard.isKeyguardLocked)
    }

    private fun await(description: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            if (check()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $description")
    }

    private fun find(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (test(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), test)?.let { return it }
        return null
    }

    private fun click(text: String) = await(text) {
        var node = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
        while (node != null && !node.isClickable) node = node.parent
        node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun type(text: String) = await("address input") {
        val node = find(instrumentation.uiAutomation.rootInActiveWindow) { it.className == "android.widget.EditText" }
        node?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }) == true
    }

    private fun screen(lookup: AddressLookup, showNavigate: Boolean = true, test: () -> Unit) {
        val originalJobs = JobStore(instrumentation.targetContext).jobs()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme {
                    var address by remember { mutableStateOf("") }
                    Box(Modifier.padding(24.dp)) {
                        JobSiteEditor(address, { address = it; value.set(it) }, {}, lookup,
                            locationProvider = null, showNavigate = showNavigate)
                    }
                }
            } }
            test()
        }
        assertEquals("Address editor tests never alter saved jobs", originalJobs, JobStore(instrumentation.targetContext).jobs())
    }

    @Test fun selectingSuggestionFillsAddressAndClosesSuggestions() {
        val lookup = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(example)

        }
        screen(lookup) {
            type("123")
            click(example.street)
            await("selected address") { value.get() == example.address }
            await("closed suggestion list") {
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == example.street } == null
            }
        }
    }

    @Test fun existingJobSiteEditorDoesNotShowAnotherNavigateButton() {
        val lookup = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()
        }
        screen(lookup, showNavigate = false) {
            type("123 Example St")
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString() == "Navigate"
            })
        }
    }

    @Test fun longSuggestionListsCanScrollToAndSelectLaterResults() {
        val results = (1..15).map { index -> AddressSuggestion("123 Example Street $index",
            "Example City", AddressPoint(41.57, -87.18)) }
        val lookup = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = results
        }
        screen(lookup) {
            type("123")
            await("scrollable suggestions") {
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable } != null
            }
            val last = results.last()
            repeat(20) {
                if (find(instrumentation.uiAutomation.rootInActiveWindow) {
                        it.text?.toString() == last.street
                    } != null) return@repeat
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                SystemClock.sleep(100)
            }
            click(last.street)
            await("selected result after scrolling") { value.get() == last.address }
        }
    }

    @Test fun selectingStreetOnlyMatchKeepsTheEnteredHouseNumber() {
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()

        }
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) =
                listOf(AddressSuggestion("Example Avenue", "Example City", example.point, houseNumber = ""))

        }
        screen(JobAddressLookup(phone, photon)) {
            type("1234 Example Ave")
            await("street match explanation") {
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.toString() == "We found the street, but not this specific house number. The number you typed is kept."
                } != null
            }
            click("1234 Example Avenue")
            await("preserved house number") { value.get() == "1234 Example Avenue, Example City" }
        }
    }

    @Test fun anApproximateRangeShowsItsLimitBeforeSelection() {
        val lookup = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(
                AddressSuggestion("5791 Kingman Ave", "Portage, IN 46368, United States",
                    AddressPoint(41.54, -87.19), houseNumber = "", numberFromEntry = true, rangeMatch = true))
        }
        screen(lookup) {
            type("5791 Kingman Ave, Portage IN")
            await("approximate address label") {
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.toString() == "Possible address. Check the street and city before selecting."
                } != null
            }
            click("5791 Kingman Ave")
            await("approximate address selected") {
                value.get() == "5791 Kingman Ave, Portage, IN 46368, United States"
            }
        }
    }

}
