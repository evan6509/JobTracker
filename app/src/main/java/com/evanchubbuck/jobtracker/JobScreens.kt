package com.evanchubbuck.jobtracker

import android.app.DatePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.Calendar
import java.util.TimeZone
import java.math.BigDecimal

internal val UiInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val UiCanvas: Color @Composable get() = MaterialTheme.colorScheme.background
private val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val amber: Color @Composable get() = Color(0xFFD5A84D)
private val steps = listOf("Clients", "Job site", "Schedule", "Work details", "Inventory", "Outside workers", "Photos", "Review")

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, color = UiInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (subtitle.isNotBlank()) Text(subtitle, color = muted)
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun HomeScreen(jobs: List<Job>, hasDraft: Boolean, modifier: Modifier, onNew: () -> Unit, onResume: () -> Unit,
    onOpen: (String) -> Unit, onHistory: () -> Unit, onScan: () -> Unit, onCheckUpdate: () -> Unit,
    onReorder: (Int, Int) -> Unit) {
    val visible = jobs.filter { it.state != Job.COMPLETED }.sortedBy { it.priority }
    Column(modifier.padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your jobs", color = UiInk, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("${visible.size} in your lineup", color = muted)
            }
            TextButton(onClick = onHistory) { Text("History") }
        }
        Spacer(Modifier.height(18.dp))
        if (hasDraft) {
            OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onResume)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Continue unfinished job", fontWeight = FontWeight.SemiBold, color = UiInk)
                    Text("Your setup is saved", color = muted)
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        if (visible.isEmpty()) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("no jobs planned", color = Color.Gray, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onNew) { Text("New Job") }
            }
            Spacer(Modifier.weight(1f))
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Priority order · hold and drag", color = muted, style = MaterialTheme.typography.labelMedium)
                Button(onClick = onNew) { Text("New Job") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.id }) { job ->
                    val index = visible.indexOfFirst { it.id == job.id }
                    var drag by remember(job.id) { mutableFloatStateOf(0f) }
                    val currentIndex by rememberUpdatedState(index)
                    val currentLast by rememberUpdatedState(visible.lastIndex)
                    val reorder by rememberUpdatedState(onReorder)
                    val border = if (job.state == Job.ACTIVE) green else amber
                    Card(Modifier.fillMaxWidth().border(2.dp, border, RoundedCornerShape(16.dp))
                        .graphicsLayer { translationY = drag }
                        .pointerInput(job.id) {
                            detectDragGesturesAfterLongPress(onDragEnd = { drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                                change.consume()
                                drag += amount.y
                                val threshold = 90.dp.toPx()
                                if (drag > threshold && currentIndex < currentLast) { reorder(currentIndex, currentIndex + 1); drag = 0f }
                                else if (drag < -threshold && currentIndex > 0) { reorder(currentIndex, currentIndex - 1); drag = 0f }
                            }
                        }.clickable { onOpen(job.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(18.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(job.label, color = UiInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                Text("≡", color = muted, fontSize = 24.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(job.state.uppercase(Locale.US), color = border, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            if (job.address.isNotBlank()) Text(job.address, color = muted, maxLines = 2)
                            if (job.startDate.isNotBlank()) Text(scheduleSummary(job), color = muted)
                            if (job.address.isBlank() && job.startDate.isBlank()) Text("Job #${job.id.take(6)}", color = muted)
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan job QR") }
        TextButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
    }
}

@Composable
internal fun HistoryScreen(jobs: List<Job>, modifier: Modifier, onOpen: (String) -> Unit) {
    Column(modifier.padding(20.dp)) {
        PageTitle("History", "Completed jobs stay here for reference.")
        if (jobs.isEmpty()) Text("No completed jobs yet.", color = muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs.sortedByDescending { it.updatedAt }, key = { it.id }) { job ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { onOpen(job.id) }) {
                    Column(Modifier.padding(16.dp)) { Text(job.label, color = UiInk, fontWeight = FontWeight.Bold); Text(job.address, color = muted) }
                }
            }
        }
    }
}

@Composable
internal fun DetailScreen(job: Job, modifier: Modifier, onEdit: (Int) -> Unit, onNavigate: () -> Unit,
    onCalendar: () -> Unit, onCall: (String) -> Unit, onPhoto: (String) -> Unit, onShare: () -> Unit, onPdf: () -> Unit, onCosts: () -> Unit,
    onActivate: () -> Unit, onPlan: () -> Unit, onComplete: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(job.state.uppercase(Locale.US), color = if (job.state == Job.ACTIVE) green else muted, fontWeight = FontWeight.Bold)
        PageTitle(job.label, if (job.state == Job.COMPLETED) "Saved in history" else "Everything you need on site")
        when (job.state) {
            Job.ACTIVE -> OutlinedButton(onClick = onPlan) { Text("Move to planned") }
            Job.PLANNED -> Button(onClick = onActivate) { Text("Activate") }
            Job.COMPLETED -> OutlinedButton(onClick = onPlan) { Text("Restore to planned") }
        }
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Share job · QR") }
        OutlinedButton(onClick = onPdf, modifier = Modifier.fillMaxWidth()) { Text("Share job · PDF") }
        OutlinedButton(onClick = onCosts, modifier = Modifier.fillMaxWidth()) { Text("Costs · private") }
        Spacer(Modifier.height(20.dp))
        DetailSection("Clients", 0, onEdit) {
            job.clients.forEach { person ->
                Text(person.name, fontWeight = FontWeight.SemiBold, color = UiInk)
                if (person.phone.isNotBlank()) TextButton(onClick = { onCall(person.phone) }) { Text("Call ${person.phone}") }
            }
        }
        DetailSection("Job site", 1, onEdit) {
            Text(job.address.ifBlank { "No address yet" }, color = UiInk)
            if (job.address.isNotBlank()) OutlinedButton(onClick = onNavigate) { Text("Navigate") }
        }
        DetailSection("Schedule", 2, onEdit) {
            Text(scheduleSummary(job), color = UiInk)
            if (calendarRange(job) != null) OutlinedButton(onClick = onCalendar) { Text("Add to calendar") }
            Text("Calendar events you already saved are not changed by edits.", color = muted, style = MaterialTheme.typography.bodySmall)
        }
        DetailSection("Work details", 3, onEdit) { Text(job.description.ifBlank { "No details yet" }, color = UiInk) }
        DetailSection("Inventory", 4, onEdit) {
            if (job.inventory.isEmpty()) Text("No items planned", color = muted)
            job.inventory.filter { it.name.isNotBlank() }.forEach { item -> Text("${item.quantity.ifBlank { "1" }} × ${item.name}${if (item.notes.isBlank()) "" else " · ${item.notes}"}", color = UiInk) }
        }
        DetailSection("Outside workers", 5, onEdit) {
            if (job.workers.isEmpty()) Text("None added", color = muted)
            job.workers.forEach { worker ->
                Text(worker.name.ifBlank { "Unnamed worker" }, color = UiInk, fontWeight = FontWeight.SemiBold)
                if (worker.work.isNotBlank()) Text(worker.work, color = muted)
                if (worker.phone.isNotBlank()) TextButton(onClick = { onCall(worker.phone) }) { Text("Call ${worker.phone}") }
            }
        }
        DetailSection("Photos", 6, onEdit) {
            if (job.photos.isEmpty()) Text("No reference photos", color = muted)
            job.photos.forEachIndexed { index, path ->
                Row(Modifier.fillMaxWidth().clickable { onPhoto(path) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoImage(path, Modifier.size(72.dp)); Spacer(Modifier.width(12.dp)); Text("Photo ${index + 1} · view", color = UiInk)
                }
            }
        }
        if (job.state != Job.COMPLETED) OutlinedButton(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("Mark completed") }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun DetailSection(title: String, step: Int, onEdit: (Int) -> Unit, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = UiInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onEdit(step) }) { Text("Edit") }
            }
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
internal fun EditorScreen(job: Job, step: Int, existing: Boolean, error: String, modifier: Modifier,
    onChange: (Job) -> Unit, onStep: (Int) -> Unit, onBack: () -> Unit, onExit: () -> Unit, onPickPhotos: () -> Unit,
    onPhoto: (String) -> Unit, onRemovePhoto: (String) -> Unit, onNavigate: () -> Unit,
    onCalendar: () -> Unit, onFinish: () -> Unit) {
    Column(modifier.padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (existing) "EDIT JOB" else "NEW JOB", color = green, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onExit) { Text(if (existing) "Close editor" else "Save draft & exit") }
        }
        Text("${step + 1} of ${steps.size}  ·  ${steps[step]}", color = muted)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.indices.forEach { index -> Box(Modifier.weight(1f).height(5.dp).background(if (index <= step) green else Color(0xFFDDE5E3), RoundedCornerShape(3.dp))) }
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PageTitle(steps[step], when (step) {
                0 -> "Who is this job for? Add each client separately."
                1 -> "Where will the work happen?"
                2 -> "Choose the job dates. A start time is optional."
                3 -> "Describe the work."
                4 -> "List items the job will require, or skip for now."
                5 -> "Assign work to any outside workers."
                6 -> "Keep useful reference photos with this job."
                else -> "Check the details before saving."
            })
            when (step) {
                0 -> {
                    Field("Short job title (optional)", job.title, { onChange(job.copy(title = it)) })
                    job.clients.forEachIndexed { index, person ->
                        PersonEditor("Client ${index + 1}", person, showWork = false,
                            onChange = { changed -> onChange(job.copy(clients = job.clients.toMutableList().also { it[index] = changed })) },
                            onRemove = if (job.clients.size > 1) ({ onChange(job.copy(clients = job.clients.filterIndexed { i, _ -> i != index })) }) else null)
                    }
                    OutlinedButton(onClick = { onChange(job.copy(clients = job.clients + Person())) }) { Text("Add client") }
                    if (job.clients.none { it.name.isNotBlank() }) Text("At least one client name is required to finish.", color = amber)
                }
                1 -> {
                    Field("Street address", job.address, { onChange(job.copy(address = it)) }, singleLine = false)
                    if (job.address.isNotBlank()) OutlinedButton(onClick = onNavigate) { Text("Navigate") }
                }
                2 -> {
                    DateField("Start date", job.startDate, job.timeZone, { onChange(job.copy(startDate = it, timeZone = TimeZone.getDefault().id)) })
                    DateField("End date (optional)", job.endDate, job.timeZone, { onChange(job.copy(endDate = it, timeZone = TimeZone.getDefault().id)) })
                    Field("Start time · HH:MM (optional)", job.startTime, { onChange(job.copy(startTime = it, timeZone = TimeZone.getDefault().id)) })
                    if (job.startTime.isNotBlank() && parseStart(job) == null) Text("Choose a start date and use 24-hour time, such as 09:30.", color = MaterialTheme.colorScheme.error)
                    if (job.startDate.isNotBlank() && job.endDate.isNotBlank() && (parseDate(job.endDate, job.timeZone) ?: 0) < (parseDate(job.startDate, job.timeZone) ?: 0)) Text("End date must be on or after start date.", color = MaterialTheme.colorScheme.error)
                    Text("Time zone: ${job.timeZone}", color = muted, style = MaterialTheme.typography.bodySmall)
                    if (calendarRange(job) != null) OutlinedButton(onClick = onCalendar) { Text("Add to calendar") }
                }
                3 -> Field("Work description", job.description, { onChange(job.copy(description = it)) }, singleLine = false, minLines = 6)
                4 -> {
                    if (job.inventory.isEmpty()) Text("No inventory items decided yet.", color = muted)
                    job.inventory.forEachIndexed { index, item ->
                        OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Item ${index + 1}", color = UiInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onChange(job.copy(inventory = job.inventory.filterIndexed { i, _ -> i != index })) }) { Text("Remove") }
                                }
                                Field("Item name", item.name, { value -> onChange(job.copy(inventory = job.inventory.toMutableList().also { list -> list[index] = item.copy(name = value) })) })
                                Field("Quantity", item.quantity, { value -> onChange(job.copy(inventory = job.inventory.toMutableList().also { list -> list[index] = item.copy(quantity = value) })) })
                                Field("Notes (optional)", item.notes, { value -> onChange(job.copy(inventory = job.inventory.toMutableList().also { list -> list[index] = item.copy(notes = value) })) })
                            }
                        }
                    }
                    OutlinedButton(onClick = { onChange(job.copy(inventory = job.inventory + InventoryItem())) }) { Text("Add item") }
                    if (job.inventory.isEmpty()) TextButton(onClick = { onStep(step + 1) }) { Text("Skip inventory for now") }
                }
                5 -> {
                    if (job.workers.isEmpty()) Text("No outside workers added.", color = muted)
                    job.workers.forEachIndexed { index, person ->
                        PersonEditor("Worker ${index + 1}", person, showWork = true,
                            onChange = { changed -> onChange(job.copy(workers = job.workers.toMutableList().also { it[index] = changed })) },
                            onRemove = { onChange(job.copy(workers = job.workers.filterIndexed { i, _ -> i != index })) })
                    }
                    OutlinedButton(onClick = { onChange(job.copy(workers = job.workers + Person())) }) { Text("Add worker") }
                }
                6 -> {
                    Button(onClick = onPickPhotos) { Text("Add photos") }
                    Text("Photos stay on this device and are not included in QR sharing.", color = muted, style = MaterialTheme.typography.bodySmall)
                    job.photos.forEachIndexed { index, path ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            PhotoImage(path, Modifier.size(72.dp).clickable { onPhoto(path) })
                            Spacer(Modifier.width(10.dp)); Text("Photo ${index + 1}", modifier = Modifier.weight(1f))
                            TextButton(onClick = { onPhoto(path) }) { Text("View") }
                            TextButton(onClick = { onRemovePhoto(path) }) { Text("Remove") }
                        }
                    }
                }
                7 -> {
                    ReviewLine("Clients", job.clients.filter { it.name.isNotBlank() }.joinToString { it.name }, 0, onStep)
                    ReviewLine("Job site", job.address, 1, onStep)
                    ReviewLine("Schedule", scheduleSummary(job), 2, onStep)
                    ReviewLine("Work details", job.description, 3, onStep)
                    ReviewLine("Inventory", job.inventory.joinToString { "${it.quantity.ifBlank { "1" }} × ${it.name}" }, 4, onStep)
                    ReviewLine("Outside workers", job.workers.joinToString { it.name }, 5, onStep)
                    ReviewLine("Photos", "${job.photos.size} attached", 6, onStep)
                }
            }
            if (error.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(error, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(20.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Button(onClick = if (step == steps.lastIndex) onFinish else ({ onStep(step + 1) }), modifier = Modifier.weight(1f)) {
                Text(if (step == steps.lastIndex) if (existing) "Done" else "Finish setup" else "Next")
            }
        }
    }
}

@Composable
private fun DateField(label: String, value: String, zone: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    OutlinedButton(onClick = {
        val selected = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { timeInMillis = parseDate(value, zone) ?: System.currentTimeMillis() }
        DatePickerDialog(context, if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert,
            { _, year, month, day -> onChange("%04d-%02d-%02d".format(Locale.US, year, month + 1, day)) },
            selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show()
    }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) { Text("$label: ${if (value.isBlank()) "Choose date" else displayDate(value, zone)}") }
    if (value.isNotBlank()) TextButton(onClick = { onChange("") }) { Text("Clear $label") }
}

@Composable
private fun Field(label: String, value: String, onValueChange: (String) -> Unit, singleLine: Boolean = true,
    minLines: Int = 1, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        singleLine = singleLine, minLines = minLines, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}

@Composable
private fun PersonEditor(title: String, person: Person, showWork: Boolean, onChange: (Person) -> Unit, onRemove: (() -> Unit)?) {
    OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = UiInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
            }
            Field("Name", person.name, { onChange(person.copy(name = it)) })
            if (person.name.isBlank() && (!showWork || person.phone.isNotBlank() || person.work.isNotBlank())) Text("Name required for this entry.", color = MaterialTheme.colorScheme.error)
            Field("Phone (optional)", person.phone, { onChange(person.copy(phone = it)) }, keyboard = KeyboardType.Phone)
            if (person.phone.isNotBlank() && !validPhone(person.phone)) Text("Enter a callable phone number (3–15 digits).", color = MaterialTheme.colorScheme.error)
            if (showWork) {
                Field("Assigned work", person.work, { onChange(person.copy(work = it)) }, singleLine = false)
                if (person.name.isNotBlank() && person.work.isBlank()) Text("Describe this worker’s assignment.", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ReviewLine(title: String, value: String, step: Int, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = UiInk, fontWeight = FontWeight.Bold); Text(value.ifBlank { "Not set" }, color = muted, maxLines = 3) }
        TextButton(onClick = { onStep(step) }) { Text("Edit") }
    }
    HorizontalDivider()
}

@Composable
internal fun ShareScreen(job: Job, modifier: Modifier) {
    val payload = remember(job) { sharePayload(job) }
    val bytes = payload.toByteArray(Charsets.UTF_8).size
    val bitmap = remember(payload) { if (bytes <= 1800) qrBitmap(payload) else null }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PageTitle("Share ${job.label}", "Show this code to another JobTracker user.")
        if (bitmap == null) Text("This job is too large for an offline QR code. Shorten the description or worker details, then try again.", color = MaterialTheme.colorScheme.error)
        else Image(bitmap.asImageBitmap(), contentDescription = "Job QR code", modifier = Modifier.fillMaxWidth().height(300.dp))
        Spacer(Modifier.height(12.dp))
        Text("Offline · no internet or account needed", color = UiInk, fontWeight = FontWeight.Bold)
        Text("Includes client contacts, address, schedule, description, inventory, and worker assignments. Photos and private costs are not included. Anyone who scans this code can read those details. The code does not expire or revoke; it is a snapshot and later edits will not update imported copies.", color = muted)
    }
}

@Composable
internal fun CostsScreen(job: Job, items: List<CostItem>, modifier: Modifier, onChange: (List<CostItem>) -> Unit, onExport: () -> Unit) {
    val invalid = items.any { it.amount.isNotBlank() && (it.amount.toBigDecimalOrNull() ?: BigDecimal(-1)) < BigDecimal.ZERO }
    val total = items.mapNotNull { it.amount.toBigDecimalOrNull() }.fold(BigDecimal.ZERO, BigDecimal::add)
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        PageTitle("Costs · ${job.label}", "Private to this job. Edit costs here and export them separately.")
        Text("Costs are never included in job QR codes or job PDFs.", color = muted)
        Spacer(Modifier.height(12.dp))
        items.forEachIndexed { index, item ->
            OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Cost ${index + 1}", color = UiInk, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onChange(items.filterIndexed { i, _ -> i != index }) }) { Text("Remove") }
                    }
                    Field("Description", item.description, { onChange(items.toMutableList().also { list -> list[index] = item.copy(description = it) }) })
                    Field("Amount", item.amount, { onChange(items.toMutableList().also { list -> list[index] = item.copy(amount = it) }) }, keyboard = KeyboardType.Decimal)
                }
            }
        }
        OutlinedButton(onClick = { onChange(items + CostItem()) }) { Text("Add cost") }
        Spacer(Modifier.height(18.dp))
        Text("Total: ${"%.2f".format(Locale.US, total)}", color = UiInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (invalid) Text("Amounts must be zero or greater before export.", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onExport, enabled = !invalid, modifier = Modifier.fillMaxWidth()) { Text("Export costs PDF") }
    }
}

@Composable
internal fun PreviewScreen(job: Job, duplicate: Boolean, modifier: Modifier, onCancel: () -> Unit, onImport: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        PageTitle("Import preview", "Review the shared job before saving it.")
        if (duplicate) Text("You already imported this exact QR snapshot. Importing again creates another separate copy.", color = amber, fontWeight = FontWeight.Bold)
        Text(job.label, color = UiInk, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ReviewText("Clients", job.clients.joinToString { "${it.name}${if (it.phone.isBlank()) "" else " · ${it.phone}"}" })
        ReviewText("Address", job.address)
        ReviewText("Schedule", scheduleSummary(job))
        ReviewText("Work details", job.description)
        ReviewText("Inventory", job.inventory.filter { it.name.isNotBlank() }.joinToString { "${it.quantity.ifBlank { "1" }} × ${it.name}${if (it.notes.isBlank()) "" else " · ${it.notes}"}" })
        ReviewText("Outside workers", job.workers.joinToString { "${it.name}: ${it.work}${if (it.phone.isBlank()) "" else " · ${it.phone}"}" })
        Text("Photos and private costs are not included. This will be a planned job and will not change your active job.", color = muted)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text(if (duplicate) "Import another copy" else "Import as planned") }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun ReviewText(label: String, value: String) {
    Spacer(Modifier.height(12.dp)); Text(label, color = UiInk, fontWeight = FontWeight.Bold)
    Text(value.ifBlank { "Not set" }, color = muted)
    HorizontalDivider(Modifier.padding(top = 12.dp))
}

@Composable
internal fun PhotoImage(path: String, modifier: Modifier) {
    val bitmap = remember(path) { sampledBitmap(path) }
    if (bitmap == null) Box(modifier.background(Color.LightGray), contentAlignment = Alignment.Center) { Text("Missing photo", color = muted, textAlign = TextAlign.Center) }
    else Image(bitmap.asImageBitmap(), contentDescription = "Reference photo", modifier = modifier)
}
