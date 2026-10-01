package com.evanchubbuck.jobtracker

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.evanchubbuck.jobtracker.ui.theme.JobTrackerTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JobEditorUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val currentJob = AtomicReference<Job>()
    private val currentStep = AtomicInteger()
    private val finishes = AtomicInteger()
    private val cameraOpens = AtomicInteger()
    private val pickerOpens = AtomicInteger()
    private lateinit var savedJobs: List<Job>
    private lateinit var savedDrafts: List<Job>

    @Before fun before() {
        val context = instrumentation.targetContext
        assumeTrue("Editor screen tests require an awake, unlocked phone",
            context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        savedJobs = JobStore(context).jobs()
        savedDrafts = JobStore(context).drafts()
    }

    @After fun noSavedDataChanged() {
        if (::savedJobs.isInitialized) {
            assertEquals(savedJobs, JobStore(instrumentation.targetContext).jobs())
            assertEquals(savedDrafts, JobStore(instrumentation.targetContext).drafts())
        }
    }

    private fun screen(initial: Job = Job(), initialStep: Int = 0, existing: Boolean = false,
        darkTheme: Boolean = true, test: () -> Unit) {
        currentJob.set(initial)
        currentStep.set(initialStep)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                var job by remember { mutableStateOf(initial) }
                var step by remember { mutableIntStateOf(initialStep) }
                PhoneTimeFormat {
                    JobTrackerTheme(darkTheme = darkTheme) {
                        Surface(color = UiCanvas) {
                            EditorScreen(job, step, existing, "",
                                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
                                onChange = { job = it; currentJob.set(it) },
                                onStep = { step = it; currentStep.set(it) },
                                onPickPhotos = { pickerOpens.incrementAndGet() },
                                onTakePhoto = { cameraOpens.incrementAndGet() }, onPhoto = {}, onRemovePhoto = {}, onNavigate = {},
                                onFinish = { if (validationError(job) == null) finishes.incrementAndGet() })
                        }
                    }
                }
            } }
            try { test() } catch (failure: Throwable) {
                capture("failure-${currentStep.get()}")
                throw failure
            }
        }
    }

    private fun await(description: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < until) {
            if (check()) return
            SystemClock.sleep(100)
        }
        fail("Timed out waiting for $description; section=${currentStep.get()}, finishes=${finishes.get()}")
    }

    private fun find(node: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (test(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), test)?.let { return it }
        return null
    }

    private fun root(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }

    private fun text(value: String) = find(root()) { it.text?.toString() == value }

    private fun calendarDay(date: String) = find(root()) {
        it.isCheckable && it.text?.toString()?.contains(date) == true
    }

    private fun chooseCalendarDay(date: String) {
        await("calendar day $date") {
            calendarDay(date)?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
        instrumentation.waitForIdleSync()
    }

    private fun openDateField(label: String) {
        val tag = "date_field_${label.lowercase(Locale.ROOT).replace(' ', '_')}"
        await("$label picker") {
            find(root()) { it.viewIdResourceName == tag }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }

    private fun switchDateMode(toInput: Boolean) {
        val description = if (toInput) "Switch to text input mode" else "Switch to calendar input mode"
        clickDescription(description)
    }

    private fun clickDescription(description: String) {
        await(description) {
            var node = find(root()) { it.contentDescription?.toString() == description }
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }

    private fun openTimePicker() {
        await("choose time") {
            var node = find(root()) { it.contentDescription?.toString() == "Choose start time" }
            if (node == null) scrollForm()
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
    }

    private fun enterTimePart(part: String, value: String) {
        await("time $part") {
            val input = find(root()) { node -> node.isEditable && find(node) {
                it.contentDescription?.toString()?.contains(part, ignoreCase = true) == true
            } != null }
            if (input == null) {
                var node = find(root()) { it.contentDescription?.toString()?.contains(part, ignoreCase = true) == true }
                while (node != null && !node.isClickable) node = node.parent
                node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return@await false
            }
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        }
        instrumentation.waitForIdleSync()
    }

    private fun setPhoneTimeMode(mode: String?) {
        val command = if (mode == null) "settings delete system time_12_24" else "settings put system time_12_24 $mode"
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        await("phone time preference") {
            Settings.System.getString(instrumentation.targetContext.contentResolver, Settings.System.TIME_12_24) == mode
        }
    }

    private fun withPhoneTimeMode(mode: String, test: () -> Unit) {
        val original = Settings.System.getString(instrumentation.targetContext.contentResolver, Settings.System.TIME_12_24)
        try { setPhoneTimeMode(mode); test() } finally { setPhoneTimeMode(original) }
    }

    private fun pickerKeyboardVisible(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        var visible = false
        instrumentation.runOnMainSync {
            visible = WindowInspector.getGlobalWindowViews().any { view ->
                view.hasWindowFocus() && view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
        }
        return visible
    }

    private fun scrollForm() = find(root()) {
        it.viewIdResourceName == "editor_section_${currentStep.get()}"
    }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    private fun swipePage(forward: Boolean) {
        swipe(horizontal = true, forward = forward)
    }

    private fun swipe(horizontal: Boolean, forward: Boolean, distance: Float = 0.6f,
        beforeRelease: (() -> Unit)? = null) {
        // Focus actions can start an IME inset animation after the UI thread is idle.
        // Read gesture bounds only once the keyboard and page have settled.
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
        val bounds = Rect()
        await("editor page bounds") {
            find(root()) { it.viewIdResourceName == "editor_pages" }
                ?.getBoundsInScreen(bounds)
            !bounds.isEmpty
        }
        val from = if (forward) 0.8f else 0.2f
        val to = if (forward) from - distance else from + distance
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, fraction: Float) {
            val x = bounds.left + bounds.width() * if (horizontal) fraction else 0.5f
            val y = bounds.top + bounds.height() * if (horizontal) 0.75f else fraction
            val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(motion, true)) }
            finally { motion.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, from)
        repeat(12) {
            SystemClock.sleep(25)
            event(MotionEvent.ACTION_MOVE, from + (to - from) * (it + 1) / 12f)
        }
        beforeRelease?.invoke()
        event(MotionEvent.ACTION_UP, to)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }

    private fun selectedTab(title: String): Boolean {
        var node = text(title)
        // Android exposes an already selected tab as selected but not clickable.
        while (node != null) {
            if (node.isSelected) return true
            node = node.parent
        }
        return false
    }

    private fun underlineCenter(): Float {
        val bounds = Rect()
        find(root()) { it.isScrollable }!!.getBoundsInScreen(bounds)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()!!
        try {
            val columns = (bounds.left until bounds.right).filter { x ->
                (bounds.bottom - 8 until bounds.bottom).any { y ->
                    val pixel = screenshot.getPixel(x, y)
                    Color.green(pixel) > Color.red(pixel) + 20 && Color.green(pixel) > Color.blue(pixel) + 10
                }
            }
            assertTrue("The tab underline should be visible", columns.size > 10)
            return (columns.first() + columns.last()) / 2f
        } finally { screenshot.recycle() }
    }

    private fun click(label: String) {
        await(label) {
            var node = text(label)
            if (node == null) {
                scrollForm()
                return@await false
            }
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
    }

    private fun section(title: String) {
        // The first scrollable area is the horizontal tab row. Start at its left edge
        // so the same helper can jump both forward and backward between tabs.
        repeat(4) {
            find(root()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            instrumentation.waitForIdleSync()
        }
        await("$title tab") {
            var node = text(title)
            while (node != null && !node.isClickable) node = node.parent
            if (node == null) {
                find(root()) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                return@await false
            }
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }

    private fun enter(label: String, value: String) {
        await(label) {
            var node = text(label)
            while (node != null && node.className != "android.widget.EditText") node = node.parent
            if (node == null) {
                scrollForm()
                return@await false
            }
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        }
        instrumentation.waitForIdleSync()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("editorScreenshots") != "true") return
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir, "editor-preview-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun materialsShowOptionalDollarPricesAndCanSaveWithoutOne() {
        val initial = Job(title = "Materials preview", clients = listOf(Person("Taylor Example")),
            inventory = listOf(InventoryItem("Tile supply", "24", "Blue tiles and adhesive", "1250.50")))
        screen(initial, initialStep = 4, existing = true) {
            await("optional price field") { text("Price (optional)") != null }
            capture("materials-priced")
            enter("Price (optional)", "")
            click("Done")
            await("save without price") { finishes.get() == 1 }
            assertEquals("", currentJob.get().inventory.single().price)
            assertEquals("24", currentJob.get().inventory.single().quantity)
        }
    }

    @Test fun invalidMaterialPriceShowsAnErrorAndCanBeCorrected() {
        val initial = Job(clients = listOf(Person("Taylor Example")), inventory = listOf(InventoryItem("Paint")))
        screen(initial, initialStep = 4, existing = true) {
            enter("Price (optional)", "-1")
            await("price guidance") { text("Enter a price of zero or more, with up to two decimal places.") != null }
            click("Done")
            assertEquals(0, finishes.get())
            enter("Price (optional)", "12.50")
            click("Done")
            await("save corrected price") { finishes.get() == 1 }
            assertEquals("12.50", currentJob.get().inventory.single().price)
        }
    }

    @Test fun creationKeepsEnteredDetailsThroughSectionsAndReview() {
        screen {
            assertNull(text("Name required for this entry."))
            click("Create job")
            await("required client name") { text("Name required for this entry.") != null }
            assertEquals(0, currentStep.get())
            enter("Job title (optional)", "Patio refresh")
            enter("Name", "Taylor Example")
            capture("clients")
            section("Job site")
            capture("site")
            section("Schedule")
            capture("schedule")
            section("Work details")
            enter("Work description", "Replace the worn patio trim and repaint the frame.")
            capture("work")
            section("Materials")
            click("Add item")
            enter("Item name", "Exterior paint")
            enter("Quantity", "2 cans")
            section("Outside workers")
            click("Add worker")
            enter("Name", "Morgan Example")
            enter("Assigned work", "Prepare and repaint the frame")
            section("Materials")
            capture("materials")
            section("Outside workers")
            capture("workers")
            section("Photos")
            capture("photos")
            section("Review")
            capture("review")
            click("Create job")
            await("finished job") { finishes.get() == 1 }
            val job = currentJob.get()
            assertEquals("Taylor Example", job.clients.single().name)
            assertEquals("Exterior paint", job.inventory.single().name)
            assertEquals("2 cans", job.inventory.single().quantity)
            assertEquals("Prepare and repaint the frame", job.workers.single().work)
            assertTrue(job.description.startsWith("Replace the worn"))
        }
    }

    @Test fun tabsCannotSkipPastStartAcknowledgement() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2000-01-01"), initialStep = 2) {
            section("Photos")
            await("past start confirmation") { text("Start is in the past") != null }
            assertEquals(2, currentStep.get())
            click("Go back")
            assertEquals(2, currentStep.get())
            section("Photos")
            click("Continue anyway")
            await("chosen section after confirmation") { currentStep.get() == 6 }
        }
    }

    @Test fun swipesChangePagesAndTabsWhileKeepingEnteredDetails() {
        screen(Job(clients = listOf(Person("Example Client")))) {
            enter("Job title (optional)", "Example renovation")
            swipePage(forward = true)
            await("job site after swipe") { currentStep.get() == 1 && selectedTab("Job site") }
            assertNotNull(text("Street address"))
            swipePage(forward = false)
            await("clients after swipe back") { currentStep.get() == 0 && selectedTab("Clients") }
            assertEquals("Example renovation", currentJob.get().title)
            assertNotNull(text("Example renovation"))
            section("Photos")
            swipePage(forward = false)
            await("workers after tab and swipe") { currentStep.get() == 5 && selectedTab("Outside workers") }
            swipePage(forward = true)
            await("photos after swipe") { currentStep.get() == 6 && selectedTab("Photos") }
            swipePage(forward = true)
            await("review after swipe") { currentStep.get() == 7 && selectedTab("Review") }
            swipePage(forward = true)
            assertEquals(7, currentStep.get())
        }
    }

    @Test fun tabUnderlineMovesDuringDragBeforePageSettles() {
        screen(Job(clients = listOf(Person("Example Client")))) {
            await("initial clients tab") { selectedTab("Clients") }
            val before = underlineCenter()
            swipe(horizontal = true, forward = true, distance = 0.35f, beforeRelease = {
                instrumentation.waitForIdleSync()
                assertEquals("The page is not committed until release", 0, currentStep.get())
                assertTrue("The underline should follow the finger before release", underlineCenter() > before + 35f)
                capture("tab-drag")
            })
        }
    }

    @Test fun swipeRequiresClientNameBeforeLeavingClients() {
        screen {
            swipePage(forward = true)
            await("required name after swipe") { text("Name required for this entry.") != null }
            assertEquals(0, currentStep.get())
            assertTrue(selectedTab("Clients"))
            enter("Name", "Example Client")
            swipePage(forward = true)
            await("job site after entering name") { currentStep.get() == 1 && selectedTab("Job site") }
        }
    }

    @Test fun swipeRequiresPastStartConfirmationAndCancelKeepsSchedule() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2000-01-01"), initialStep = 2) {
            swipePage(forward = true)
            await("past start confirmation after swipe") { text("Start is in the past") != null }
            assertEquals(2, currentStep.get())
            click("Go back")
            assertTrue(selectedTab("Schedule"))
            swipePage(forward = true)
            await("past start confirmation again") { text("Start is in the past") != null }
            click("Continue anyway")
            await("work details after acknowledgement") { currentStep.get() == 3 && selectedTab("Work details") }
        }
    }

    @Test fun swipeCannotAdvanceWithInvalidDatesButCanGoBack() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-08", endDate = "2030-10-06"), initialStep = 2) {
            swipePage(forward = true)
            assertEquals(2, currentStep.get())
            assertTrue(selectedTab("Schedule"))
            assertNotNull(text("Start date"))
            swipePage(forward = false)
            await("job site after swiping back") { currentStep.get() == 1 && selectedTab("Job site") }
        }
    }

    @Test fun verticalFormSwipeDoesNotChangeSection() {
        screen(Job(clients = List(5) { Person("Example Client ${it + 1}") })) {
            swipe(horizontal = false, forward = true)
            assertEquals(0, currentStep.get())
            assertTrue(selectedTab("Clients"))
            assertNull(text("Job title (optional)"))
        }
    }

    @Test fun invalidDateOrderBlocksSavingAndForwardTabJump() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-08", endDate = "2030-10-06"), initialStep = 2) {
            await("create job button") { text("Create job") != null }
            var button = text("Create job")
            while (button != null && !button.isClickable) button = button.parent
            assertNotNull(button)
            assertFalse(button!!.isEnabled)
            section("Work details")
            assertEquals(2, currentStep.get())
            assertEquals(0, finishes.get())
        }
    }

    @Test fun existingJobCanFinishInTheCurrentSectionAndUseBothPhotoSources() {
        screen(Job(clients = listOf(Person("Example Client"))), initialStep = 6, existing = true) {
            click("Take photo")
            click("Choose photos")
            assertEquals(1, cameraOpens.get())
            assertEquals(1, pickerOpens.get())
            click("Done")
            await("done from photo section") { finishes.get() == 1 }
            assertEquals(6, currentStep.get())
        }
    }

    @Test fun newJobCanBeCreatedFromAnyTabWithoutSteppingThroughAllSections() {
        screen(Job(clients = listOf(Person("Example Client")))) {
            section("Photos")
            click("Create job")
            await("creation from photos tab") { finishes.get() == 1 }
            assertEquals(6, currentStep.get())
        }
    }

    @Test fun finishingExistingScheduleStillRequiresPastStartAcknowledgement() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2000-01-01"), initialStep = 2, existing = true) {
            click("Done")
            await("past start confirmation") { text("Start is in the past") != null }
            assertEquals(0, finishes.get())
            click("Continue anyway")
            await("acknowledged finish") { finishes.get() == 1 }
        }
    }

    @Test fun timePickerFillsAValidTimeWithoutChangingTheDates() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-06", endDate = "2030-10-08"),
            initialStep = 2, existing = true) {
            openTimePicker()
            await("themed time picker") { text("Use time") != null }
            capture("time-clock-dark")
            click("Use time")
            await("selected time") { currentJob.get().startTime == "08:00" }
            assertEquals("2030-10-06", currentJob.get().startDate)
            assertEquals("2030-10-08", currentJob.get().endDate)
        }
    }

    @Test fun themedTimePickerSupportsTyped24HourTimeAndCancelInLightMode() {
        withPhoneTimeMode("24") {
            screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-06", endDate = "2030-10-08",
                startTime = "17:45"), initialStep = 2, existing = true, darkTheme = false) {
                openTimePicker()
                clickDescription("Switch to typed time")
                enterTimePart("hour", "22")
                enterTimePart("minute", "35")
                click("Cancel")
                assertEquals("17:45", currentJob.get().startTime)
                openTimePicker()
                clickDescription("Switch to typed time")
                enterTimePart("hour", "23")
                enterTimePart("minute", "59")
                capture("time-input-light")
                clickDescription("Switch to clock mode")
                await("clock after keyboard closes") {
                    val toggle = find(root()) { it.contentDescription?.toString() == "Switch to typed time" }
                    if (toggle != null) assertFalse(pickerKeyboardVisible())
                    toggle != null
                }
                capture("time-clock-light")
                click("Use time")
                await("selected typed time") { currentJob.get().startTime == "23:59" }
                assertEquals("2030-10-06", currentJob.get().startDate)
                assertEquals("2030-10-08", currentJob.get().endDate)
            }
        }
    }

    @Test fun phoneTimePreferenceChangesAppearanceWhileSavedAndSharedTimesStayCanonical() {
        withPhoneTimeMode("12") {
            screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-06", startTime = "17:45"),
                initialStep = 2, existing = true) {
                await("12-hour schedule field") { text("5:45 PM") != null }
                openTimePicker()
                await("AM/PM picker") { text("PM") != null && text("AM") != null }
                capture("time-clock-12-hour")
                clickDescription("Switch to typed time")
                enterTimePart("hour", "11")
                enterTimePart("minute", "35")
                click("AM")
                click("Cancel")
                assertEquals("17:45", currentJob.get().startTime)
                openTimePicker()
                clickDescription("Switch to typed time")
                enterTimePart("hour", "1")
                enterTimePart("minute", "30")
                capture("time-input-12-hour")
                click("Use time")
                await("saved PM time") { currentJob.get().startTime == "13:30" && text("1:30 PM") != null }
                assertEquals("13:30", org.json.JSONObject(sharePayload(currentJob.get())).getJSONObject("job").getString("startTime"))
                setPhoneTimeMode("24")
                await("24-hour schedule field") { text("13:30") != null }
                openTimePicker()
                await("24-hour picker") { text("13") != null && text("PM") == null && text("AM") == null }
                capture("time-clock-phone-24-hour")
                setPhoneTimeMode("12")
                await("open picker follows preference") { text("01") != null && text("PM") != null }
                click("Use time")
                await("time survives preference change") { currentJob.get().startTime == "13:30" && text("1:30 PM") != null }
                clickDescription("Clear start time")
                await("cleared optional time") { currentJob.get().startTime.isBlank() }
            }
        }
    }

    @Test fun returningFromTypedDateHidesKeyboardBeforeShowingCalendarAndKeepsSelection() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-06"),
            initialStep = 2, existing = true) {
            openDateField("Start date")
            switchDateMode(toInput = true)
            await("date keyboard") { pickerKeyboardVisible() }
            await("typed date") {
                find(root()) { it.className == "android.widget.EditText" }
                    ?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "10092030")
                    }) == true
            }
            await("typed selection") { text("October 9, 2030") != null }
            capture("date-input")
            assertEquals("2030-10-06", currentJob.get().startDate)
            switchDateMode(toInput = false)
            await("calendar after keyboard closes") {
                val day = calendarDay("October 9, 2030")
                if (day != null) assertFalse("Calendar appeared while the keyboard was visible", pickerKeyboardVisible())
                day != null
            }
            assertTrue(calendarDay("October 9, 2030")!!.isChecked)
            capture("calendar-after-input")
            switchDateMode(toInput = true)
            await("second date keyboard") { pickerKeyboardVisible() }
            switchDateMode(toInput = false)
            await("second calendar switch") { calendarDay("October 9, 2030") != null && !pickerKeyboardVisible() }
            click("Use date")
            await("save typed date") { currentJob.get().startDate == "2030-10-09" }
        }
    }

    @Test fun quicklySwitchingBackDoesNotReopenKeyboardOrChangeTheDate() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-06"),
            initialStep = 2, existing = true) {
            openDateField("Start date")
            switchDateMode(toInput = true)
            switchDateMode(toInput = false)
            await("calendar after quick switch") { calendarDay("October 6, 2030") != null }
            SystemClock.sleep(700) // Past Material's delayed input focus request.
            assertFalse(pickerKeyboardVisible())
            assertTrue(calendarDay("October 6, 2030")!!.isChecked)
            click("Cancel")
            assertEquals("2030-10-06", currentJob.get().startDate)
        }
    }

    @Test fun datePickerCancelsChangesAndKeepsTheChosenCalendarDay() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-06", endDate = "2030-10-08"),
            initialStep = 2, existing = true) {
            openDateField("Start date")
            await("calendar picker") { text("Use date") != null }
            capture("calendar-dark")
            chooseCalendarDay("October 9, 2030")
            click("Cancel")
            assertEquals("2030-10-06", currentJob.get().startDate)
            assertEquals("2030-10-08", currentJob.get().endDate)
            openDateField("Start date")
            click("Use date")
            assertEquals("2030-10-06", currentJob.get().startDate)
            openDateField("Start date")
            chooseCalendarDay("October 9, 2030")
            click("Use date")
            await("new start and cleared earlier end") {
                currentJob.get().startDate == "2030-10-09" && currentJob.get().endDate.isBlank()
            }
            assertNotNull(text("End date cleared because it came before the new start date."))
        }
    }

    @Test fun endDatePickerDisablesEarlierDaysAndAllowsTheStartDayInLightMode() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2030-10-06", endDate = "2030-10-08"),
            initialStep = 2, existing = true, darkTheme = false) {
            openDateField("End date")
            await("end calendar picker") { calendarDay("October 5, 2030") != null }
            capture("calendar-light")
            assertFalse(calendarDay("October 5, 2030")!!.isEnabled)
            assertTrue(calendarDay("October 6, 2030")!!.isEnabled)
            chooseCalendarDay("October 6, 2030")
            click("Use date")
            await("same day end") { currentJob.get().endDate == "2030-10-06" }
            assertEquals("2030-10-06", currentJob.get().startDate)
        }
    }

    @Test fun choosingAPastDateStillRequiresAcknowledgement() {
        screen(Job(clients = listOf(Person("Example Client")), startDate = "2000-01-01"),
            initialStep = 2, existing = true) {
            openDateField("Start date")
            chooseCalendarDay("January 2, 2000")
            click("Use date")
            await("past date warning") { text("Start is in the past") != null }
            assertEquals("2000-01-01", currentJob.get().startDate)
            click("Go back")
            assertEquals("2000-01-01", currentJob.get().startDate)
            openDateField("Start date")
            chooseCalendarDay("January 2, 2000")
            click("Use date")
            await("past date warning again") { text("Start is in the past") != null }
            click("Use date")
            await("acknowledged past date") { currentJob.get().startDate == "2000-01-02" }
            click("Done")
            await("finish after acknowledgement") { finishes.get() == 1 }
        }
    }
}
