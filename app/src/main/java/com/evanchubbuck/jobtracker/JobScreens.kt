package com.evanchubbuck.jobtracker

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal val UiInk = Color(0xFF18304A)
internal val UiCanvas = Color(0xFFF6F8F7)
private val muted = Color(0xFF687685)
private val green = Color(0xFF177A5A)
private val amber = Color(0xFFAD832C)
private val steps = listOf("Clients", "Job site", "Schedule", "Work details", "Outside workers", "Photos", "Review")

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, color = UiInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (subtitle.isNotBlank()) Text(subtitle, color = muted)
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun HomeScreen(jobs: List<Job>, hasDraft: Boolean, modifier: Modifier, onNew: () -> Unit, onResume: () -> Unit,
    onOpen: (String) -> Unit, onHistory: () -> Unit, onScan: () -> Unit, onReorder: (Int, Int) -> Unit) {
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
                        colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(18.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(job.label, color = UiInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                Text("≡", color = muted, fontSize = 24.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(job.state.uppercase(Locale.US), color = border, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            if (job.address.isNotBlank()) Text(job.address, color = muted, maxLines = 2)
                            if (job.startDate.isNotBlank()) Text("${job.startDate}  ${job.startTime}", color = muted)
                            if (job.address.isBlank() && job.startDate.isBlank()) Text("Job #${job.id.take(6)}", color = muted)
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan job QR") }
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
    onCalendar: () -> Unit, onCall: (String) -> Unit, onPhoto: (String) -> Unit, onShare: () -> Unit,
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
        Spacer(Modifier.height(20.dp))
        DetailSection("Clients", 0, onEdit) {
            job.clients.forEach { person ->
                Text(person.name, fontWeight = FontWeight.SemiBold, color = UiInk)
                if (person.phone.isNotBlank()) TextButton(onClick = { onCall(person.phone) }) { Text("Call ${person.phone}") }
            }
        }
        DetailSection("Job site", 1, onEdit) {
            Text(job.address.ifBlank { "No address yet" }, color = UiInk)
            if (job.address.isNotBlank()) TextButton(onClick = onNavigate) { Text("Navigate") }
        }
        DetailSection("Schedule", 2, onEdit) {
            Text(if (job.startDate.isBlank()) "Start not set" else "${job.startDate} at ${job.startTime}", color = UiInk)
            Text(if (job.durationMinutes.isBlank()) "Estimate not set" else "Estimated ${job.durationMinutes} minutes", color = muted)
            if (parseStart(job) != null && (job.durationMinutes.toLongOrNull() ?: 0) > 0) TextButton(onClick = onCalendar) { Text("Add to calendar") }
            Text("Calendar events you already saved are not changed by edits.", color = muted, style = MaterialTheme.typography.bodySmall)
        }
        DetailSection("Work details", 3, onEdit) { Text(job.description.ifBlank { "No details yet" }, color = UiInk) }
        DetailSection("Outside workers", 4, onEdit) {
            if (job.workers.isEmpty()) Text("None added", color = muted)
            job.workers.forEach { worker ->
                Text(worker.name.ifBlank { "Unnamed worker" }, color = UiInk, fontWeight = FontWeight.SemiBold)
                if (worker.work.isNotBlank()) Text(worker.work, color = muted)
                if (worker.phone.isNotBlank()) TextButton(onClick = { onCall(worker.phone) }) { Text("Call ${worker.phone}") }
            }
        }
        DetailSection("Photos", 5, onEdit) {
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
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
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
                2 -> "Set a time and estimate when you know them."
                3 -> "Describe the work and what is needed."
                4 -> "Assign work to any outside workers."
                5 -> "Keep useful reference photos with this job."
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
                    if (job.address.isNotBlank()) TextButton(onClick = onNavigate) { Text("Navigate") }
                }
                2 -> {
                    Field("Start date · YYYY-MM-DD", job.startDate, { onChange(job.copy(startDate = it, timeZone = java.util.TimeZone.getDefault().id)) })
                    Field("Start time · HH:MM (24 hour)", job.startTime, { onChange(job.copy(startTime = it, timeZone = java.util.TimeZone.getDefault().id)) })
                    if (job.startDate.isNotBlank() != job.startTime.isNotBlank()) Text("Enter both date and time, or leave both blank.", color = MaterialTheme.colorScheme.error)
                    else if (job.startDate.isNotBlank() && parseStart(job) == null) Text("Check the date and time format.", color = MaterialTheme.colorScheme.error)
                    Field("Estimated duration · minutes", job.durationMinutes, { onChange(job.copy(durationMinutes = it)) }, keyboard = KeyboardType.Number)
                    if (job.durationMinutes.isNotBlank() && (job.durationMinutes.toLongOrNull() ?: 0) <= 0) Text("Enter a number above zero.", color = MaterialTheme.colorScheme.error)
                    Text("Time zone: ${job.timeZone}", color = muted, style = MaterialTheme.typography.bodySmall)
                    if (parseStart(job) != null && (job.durationMinutes.toLongOrNull() ?: 0) > 0) TextButton(onClick = onCalendar) { Text("Add to calendar") }
                }
                3 -> Field("Work description and needed items", job.description, { onChange(job.copy(description = it)) }, singleLine = false, minLines = 6)
                4 -> {
                    if (job.workers.isEmpty()) Text("No outside workers added.", color = muted)
                    job.workers.forEachIndexed { index, person ->
                        PersonEditor("Worker ${index + 1}", person, showWork = true,
                            onChange = { changed -> onChange(job.copy(workers = job.workers.toMutableList().also { it[index] = changed })) },
                            onRemove = { onChange(job.copy(workers = job.workers.filterIndexed { i, _ -> i != index })) })
                    }
                    OutlinedButton(onClick = { onChange(job.copy(workers = job.workers + Person())) }) { Text("Add worker") }
                }
                5 -> {
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
                6 -> {
                    ReviewLine("Clients", job.clients.filter { it.name.isNotBlank() }.joinToString { it.name }, 0, onStep)
                    ReviewLine("Job site", job.address, 1, onStep)
                    ReviewLine("Schedule", listOf(job.startDate, job.startTime, job.durationMinutes.takeIf { it.isBlank() } ?: "${job.durationMinutes} min").filter { it.isNotBlank() }.joinToString(" · "), 2, onStep)
                    ReviewLine("Work details", job.description, 3, onStep)
                    ReviewLine("Outside workers", job.workers.joinToString { it.name }, 4, onStep)
                    ReviewLine("Photos", "${job.photos.size} attached", 5, onStep)
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
        Text("Includes client contacts, address, schedule, description, and worker assignments. Photos are not included. Anyone who scans this code can read those details. The code does not expire or revoke; it is a snapshot and later edits will not update imported copies.", color = muted)
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
        ReviewText("Schedule", "${job.startDate} ${job.startTime} · ${job.durationMinutes} min")
        ReviewText("Work details", job.description)
        ReviewText("Outside workers", job.workers.joinToString { "${it.name}: ${it.work}${if (it.phone.isBlank()) "" else " · ${it.phone}"}" })
        Text("Photos are not included. This will be a planned job and will not change your active job.", color = muted)
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
