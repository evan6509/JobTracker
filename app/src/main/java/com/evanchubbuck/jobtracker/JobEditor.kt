package com.evanchubbuck.jobtracker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

private val editorSections = listOf("Clients", "Job site", "Schedule", "Work details", "Materials", "Outside workers", "Photos", "Review")
private enum class PastStartPrompt { SET_DATE, SECTION, FINISH }
private val editorMuted @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(job: Job, step: Int, existing: Boolean, error: String, modifier: Modifier,
    onChange: (Job) -> Unit, onStep: (Int) -> Unit, onPickPhotos: () -> Unit,
    onTakePhoto: () -> Unit, onPhoto: (String) -> Unit, onRemovePhoto: (String) -> Unit,
    onNavigate: () -> Unit, onFinish: () -> Unit) {
    val focus = LocalFocusManager.current
    var showMissingClientName by remember(job.id) { mutableStateOf(false) }
    var pastStartPrompt by remember(job.id) { mutableStateOf<PastStartPrompt?>(null) }
    var pendingPastStartDate by remember(job.id) { mutableStateOf<String?>(null) }
    var pendingSection by remember(job.id) { mutableStateOf<Int?>(null) }
    var acknowledgedPastStart by remember(job.id) { mutableStateOf<String?>(null) }
    var clearedEndDate by remember(job.id) { mutableStateOf(false) }
    val pager = rememberPagerState(initialPage = step, pageCount = { editorSections.size })
    val visiblePage by remember(pager) { derivedStateOf { pager.currentPage } }

    fun startKey(value: Job) = "${value.startDate}|${value.startTime}|${value.timeZone}"
    fun withStartDate(value: String): Job {
        val zone = TimeZone.getDefault().id
        val start = parseDate(value, zone)
        val end = parseDate(job.endDate, zone)
        val removeEnd = job.endDate.isNotBlank() && (value.isBlank() || (start != null && end != null && end < start))
        return job.copy(startDate = value, startTime = if (value.isBlank()) "" else job.startTime,
            endDate = if (removeEnd) "" else job.endDate, timeZone = zone)
    }
    fun applyStartDate(value: String) {
        val changed = withStartDate(value)
        clearedEndDate = job.endDate.isNotBlank() && changed.endDate.isBlank()
        onChange(changed)
    }
    fun setStartDate(value: String) {
        val changed = withStartDate(value)
        if (value.isNotBlank() && startIsPast(changed)) {
            pendingPastStartDate = value
            pastStartPrompt = PastStartPrompt.SET_DATE
        } else {
            acknowledgedPastStart = null
            applyStartDate(value)
        }
    }
    fun goToSection(target: Int): Boolean {
        focus.clearFocus()
        if (target == step) return true
        if (step == 0 && target > step && job.clients.none { it.name.isNotBlank() }) {
            showMissingClientName = true
            return false
        }
        if (step == 2 && target > step) {
            if (scheduleValidationError(job) != null) return false
            if (startIsPast(job) && acknowledgedPastStart != startKey(job)) {
                pendingSection = target
                pastStartPrompt = PastStartPrompt.SECTION
                return false
            }
        }
        onStep(target)
        return true
    }
    fun finish() {
        focus.clearFocus()
        if (validationError(job) != null) {
            if (job.clients.none { it.name.isNotBlank() }) showMissingClientName = true
            onFinish()
        }
        else if (startIsPast(job) && acknowledgedPastStart != startKey(job)) pastStartPrompt = PastStartPrompt.FINISH
        else onFinish()
    }

    val currentStep by rememberUpdatedState(step)
    // A lambda keeps updated captures; local function references can compare equal
    // across recompositions even when the job and selected section have changed.
    val requestSection by rememberUpdatedState({ target: Int -> goToSection(target) })
    LaunchedEffect(job.id, step) {
        if (pager.settledPage != step) pager.animateScrollToPage(step)
    }
    LaunchedEffect(job.id, pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            // A swipe uses the same gate as a tab. Keep the saved draft section
            // unchanged until validation succeeds, and return on cancel/error.
            if (page != currentStep && !requestSection(page)) pager.scrollToPage(currentStep)
        }
    }

    Column(modifier) {
        PrimaryScrollableTabRow(selectedTabIndex = visiblePage, edgePadding = 8.dp,
            containerColor = UiCanvas, contentColor = MaterialTheme.colorScheme.primary,
            indicator = { EditorPageIndicator(pager, visiblePage) },
            divider = { HorizontalDivider(color = editorMuted.copy(alpha = 0.2f)) }) {
            editorSections.forEachIndexed { index, title ->
                Tab(selected = visiblePage == index, onClick = { goToSection(index) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = editorMuted,
                    text = {
                        val amount = (1f - abs(pager.currentPage + pager.currentPageOffsetFraction - index)).coerceIn(0f, 1f)
                        Text(title, color = lerp(editorMuted, MaterialTheme.colorScheme.primary, amount),
                            maxLines = 1, fontWeight = FontWeight.SemiBold)
                    })
            }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth()
            .testTag("editor_pages").semantics { testTagsAsResourceId = true },
            userScrollEnabled = pastStartPrompt == null) { page ->
            Column(Modifier.fillMaxSize().testTag("editor_section_$page")
                .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(when (page) {
                    0 -> "Add the people this job is for. Only a client name is required."
                    1 -> "Add the address where the work will happen."
                    2 -> "Set the dates and an optional start time."
                    3 -> "Describe the work, the plan, and anything to keep in mind."
                    4 -> "Keep materials and other job purchases in one place."
                    5 -> "Add anyone helping with the job and their assigned work."
                    6 -> "Keep site, material, and progress pictures together."
                    else -> "Check the details. Tap a section to make changes."
                }, color = editorMuted, style = MaterialTheme.typography.bodyMedium)
                when (page) {
                    0 -> {
                        EditorCard {
                            EditorField("Job title (optional)", job.title, { onChange(job.copy(title = it)) },
                                helper = "Leave blank to use the first client's name.")
                        }
                        job.clients.forEachIndexed { index, person ->
                            PersonEditor(if (job.clients.size == 1) "Client details" else "Client ${index + 1}", person,
                                showWork = false,
                                showEmptyNameError = showMissingClientName && index == 0 && job.clients.none { it.name.isNotBlank() },
                                onChange = { changed -> onChange(job.copy(clients = job.clients.toMutableList().also { it[index] = changed })) },
                                onRemove = if (job.clients.size > 1) ({ onChange(job.copy(clients = job.clients.filterIndexed { i, _ -> i != index })) }) else null)
                        }
                        AddEditorButton("Add another client") { onChange(job.copy(clients = job.clients + Person())) }
                    }
                    1 -> EditorCard {
                        key(job.id) { JobSiteEditor(job.address, { onChange(job.copy(address = it)) }, onNavigate,
                            showNavigate = !existing) }
                    }
                    2 -> {
                        DateField("Start date", job.startDate, job.timeZone, ::setStartDate)
                        DateField("End date", job.endDate, job.timeZone, { value ->
                            clearedEndDate = false
                            if (value.isBlank() || (job.startDate.isNotBlank() &&
                                    (parseDate(value, job.timeZone) ?: Long.MIN_VALUE) >= (parseDate(job.startDate, job.timeZone) ?: Long.MAX_VALUE)))
                                onChange(job.copy(endDate = value, timeZone = TimeZone.getDefault().id))
                        }, optional = true, enabled = job.startDate.isNotBlank(),
                            minDate = job.startDate.takeIf { it.isNotBlank() })
                        if (job.startDate.isBlank()) Text("Choose a start date before adding an end date.",
                            color = editorMuted, style = MaterialTheme.typography.bodySmall)
                        if (clearedEndDate) Text("End date cleared because it came before the new start date.",
                            color = editorMuted, style = MaterialTheme.typography.bodySmall)
                        EditorCard {
                            StartTimeField(job.startTime) {
                                onChange(job.copy(startTime = it, timeZone = TimeZone.getDefault().id))
                            }
                            Text("Time zone: ${job.timeZone}", color = editorMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        scheduleValidationError(job)?.let { EditorNotice(it, isError = true) }
                        if (startIsPast(job) && acknowledgedPastStart != startKey(job)) EditorNotice(
                            "This start is in the past. You'll be asked to confirm it before continuing.")
                    }
                    3 -> EditorCard {
                        EditorField("Work description", job.description, { onChange(job.copy(description = it)) },
                            singleLine = false, minLines = 7, placeholder = "What needs to be done?")
                    }
                    4 -> {
                        if (job.inventory.isEmpty()) EditorEmptyState("No items yet",
                            "Add materials or other job purchases. Prices are optional.")
                        Text("Prices are optional and only shared when you choose to include them.",
                            color = editorMuted, style = MaterialTheme.typography.bodySmall)
                        job.inventory.forEachIndexed { index, item ->
                            EditorCard {
                                EditorCardHeading("Item ${index + 1}", "Remove item ${index + 1}",
                                    { onChange(job.copy(inventory = job.inventory.filterIndexed { i, _ -> i != index })) })
                                EditorField("Item name", item.name, { value -> onChange(job.copy(inventory = job.inventory.toMutableList().also { it[index] = item.copy(name = value) })) })
                                EditorField("Quantity", item.quantity, { value -> onChange(job.copy(inventory = job.inventory.toMutableList().also { it[index] = item.copy(quantity = value) })) },
                                    placeholder = "1")
                                EditorField("Price (optional)", item.price, { value -> onChange(job.copy(inventory = job.inventory.toMutableList().also { it[index] = item.copy(price = value) })) },
                                    keyboard = KeyboardType.Decimal, prefix = { Text("$") }, isError = !validMaterialPrice(item.price),
                                    helper = if (!validMaterialPrice(item.price)) "Enter a price of zero or more, with up to two decimal places." else "Price for this entry, including its quantity")
                                EditorField("Notes (optional)", item.notes, { value -> onChange(job.copy(inventory = job.inventory.toMutableList().also { it[index] = item.copy(notes = value) })) }, singleLine = false)
                            }
                        }
                        AddEditorButton("Add item") { onChange(job.copy(inventory = job.inventory + InventoryItem())) }
                        if (job.inventory.any { it.price.isNotBlank() }) Text("Total of entered prices: ${materialTotal(job.inventory).asMoney()}",
                            color = UiInk, style = MaterialTheme.typography.titleMedium)
                    }
                    5 -> {
                        if (job.workers.isEmpty()) EditorEmptyState("No outside workers yet",
                            "Add a contact and their part of the work when you need to.")
                        job.workers.forEachIndexed { index, person ->
                            PersonEditor("Worker ${index + 1}", person, showWork = true,
                                onChange = { changed -> onChange(job.copy(workers = job.workers.toMutableList().also { it[index] = changed })) },
                                onRemove = { onChange(job.copy(workers = job.workers.filterIndexed { i, _ -> i != index })) })
                        }
                        AddEditorButton("Add worker") { onChange(job.copy(workers = job.workers + Person())) }
                    }
                    6 -> JobPhotoEditor(job, onPickPhotos, onTakePhoto, onPhoto, onRemovePhoto)
                    7 -> {
                        EditorCard {
                            Text(job.label, color = UiInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(if (existing) "Review your job details" else "Ready to create your job",
                                color = editorMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                        editorSections.take(7).forEachIndexed { index, title ->
                            ReviewLine(title, editorSectionSummary(job, index), index) { goToSection(it) }
                        }
                    }
                }
                if (error.isNotBlank()) EditorNotice(error, isError = true)
                Spacer(Modifier.height(4.dp))
            }
        }
        HorizontalDivider(color = editorMuted.copy(alpha = 0.2f))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { finish() },
            enabled = step != 2 || scheduleValidationError(job) == null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (existing) "Done" else "Create job", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
    }

    if (pastStartPrompt != null) {
        val proposed = pendingPastStartDate?.let { withStartDate(it) } ?: job
        val selectedStart = displayDate(proposed.startDate, proposed.timeZone) +
            if (proposed.startTime.isBlank()) "" else " at ${displayTime(proposed.startTime, LocalJobTimeFormat.current)}"
        AlertDialog(onDismissRequest = { pastStartPrompt = null; pendingPastStartDate = null; pendingSection = null },
            title = { Text("Start is in the past") },
            text = { Text("$selectedStart is in the past. Use this start anyway?") },
            confirmButton = {
                TextButton(onClick = {
                    when (pastStartPrompt) {
                        PastStartPrompt.SET_DATE -> {
                            val chosen = pendingPastStartDate ?: return@TextButton
                            acknowledgedPastStart = startKey(withStartDate(chosen))
                            applyStartDate(chosen)
                        }
                        PastStartPrompt.SECTION -> { acknowledgedPastStart = startKey(job); onStep(pendingSection ?: step + 1) }
                        PastStartPrompt.FINISH -> { acknowledgedPastStart = startKey(job); onFinish() }
                        null -> Unit
                    }
                    pastStartPrompt = null; pendingPastStartDate = null; pendingSection = null
                }) { Text(if (pastStartPrompt == PastStartPrompt.SET_DATE) "Use date" else "Continue anyway") }
            }, dismissButton = { TextButton(onClick = {
                pastStartPrompt = null; pendingPastStartDate = null; pendingSection = null
            }) { Text("Go back") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabIndicatorScope.EditorPageIndicator(pager: PagerState, selectedTab: Int) {
    TabRowDefaults.PrimaryIndicator(width = Dp.Unspecified,
        modifier = Modifier.tabIndicatorLayout { measurable, constraints, positions ->
            if (positions.isEmpty()) return@tabIndicatorLayout layout(0, 0) {}
            // Follow the finger directly instead of starting a second animation
            // after the pager settles. This also follows canceled swipes back.
            val progress = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, positions.lastIndex.toFloat())
            val from = progress.toInt()
            val to = (from + 1).coerceAtMost(positions.lastIndex)
            val fraction = progress - from
            val start = positions[from]
            val end = positions[to]
            val width = (start.contentWidth.toPx() + (end.contentWidth.toPx() - start.contentWidth.toPx()) * fraction).roundToInt()
            val startLeft = (start.left + (start.width - start.contentWidth) / 2).toPx()
            val endLeft = (end.left + (end.width - end.contentWidth) / 2).toPx()
            val left = (startLeft + (endLeft - startLeft) * fraction).roundToInt()
            val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
            // The row centers the indicator's layout within the selected tab.
            // Subtract that inset so the interpolated position stays absolute.
            val rowInset = ((positions[selectedTab].width.roundToPx() - constraints.maxWidth) / 2).coerceAtLeast(0)
            layout(constraints.maxWidth, placeable.height) {
                placeable.placeRelative(left - rowInset, 0)
            }
        })
}

@Composable
private fun editorSectionSummary(job: Job, step: Int): String = when (step) {
    0 -> job.clients.filter { it.name.isNotBlank() }.joinToString { it.name }.ifBlank { "Add at least one client" }
    1 -> job.address.ifBlank { "Address not set" }
    2 -> scheduleSummary(job, LocalJobTimeFormat.current)
    3 -> job.description.ifBlank { "Description not added" }
    4 -> job.inventory.joinToString { materialSummary(it) }.ifBlank { "No items added" }
    5 -> job.workers.map { it.name.ifBlank { "Unnamed worker" } }.joinToString().ifBlank { "No workers added" }
    6 -> "${job.photos.size} ${if (job.photos.size == 1) "photo" else "photos"} attached"
    else -> "Check the details before creating your job"
}

@Composable
private fun EditorCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun EditorCardHeading(title: String, removeLabel: String? = null, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = removeLabel,
                tint = editorMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EditorField(label: String, value: String, onChange: (String) -> Unit,
    singleLine: Boolean = true, minLines: Int = 1, keyboard: KeyboardType = KeyboardType.Text,
    helper: String? = null, placeholder: String? = null, trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null, isError: Boolean = false) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onChange, label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } }, supportingText = helper?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(), singleLine = singleLine, minLines = minLines, trailingIcon = trailingIcon,
        prefix = prefix, isError = isError,
        shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = keyboard,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        visualTransformation = if (keyboard == KeyboardType.Phone) PhoneVisualTransformation else VisualTransformation.None)
}

@Composable
private fun StartTimeField(value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(role = Role.Button) {
                focus.clearFocus()
                showPicker = true
            }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(R.drawable.ic_schedule), contentDescription = "Choose start time",
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Start time (optional)", color = editorMuted, style = MaterialTheme.typography.bodySmall)
                Text(if (value.isBlank()) "Choose time" else displayTime(value, LocalJobTimeFormat.current),
                    color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (value.isBlank()) Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null,
                tint = editorMuted, modifier = Modifier.size(20.dp))
        }
        if (value.isNotBlank()) IconButton(onClick = { onChange("") }) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear start time",
                tint = editorMuted, modifier = Modifier.size(20.dp))
        }
    }
    if (showPicker) JobTimePickerDialog(value, onDismiss = { showPicker = false }, onSelect = {
        showPicker = false
        onChange(it)
    })
}

@Composable
private fun AddEditorButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp)) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun EditorEmptyState(title: String, detail: String) {
    EditorCard {
        Text(title, color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(detail, color = editorMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EditorNotice(message: String, isError: Boolean = false) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp),
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun PersonEditor(title: String, person: Person, showWork: Boolean, showEmptyNameError: Boolean = false,
    onChange: (Person) -> Unit, onRemove: (() -> Unit)?) {
    EditorCard {
        EditorCardHeading(title, "Remove $title", onRemove)
        EditorField("Name", person.name, { onChange(person.copy(name = it)) })
        if (person.name.isBlank() && (showEmptyNameError || person.phone.isNotBlank() || (showWork && person.work.isNotBlank())))
            Text("Name required for this entry.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        EditorField("Phone (optional)", person.phone, { onChange(person.copy(phone = phoneInput(it))) }, keyboard = KeyboardType.Phone)
        if (person.phone.isNotBlank() && !validPhone(person.phone))
            Text("Enter a callable phone number (3–15 digits).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (showWork) {
            EditorField("Assigned work", person.work, { onChange(person.copy(work = it)) }, singleLine = false, minLines = 2)
            if (person.name.isNotBlank() && person.work.isBlank())
                Text("Describe this worker’s assignment.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DateField(label: String, value: String, zone: String, onChange: (String) -> Unit,
    optional: Boolean = false, enabled: Boolean = true, minDate: String? = null) {
    val focus = LocalFocusManager.current
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).testTag("date_field_${label.lowercase(Locale.ROOT).replace(' ', '_')}")
                .clickable(enabled = enabled, role = Role.Button) {
                focus.clearFocus()
                showPicker = true
            }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else editorMuted,
                    modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label + if (optional) " (optional)" else "", color = editorMuted,
                        style = MaterialTheme.typography.bodySmall)
                    Text(if (value.isBlank()) if (enabled) "Choose date" else "Set start date first" else displayDate(value, zone),
                        color = if (enabled) UiInk else editorMuted, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                if (value.isBlank()) Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null,
                    tint = editorMuted, modifier = Modifier.size(20.dp))
            }
            if (value.isNotBlank()) IconButton(onClick = { onChange("") }, modifier = Modifier.padding(end = 4.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear $label",
                    tint = editorMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (showPicker && enabled) JobDatePickerDialog(label, value, minDate,
        onDismiss = { showPicker = false }, onSelect = {
            showPicker = false
            onChange(it)
        })
}

@Composable
private fun JobPhotoEditor(job: Job, onPickPhotos: () -> Unit, onTakePhoto: () -> Unit,
    onPhoto: (String) -> Unit, onRemovePhoto: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = onTakePhoto, modifier = Modifier.weight(1f).heightIn(min = 92.dp),
            shape = RoundedCornerShape(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(26.dp))
                Text("Take photo")
            }
        }
        OutlinedButton(onClick = onPickPhotos, modifier = Modifier.weight(1f).heightIn(min = 92.dp),
            shape = RoundedCornerShape(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(R.drawable.ic_photo), contentDescription = null, modifier = Modifier.size(26.dp))
                Text("Choose photos")
            }
        }
    }
    Text("${job.photos.size} ${if (job.photos.size == 1) "photo" else "photos"} attached", color = UiInk,
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (job.photos.isEmpty()) EditorEmptyState("No photos yet", "Take a picture here or choose photos from your phone.")
    job.photos.forEachIndexed { index, path ->
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PhotoImage(path, Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).clickable { onPhoto(path) })
                Column(Modifier.weight(1f).clickable(role = Role.Button) { onPhoto(path) }.padding(horizontal = 12.dp)) {
                    Text("Photo ${index + 1}", color = UiInk, fontWeight = FontWeight.SemiBold)
                    Text("Tap to view", color = editorMuted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { onRemovePhoto(path) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove photo ${index + 1}",
                        tint = editorMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    Text("Photos are saved with this job. QR sharing doesn't include them.", color = editorMuted,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ReviewLine(title: String, value: String, step: Int, onStep: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onStep(step) }, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = UiInk, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(value, color = editorMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
            Icon(painterResource(R.drawable.ic_arrow_forward), contentDescription = "Edit $title",
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}
