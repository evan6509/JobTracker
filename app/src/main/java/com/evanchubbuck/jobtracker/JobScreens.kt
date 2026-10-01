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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal val UiInk: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val UiCanvas: Color @Composable get() = MaterialTheme.colorScheme.background
private val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val amber: Color @Composable get() = Color(0xFFD5A84D)
private fun Job.displayState(): String = if (state == Job.PLANNED) "PLANNING" else state.uppercase(Locale.US)

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, color = UiInk, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (subtitle.isNotBlank()) Text(subtitle, color = muted)
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun HomeScreen(jobs: List<Job>, draftCount: Int, modifier: Modifier, onNew: () -> Unit, onDrafts: () -> Unit,
    onOpen: (String) -> Unit, onNavigate: (String) -> Unit, onScan: () -> Unit, onSync: () -> Unit,
    onReorder: (Int, Int) -> Unit, onDeleteJob: (String) -> Unit,
    onDeleteJobImmediately: (String) -> Unit, instantDeleteOnLongSwipe: Boolean) {
    val visible = jobs.filter { it.state != Job.COMPLETED }.sortedBy { it.priority }
    Column(modifier.padding(20.dp)) {
        Text("Your jobs", color = UiInk, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text("New job") }
            OutlinedButton(onClick = onDrafts, modifier = Modifier.weight(1f)) { Text("Drafts ($draftCount)") }
        }
        Spacer(Modifier.height(20.dp))
        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No jobs in progress", color = UiInk, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Create a job or continue a draft.", color = muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Text("${visible.size} ${if (visible.size == 1) "job" else "jobs"} in progress",
                color = UiInk, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(if (visible.size > 1) "Hold to reorder · Swipe right to top · left to delete"
                else "Swipe left on a job to delete", color = muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.id }) { job ->
                    val index = visible.indexOfFirst { it.id == job.id }
                    var drag by remember(job.id) { mutableFloatStateOf(0f) }
                    val currentIndex by rememberUpdatedState(index)
                    val currentLast by rememberUpdatedState(visible.lastIndex)
                    val reorder by rememberUpdatedState(onReorder)
                    val border = if (job.state == Job.ACTIVE) green
                        else if (UiCanvas.luminance() > 0.5f) Color(0xFF805800) else amber
                    val shape = RoundedCornerShape(16.dp)
                    SwipeDeleteContainer("Delete job", instantDeleteOnLongSwipe,
                        onDelete = { onDeleteJob(job.id) },
                        onDeleteImmediately = { onDeleteJobImmediately(job.id) },
                        onMoveToTop = if (index > 0) ({ reorder(currentIndex, 0) }) else null) {
                        Card(Modifier.fillMaxWidth().border(1.dp, border.copy(alpha = 0.7f), shape)
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
                            shape = shape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(18.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(50), color = border.copy(alpha = 0.14f)) {
                                        Text(job.displayState(), color = border, fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null,
                                        tint = muted, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(job.label, color = UiInk, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleLarge, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                if (job.address.isNotBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(R.drawable.ic_place), contentDescription = null,
                                            tint = muted, modifier = Modifier.size(18.dp))
                                        Text(job.address, color = muted, style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (job.startDate.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(R.drawable.ic_calendar), contentDescription = null,
                                            tint = muted, modifier = Modifier.size(18.dp))
                                        Text(scheduleSummary(job, LocalJobTimeFormat.current), color = muted, style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (job.address.isBlank() && job.startDate.isBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Add a site or date to get started", color = muted,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 8.dp),
                                    color = muted.copy(alpha = 0.28f))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("View details  ›", color = green, style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    if (job.address.isNotBlank()) {
                                        OutlinedButton(onClick = { onNavigate(job.address) },
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                                            Icon(painterResource(R.drawable.ic_navigation), contentDescription = null,
                                                modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Navigate")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Scan job QR") }
            OutlinedButton(onClick = onSync, modifier = Modifier.weight(1f)) { Text("Sync phones") }
        }
    }
}

@Composable
internal fun DraftsScreen(drafts: List<Job>, modifier: Modifier, onNew: () -> Unit,
    onOpen: (String) -> Unit, onDelete: (String) -> Unit, onDeleteImmediately: (String) -> Unit,
    instantDeleteOnLongSwipe: Boolean) {
    Column(modifier.padding(20.dp)) {
        PageTitle("Drafts", "Save up to three unfinished jobs.")
        Button(onClick = onNew, enabled = drafts.size < 3, modifier = Modifier.fillMaxWidth()) {
            Text("New draft")
        }
        Spacer(Modifier.height(12.dp))
        if (drafts.isEmpty()) Text("No drafts yet.", color = muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(drafts, key = { it.id }) { draft ->
                SwipeDeleteContainer("Delete draft", instantDeleteOnLongSwipe,
                    onDelete = { onDelete(draft.id) }, onDeleteImmediately = { onDeleteImmediately(draft.id) }) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onOpen(draft.id) }.padding(4.dp)) {
                                Text(draft.label, color = UiInk, fontWeight = FontWeight.SemiBold)
                                Text("Continue setup · Swipe left to delete", color = muted, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onDelete(draft.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(darkMode: Boolean, onDarkMode: (Boolean) -> Unit,
    qrSharing: Boolean, onQrSharing: (Boolean) -> Unit, pdfSharing: Boolean, onPdfSharing: (Boolean) -> Unit,
    instantDeleteOnLongSwipe: Boolean, onInstantDeleteOnLongSwipe: (Boolean) -> Unit,
    version: String, modifier: Modifier, onRecycleBin: () -> Unit,
    companyName: String, onCompanyName: (String) -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        PageTitle("Settings", "")
        Text("Company", color = muted, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            OutlinedTextField(value = companyName, onValueChange = onCompanyName,
                label = { Text("Company name") }, singleLine = true,
                supportingText = { Text("Shown on exported PDFs. Saved automatically.") },
                modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Appearance", color = muted, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Dark mode", color = UiInk, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = darkMode, onCheckedChange = onDarkMode)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Share job", color = muted, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("QR code", color = UiInk, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = qrSharing, onCheckedChange = onQrSharing, enabled = !qrSharing || pdfSharing)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PDF", color = UiInk, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = pdfSharing, onCheckedChange = onPdfSharing, enabled = !pdfSharing || qrSharing)
            }
        }
        Text("Keep at least one sharing option on.", color = muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        Text("Swipe to delete", color = muted, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skip confirmation for long swipes", color = UiInk, style = MaterialTheme.typography.titleMedium)
                    Text("Swipe a job or draft far left to move it straight to the Recycle bin. Shorter swipes still ask first.", color = muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = instantDeleteOnLongSwipe, onCheckedChange = onInstantDeleteOnLongSwipe)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("About", color = muted, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("App version", color = UiInk, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text(version.ifBlank { "Unavailable" }, color = muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRecycleBin) { Text("Recycle bin") }
    }
}

@Composable
internal fun SyncScreen(sync: WifiDirectSync, jobs: List<Job>, drafts: List<Job>, permissionGranted: Boolean,
    onPermission: () -> Unit, modifier: Modifier) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      item {
        PageTitle("Sync phones", "Keep both phones nearby with this screen open. No internet or account is needed.")
        Text("Choose what this phone sends. Each phone makes its own choices. Prices stay off unless you select them. Details you leave out stay as they are on the other phone.", color = muted)
      }
      item {
        Text("Saved jobs", color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { sync.selectAllJobs(jobs.map { it.id }) }, enabled = !sync.busy) { Text("Select all") }
            TextButton(onClick = sync::clearJobs, enabled = !sync.busy) { Text("Clear") }
        }
        if (jobs.isEmpty()) Text("No saved jobs on this phone.", color = muted)
      }
      items(jobs, key = { "job_${it.id}" }) { job ->
          SyncChoiceCard(job, false, sync, expandedId == "job_${job.id}",
              onExpand = { expandedId = if (expandedId == "job_${job.id}") null else "job_${job.id}" })
      }
      if (drafts.isNotEmpty()) {
          item { Text("Drafts", color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
          items(drafts, key = { "draft_${it.id}" }) { draft ->
              SyncChoiceCard(draft, true, sync, expandedId == "draft_${draft.id}",
                  onExpand = { expandedId = if (expandedId == "draft_${draft.id}") null else "draft_${draft.id}" })
          }
      }
      item {
        Text("Connect phones", color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("You can leave everything unselected if this phone only needs to receive. If this phone already has three drafts, extra incoming drafts stay on the sending phone.", color = muted)
        Spacer(Modifier.height(12.dp))
        if (!permissionGranted) {
            Text("Allow nearby and local network access to connect the phones. On older Android versions, this uses Location permission.", color = UiInk)
            Button(onClick = onPermission, modifier = Modifier.fillMaxWidth()) { Text("Allow nearby access") }
        } else {
            Text(sync.status, color = UiInk)
            Spacer(Modifier.height(12.dp))
            if (sync.code != null) {
                Text(sync.code.orEmpty(), color = UiInk, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text("Confirm only if this code matches on the other phone.", color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { sync.confirm(true) }) { Text("Codes match") }
                    OutlinedButton(onClick = { sync.confirm(false) }) { Text("Cancel") }
                }
            } else if (sync.result == null && !sync.busy) {
                OutlinedButton(onClick = sync::discover, modifier = Modifier.fillMaxWidth()) { Text("Search again") }
                Spacer(Modifier.height(8.dp))
                Text("Nearby phones", color = UiInk, fontWeight = FontWeight.Bold)
                if (sync.peers.isEmpty()) Text("No phones found yet. Open Sync phones on the other phone, turn on Wi-Fi and Location, then search again.", color = muted)
                sync.peers.forEach { peer ->
                    OutlinedButton(onClick = { sync.connect(peer) }, modifier = Modifier.fillMaxWidth()) {
                        Text(peer.deviceName.ifBlank { "Nearby phone" })
                    }
                }
            }
        }
      }
    }
}

@Composable
private fun SyncChoiceCard(job: Job, draft: Boolean, sync: WifiDirectSync, expanded: Boolean, onExpand: () -> Unit) {
    val selected = sync.selection.categories(job.id, draft)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected.isNotEmpty(), onCheckedChange = { sync.choose(job.id, draft, it) }, enabled = !sync.busy)
            Text(job.label, modifier = Modifier.weight(1f).clickable(onClick = onExpand), color = UiInk)
            TextButton(onClick = onExpand) { Text(if (expanded) "Hide" else "Details") }
        }
        if (expanded) {
            SyncCategory.entries.forEach { category ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !sync.busy) {
                    sync.setCategory(job.id, draft, category, category !in selected)
                }.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = category in selected, onCheckedChange = {
                        sync.setCategory(job.id, draft, category, it)
                    }, enabled = !sync.busy)
                    Text(category.label, color = UiInk)
                }
            }
        }
    }
}

@Composable
internal fun HistoryScreen(jobs: List<Job>, modifier: Modifier, onOpen: (String) -> Unit, onNavigate: (String) -> Unit) {
    Column(modifier.padding(20.dp)) {
        PageTitle("History", "Completed jobs stay here for reference.")
        if (jobs.isEmpty()) Text("No completed jobs yet.", color = muted)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs.sortedByDescending { it.updatedAt }, key = { it.id }) { job ->
                OutlinedCard(Modifier.fillMaxWidth().clickable { onOpen(job.id) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(job.label, color = UiInk, fontWeight = FontWeight.Bold)
                        if (job.address.isNotBlank()) Text(job.address, color = muted)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("View details  ›", color = green, style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (job.address.isNotBlank()) OutlinedButton(onClick = { onNavigate(job.address) },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                                Icon(painterResource(R.drawable.ic_navigation), contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Navigate")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScreen(job: Job, modifier: Modifier, onEdit: (Int) -> Unit,
    onCall: (String) -> Unit, onPhoto: (String) -> Unit, onShare: () -> Unit, onPdf: () -> Unit,
    qrSharing: Boolean, pdfSharing: Boolean,
    onActivate: () -> Unit, onPlan: () -> Unit, onComplete: () -> Unit) {
    var shareSheetOpen by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(job.displayState(), color = if (job.state == Job.ACTIVE) green else muted, fontWeight = FontWeight.Bold)
        PageTitle(job.label, if (job.state == Job.COMPLETED) "Saved in history" else "Everything you need on-site")
        when (job.state) {
            Job.ACTIVE, Job.PLANNED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (job.state == Job.ACTIVE) {
                    OutlinedButton(onClick = onPlan, modifier = Modifier.weight(1f)) { Text("Move to Planning") }
                } else {
                    Button(onClick = onActivate, modifier = Modifier.weight(1f)) { Text("Activate") }
                }
                OutlinedButton(onClick = onComplete, modifier = Modifier.weight(1f)) { Text("Mark completed") }
            }
            Job.COMPLETED -> OutlinedButton(onClick = onPlan, modifier = Modifier.fillMaxWidth()) {
                Text("Restore to Planning")
            }
        }
        OutlinedButton(onClick = { shareSheetOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Share job") }
        Spacer(Modifier.height(20.dp))
        DetailSection("Clients", 0, onEdit) {
            job.clients.forEach { person ->
                Text(person.name, fontWeight = FontWeight.SemiBold, color = UiInk)
                if (person.phone.isNotBlank()) TextButton(onClick = { onCall(person.phone) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text("Call ${displayPhone(person.phone)}") }
            }
        }
        DetailSection("Job site", 1, onEdit) {
            Text(job.address.ifBlank { "No address yet" }, color = UiInk)
        }
        DetailSection("Schedule", 2, onEdit) {
            Text(scheduleSummary(job, LocalJobTimeFormat.current), color = UiInk)
            Spacer(Modifier.height(8.dp))
            Text("Calendar events you already saved are not changed by edits.", color = muted, style = MaterialTheme.typography.bodySmall)
        }
        DetailSection("Work details", 3, onEdit) { Text(job.description.ifBlank { "No details yet" }, color = UiInk) }
        DetailSection("Materials", 4, onEdit) {
            if (job.inventory.isEmpty()) Text("No items planned", color = muted)
            job.inventory.filter { it.name.isNotBlank() }.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${item.quantity.ifBlank { "1" }} × ${item.name}", color = UiInk, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (item.price.isNotBlank()) Text(displayMaterialPrice(item.price), color = UiInk)
                }
                if (item.notes.isNotBlank()) Text(item.notes, color = muted)
                Spacer(Modifier.height(8.dp))
            }
            if (job.inventory.any { it.price.isNotBlank() }) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Total of entered prices: ${materialTotal(job.inventory).asMoney()}", color = UiInk, fontWeight = FontWeight.SemiBold)
            }
        }
        DetailSection("Outside workers", 5, onEdit, bottomPadding = 4.dp) {
            if (job.workers.isEmpty()) Text("None added", color = muted)
            job.workers.forEach { worker ->
                Text(worker.name.ifBlank { "Unnamed worker" }, color = UiInk, fontWeight = FontWeight.SemiBold)
                if (worker.work.isNotBlank()) Text(worker.work, color = muted)
                if (worker.phone.isNotBlank()) TextButton(onClick = { onCall(worker.phone) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(0.dp)) { Text("Call ${displayPhone(worker.phone)}") }
            }
        }
        DetailSection("Photos", 6, onEdit) {
            if (job.photos.isEmpty()) Text("No photos yet", color = muted)
            job.photos.forEachIndexed { index, path ->
                Row(Modifier.fillMaxWidth().clickable { onPhoto(path) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoImage(path, Modifier.size(72.dp)); Spacer(Modifier.width(12.dp)); Text("Photo ${index + 1} · view", color = UiInk)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
    if (shareSheetOpen) {
        ModalBottomSheet(onDismissRequest = { shareSheetOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("Share job", color = UiInk, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Choose how to share this job.", color = muted)
                Spacer(Modifier.height(20.dp))
                if (qrSharing) {
                    OutlinedCard(Modifier.fillMaxWidth().clickable { shareSheetOpen = false; onShare() }) {
                        Column(Modifier.padding(18.dp)) {
                            Text("QR code", color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Show a code another phone can scan.", color = muted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (pdfSharing) {
                    OutlinedCard(Modifier.fillMaxWidth().clickable { shareSheetOpen = false; onPdf() }) {
                        Column(Modifier.padding(18.dp)) {
                            Text("PDF", color = UiInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Choose details to include, then preview the PDF.", color = muted)
                        }
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, step: Int, onEdit: (Int) -> Unit,
    bottomPadding: Dp = 16.dp, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = bottomPadding)) {
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
private fun Field(label: String, value: String, onValueChange: (String) -> Unit, singleLine: Boolean = true,
    minLines: Int = 1, keyboard: KeyboardType = KeyboardType.Text, prefix: @Composable (() -> Unit)? = null) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        singleLine = singleLine, minLines = minLines, prefix = prefix, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (keyboard == KeyboardType.Phone) PhoneVisualTransformation else VisualTransformation.None)
}

internal object PhoneVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val formatted = displayPhone(raw)
        if (formatted == raw) return TransformedText(text, OffsetMapping.Identity)
        val firstDash = raw.length > 3
        val secondDash = raw.length > 6
        return TransformedText(AnnotatedString(formatted), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                offset + (if (firstDash && offset >= 3) 1 else 0) + (if (secondDash && offset >= 6) 1 else 0)
            override fun transformedToOriginal(offset: Int): Int =
                (offset - (if (firstDash && offset > 3) 1 else 0) - (if (secondDash && offset > 7) 1 else 0))
                    .coerceIn(0, raw.length)
        })
    }
}

@Composable
internal fun ShareScreen(job: Job, modifier: Modifier) {
    var includePrices by rememberSaveable(job.id) { mutableStateOf(false) }
    val payload = remember(job, includePrices) { sharePayload(job, includePrices) }
    val bytes = payload.toByteArray(Charsets.UTF_8).size
    val invalidPrices = includePrices && job.inventory.any { !validMaterialPrice(it.price) }
    val bitmap = remember(payload, invalidPrices) { if (bytes <= 1800 && !invalidPrices) qrBitmap(payload) else null }
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PageTitle("Share ${job.label}", "Show this code to another Job Tracker user.")
        Row(Modifier.fillMaxWidth().clickable { includePrices = !includePrices }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includePrices, onCheckedChange = { includePrices = it })
            Text("Include material prices", color = UiInk)
        }
        if (invalidPrices) Text("Check material prices in the job before including them.", color = MaterialTheme.colorScheme.error)
        else if (bitmap == null) Text("This job is too large for an offline QR code. Shorten the description or worker details, then try again.", color = MaterialTheme.colorScheme.error)
        else Image(bitmap.asImageBitmap(), contentDescription = "Job QR code", modifier = Modifier.fillMaxWidth().height(300.dp))
        Spacer(Modifier.height(12.dp))
        Text("Offline · no internet or account needed", color = UiInk, fontWeight = FontWeight.Bold)
        Text("Includes client contacts, address, schedule, work details, materials, and worker assignments. Photos are not included. ${if (includePrices) "Material prices are included." else "Material prices are not included."} Anyone who scans this code can read those details. The code does not expire or revoke; it is a snapshot and later edits will not update imported copies.", color = muted)
    }
}

@Composable
internal fun PreviewScreen(job: Job, duplicate: Boolean, modifier: Modifier, onCancel: () -> Unit, onImport: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        PageTitle("Import preview", "Review the shared job before saving it.")
        if (duplicate) Text("You already imported this exact QR snapshot. Importing again creates another separate copy.", color = amber, fontWeight = FontWeight.Bold)
        Text(job.label, color = UiInk, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ReviewText("Clients", job.clients.joinToString { "${it.name}${if (it.phone.isBlank()) "" else " · ${displayPhone(it.phone)}"}" })
        ReviewText("Address", job.address)
        ReviewText("Schedule", scheduleSummary(job, LocalJobTimeFormat.current))
        ReviewText("Work details", job.description)
        ReviewText("Materials", job.inventory.filter { it.name.isNotBlank() }.joinToString("\n") { materialSummary(it) })
        ReviewText("Outside workers", job.workers.joinToString { "${it.name}: ${it.work}${if (it.phone.isBlank()) "" else " · ${displayPhone(it.phone)}"}" })
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
