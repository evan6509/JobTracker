package com.evanchubbuck.jobtracker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.app.ActivityManager
import android.os.Build
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.evanchubbuck.jobtracker.ui.theme.JobTrackerTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("job_tracker_settings", MODE_PRIVATE)
        val initialDarkMode = prefs.getBoolean("dark_mode", true)
        enableEdgeToEdge(
            statusBarStyle = if (initialDarkMode) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                else SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = if (initialDarkMode) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContent {
            var darkMode by remember { mutableStateOf(initialDarkMode) }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = darkMode
                    isAppearanceLightNavigationBars = !darkMode
                }
            }
            JobTrackerTheme(darkTheme = darkMode) {
                JobTrackerApp(darkMode) { enabled -> darkMode = enabled; prefs.edit().putBoolean("dark_mode", enabled).apply() }
            }
        }
    }

    @Composable
    private fun JobTrackerApp(darkMode: Boolean, onDarkMode: (Boolean) -> Unit) {
        val store = remember { JobStore(this) }
        val settings = remember { getSharedPreferences("job_tracker_settings", MODE_PRIVATE) }
        var qrSharing by remember { mutableStateOf(settings.getBoolean("share_qr", true)) }
        var pdfSharing by remember { mutableStateOf(settings.getBoolean("share_pdf", true)) }
        var instantDeleteOnLongSwipe by remember { mutableStateOf(settings.getBoolean("instant_draft_delete", false)) }
        val sync = remember { WifiDirectSync(this, store) }
        val releaseUpdatesEnabled = remember { resources.getBoolean(R.bool.release_updates_enabled) }
        val installedVersion = remember {
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("")
        }
        val nearbyPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        var nearbyAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, nearbyPermission) == PackageManager.PERMISSION_GRANTED) }
        var networkAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) }
        val nearbyRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            nearbyAllowed = grants[nearbyPermission] == true
            if (Build.VERSION.SDK_INT >= 37) networkAllowed = grants[Manifest.permission.ACCESS_LOCAL_NETWORK] == true
        }
        var jobs by remember { mutableStateOf(store.jobs()) }
        var drafts by remember { mutableStateOf(store.drafts()) }
        var recycled by remember { mutableStateOf(store.recycled()) }
        var screen by rememberSaveable { mutableStateOf("home") }
        var selectedId by rememberSaveable { mutableStateOf("") }
        var editingId by rememberSaveable { mutableStateOf("") }
        var activeDraftId by rememberSaveable { mutableStateOf("") }
        var editor by remember { mutableStateOf<Job?>(null) }
        var step by rememberSaveable { mutableIntStateOf(0) }
        var error by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var deleteDraftId by remember { mutableStateOf<String?>(null) }
        var clearAllData by remember { mutableStateOf(false) }
        var menuExpanded by remember { mutableStateOf(false) }
        var activateId by remember { mutableStateOf<String?>(null) }
        var completeId by remember { mutableStateOf<String?>(null) }
        var deleteJobId by remember { mutableStateOf<String?>(null) }
        var photoPath by remember { mutableStateOf<String?>(null) }
        var cameraJobId by rememberSaveable { mutableStateOf("") }
        var pdfPath by rememberSaveable { mutableStateOf("") }
        var pdfSubject by rememberSaveable { mutableStateOf("") }
        var pdfReturnScreen by rememberSaveable { mutableStateOf("detail") }
        var creatingPdf by remember { mutableStateOf(false) }
        var costs by remember { mutableStateOf<List<CostItem>>(emptyList()) }
        var scanRaw by rememberSaveable { mutableStateOf("") }
        var checkingUpdate by remember { mutableStateOf(false) }
        var latestRelease by remember { mutableStateOf<GitHubRelease?>(null) }
        val scanPreview = remember(scanRaw) { runCatching { if (scanRaw.isBlank()) null else JSONObject(scanRaw).getJSONObject("job").toJob() }.getOrNull() }
        val scanSource = remember(scanRaw) { runCatching { JSONObject(scanRaw).getString("source") }.getOrDefault("") }
        val scope = rememberCoroutineScope()
        DisposableEffect(releaseUpdatesEnabled) {
            var startupCheck: kotlinx.coroutines.Job? = null
            fun checkOnOpen() {
                if (releaseUpdatesEnabled && startupCheck?.isActive != true) {
                    startupCheck = scope.launch {
                        startupUpdate(installedVersion)?.let { latestRelease = it }
                    }
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> checkOnOpen()
                    Lifecycle.Event.ON_STOP -> startupCheck?.cancel()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) checkOnOpen()
            onDispose {
                lifecycle.removeObserver(observer)
                startupCheck?.cancel()
            }
        }

        DisposableEffect(screen, nearbyAllowed, networkAllowed) {
            if (screen == "sync" && nearbyAllowed && networkAllowed) sync.start()
            onDispose { if (screen == "sync") sync.stop() }
        }
        LaunchedEffect(sync.result) {
            if (sync.result != null) {
                jobs = store.jobs()
                drafts = store.drafts()
                costs = store.costs(selectedId)
            }
        }

        fun persist(list: List<Job>) { jobs = list; store.saveJobs(list) }
        fun deleteUnusedPhotos(paths: List<String>, usedPhotos: Set<String>) {
            val retainedPhotos = usedPhotos + store.recycled().flatMap { it.job.photos }
            deleteUnreferencedJobPhotos(this@MainActivity, paths, retainedPhotos)
        }
        fun discardDraft(id: String) {
            if (!store.recycle(id, draft = true)) return
            drafts = store.drafts()
            recycled = store.recycled()
        }
        fun deleteSavedJob(id: String) {
            if (jobs.none { it.id == id && it.state != Job.COMPLETED } || !store.recycle(id, draft = false)) return
            jobs = store.jobs()
            recycled = store.recycled()
            selectedId = ""
            screen = "home"
        }
        fun restoreRecycled(id: String) {
            when (store.restoreRecycled(id)) {
                RecycleRestoreResult.RESTORED -> {
                    jobs = store.jobs()
                    drafts = store.drafts()
                    recycled = store.recycled()
                }
                RecycleRestoreResult.DRAFT_LIMIT -> message = "You already have three drafts. Finish a draft or move one to the Recycle bin, then try again."
                RecycleRestoreResult.ALREADY_EXISTS -> message = "This job or draft is already on this phone. The copy in the Recycle bin has been kept."
                RecycleRestoreResult.MISSING -> recycled = store.recycled()
            }
        }
        fun deleteRecycledForever(ids: Set<String>) {
            val removed = store.deleteRecycled(ids)
            recycled = store.recycled()
            val usedPhotos = jobs.flatMap { it.photos }.toSet() + drafts.flatMap { it.photos }
            deleteUnusedPhotos(removed.flatMap { it.job.photos }, usedPhotos)
        }
        fun changeEditor(value: Job) {
            editor = value
            if (editingId.isBlank()) {
                val changed = value.copy(updatedAt = System.currentTimeMillis())
                drafts = drafts.map { if (it.id == activeDraftId) changed else it }
                editor = changed
                store.saveDraft(changed)
            }
            else persist(jobs.map { if (it.id == editingId) value.copy(updatedAt = System.currentTimeMillis()) else it })
        }
        fun openEditor(id: String = "", section: Int = 0) {
            editingId = id
            if (id.isBlank()) {
                if (drafts.size >= 3) { screen = "drafts"; return }
                val newDraft = Job()
                activeDraftId = newDraft.id
                store.saveDraft(newDraft)
                drafts = drafts + newDraft
                editor = newDraft
                step = 0
            } else {
                activeDraftId = ""
                editor = jobs.firstOrNull { it.id == id }
                step = section
            }
            error = ""; screen = "editor"
        }
        fun openDraft(id: String) {
            val draft = drafts.firstOrNull { it.id == id } ?: return
            editingId = ""
            activeDraftId = id
            editor = draft
            step = store.draftStep(id)
            error = ""; screen = "editor"
        }
        fun setState(id: String, state: String) {
            persist(changeJobState(jobs, id, state))
        }
        fun back() {
            when (screen) {
                "editor" -> if (editingId.isNotBlank() && step == 6) { screen = "detail"; editor = null }
                    else if (step > 0) { step--; if (editingId.isBlank()) store.saveDraftStep(activeDraftId, step); error = "" }
                    else { screen = if (editingId.isBlank()) "drafts" else "detail"; editor = null }
                "share", "costs" -> screen = "detail"
                "recycle_bin" -> screen = "settings"
                "pdf" -> { screen = pdfReturnScreen; pdfPath = ""; pdfSubject = "" }
                else -> screen = "home"
            }
        }
        fun launch(intent: Intent, failure: String) {
            try { startActivity(intent) } catch (_: ActivityNotFoundException) { message = failure }
        }
        fun navigate(address: String) {
            if (address.isNotBlank()) launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}")), "No map app is available.")
        }
        fun calendar(job: Job) {
            val range = calendarRange(job)
            if (range == null) { message = "Choose a valid start date first."; return }
            launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, job.label)
                .putExtra(CalendarContract.Events.EVENT_LOCATION, job.address)
                .putExtra(CalendarContract.Events.DESCRIPTION, job.description)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, range.allDay)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, range.start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, range.end), "No calendar app is available.")
        }
        fun sharePdf(file: File, subject: String) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    clipData = android.content.ClipData.newUri(contentResolver, "JobTracker PDF", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                launch(Intent.createChooser(intent, "Send PDF"), "No app is available to send the PDF.")
            } catch (_: Exception) { message = "Could not share the PDF. Try again." }
        }
        fun previewPdf(job: Job, costExport: Boolean) {
            if (creatingPdf) return
            val returnScreen = screen
            val exportCosts = costs
            creatingPdf = true
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        if (costExport) createCostsPdf(this@MainActivity, job, exportCosts) else createJobPdf(this@MainActivity, job)
                    }
                    if (screen == returnScreen && selectedId == job.id) {
                        pdfPath = file.absolutePath
                        pdfSubject = if (costExport) "${job.label} costs" else job.label
                        pdfReturnScreen = returnScreen
                        screen = "pdf"
                    } else file.delete()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) { message = "Could not create the PDF. Try again." }
                finally { creatingPdf = false }
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val current = editor ?: if (editingId.isBlank()) drafts.firstOrNull { it.id == activeDraftId }
                else jobs.firstOrNull { it.id == editingId }
            if (current != null && uris.isNotEmpty()) {
                val editSession = editingId
                scope.launch {
                    val paths = withContext(Dispatchers.IO) { uris.mapNotNull { copyPhoto(this@MainActivity, it) } }
                    val latest = editor ?: if (editingId.isBlank()) drafts.firstOrNull { it.id == activeDraftId }
                        else jobs.firstOrNull { it.id == editingId }
                    if (latest?.id == current.id && editingId == editSession && screen == "editor") changeEditor(latest.copy(photos = latest.photos + paths))
                    else paths.forEach { File(it).delete() }
                    if (paths.size != uris.size) message = "Some photos could not be added."
                }
            }
        }
        val photoCamera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) result.data?.getStringExtra(PhotoCaptureActivity.PHOTO_PATH)?.let { path ->
                val updated = attachCapturedPhoto(this@MainActivity, store, cameraJobId, path)
                if (updated == null) {
                    val used = (store.jobs() + store.drafts()).flatMap { it.photos }.toSet()
                    deleteUnusedPhotos(listOf(path), used)
                    message = "This job or draft is no longer available. The photo wasn't added."
                } else {
                    jobs = store.jobs()
                    drafts = store.drafts()
                    if ((editingId.ifBlank { activeDraftId }) == updated.id) editor = updated
                }
            }
            cameraJobId = ""
        }
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            if (result.contents != null) {
                try {
                    val data = JSONObject(result.contents)
                    require(data.optString("format") == "jobtracker-v1")
                    val imported = data.getJSONObject("job").toJob()
                    require(imported.clients.any { it.name.isNotBlank() })
                    require(data.getString("source").isNotBlank())
                    scanRaw = result.contents
                    screen = "preview"
                } catch (_: Exception) { message = "This is not a valid JobTracker QR code." }
            }
        }
        fun scan() { scanner.launch(ScanOptions().setCaptureActivity(JobScannerActivity::class.java).setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan a JobTracker job QR code").setBeepEnabled(false).setOrientationLocked(false)) }
        fun checkForUpdate() {
            if (!releaseUpdatesEnabled || checkingUpdate) return
            checkingUpdate = true
            scope.launch {
                try {
                    latestRelease = fetchLatestRelease()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = updateCheckErrorMessage(e)
                } finally {
                    checkingUpdate = false
                }
            }
        }

        BackHandler(screen != "home") { back() }
        Scaffold(containerColor = UiCanvas, topBar = {
            Surface(color = UiInk) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (screen == "home") {
                        Box {
                            val menuColor = UiCanvas
                            val menuInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier.size(48.dp).clip(CircleShape)
                                    .clickable(interactionSource = menuInteraction,
                                        indication = ripple(color = menuColor),
                                        onClick = { menuExpanded = true })
                                    .semantics { contentDescription = "Open menu" },
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(Modifier.size(22.dp)) {
                                    for (fraction in listOf(0.22f, 0.5f, 0.78f)) {
                                        val y = size.height * fraction
                                        drawLine(menuColor, Offset(0f, y), Offset(size.width, y),
                                            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                                    }
                                }
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Settings") }, onClick = {
                                    menuExpanded = false; screen = "settings"
                                })
                                if (releaseUpdatesEnabled) {
                                    DropdownMenuItem(text = { Text("Check for updates") }, onClick = {
                                        menuExpanded = false; checkForUpdate()
                                    })
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Clear all data", color = MaterialTheme.colorScheme.error) }, onClick = {
                                    menuExpanded = false; clearAllData = true
                                })
                            }
                        }
                        Text(stringResource(R.string.app_name), color = UiCanvas, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { screen = "history" }) {
                            Icon(painterResource(R.drawable.ic_history), contentDescription = "History", tint = UiCanvas)
                        }
                    } else {
                        TextButton(onClick = { back() }) { Text("‹ Back", color = UiCanvas) }
                    }
                }
            }
        }) { padding ->
            val area = Modifier.fillMaxSize().padding(padding)
            when (screen) {
                "home" -> HomeScreen(jobs, drafts.size, area, onNew = { openEditor() }, onDrafts = { screen = "drafts" },
                    onOpen = { selectedId = it; screen = "detail" }, onScan = { scan() },
                    onSync = { sync.resetSelection(); screen = "sync" },
                    onReorder = { from, to ->
                        persist(reorderVisibleJobs(jobs, from, to))
                    }, onDeleteJob = { deleteJobId = it }, onDeleteJobImmediately = { deleteSavedJob(it) },
                    instantDeleteOnLongSwipe = instantDeleteOnLongSwipe)
                "drafts" -> DraftsScreen(drafts, area, onNew = { openEditor() }, onOpen = { openDraft(it) },
                    onDelete = { deleteDraftId = it }, onDeleteImmediately = { discardDraft(it) },
                    instantDeleteOnLongSwipe = instantDeleteOnLongSwipe)
                "settings" -> SettingsScreen(darkMode, onDarkMode,
                    qrSharing, { enabled ->
                        if (enabled || pdfSharing) {
                            qrSharing = enabled
                            settings.edit().putBoolean("share_qr", enabled).apply()
                        }
                    }, pdfSharing, { enabled ->
                        if (enabled || qrSharing) {
                            pdfSharing = enabled
                            settings.edit().putBoolean("share_pdf", enabled).apply()
                        }
                    }, instantDeleteOnLongSwipe, { enabled ->
                        instantDeleteOnLongSwipe = enabled
                        settings.edit().putBoolean("instant_draft_delete", enabled).apply()
                    }, installedVersion, area, onRecycleBin = { recycled = store.recycled(); screen = "recycle_bin" })
                "recycle_bin" -> RecycleBinScreen(recycled, area, onRestore = { restoreRecycled(it) },
                    onDeleteForever = { deleteRecycledForever(it) })
                "history" -> HistoryScreen(jobs.filter { it.state == Job.COMPLETED }, area) { selectedId = it; screen = "detail" }
                "sync" -> SyncScreen(sync, jobs, drafts, nearbyAllowed && networkAllowed, {
                    nearbyRequest.launch(if (Build.VERSION.SDK_INT in 31..32)
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                    else if (Build.VERSION.SDK_INT >= 37)
                        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK)
                    else arrayOf(nearbyPermission))
                }, area)
                "detail" -> jobs.firstOrNull { it.id == selectedId }?.let { job ->
                    DetailScreen(job, area, onEdit = { openEditor(job.id, it) }, onNavigate = { navigate(job.address) },
                        onCalendar = { calendar(job) }, onCall = { phone -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")), "No phone app is available.") },
                        onPhoto = { photoPath = it }, onShare = { screen = "share" }, onPdf = { previewPdf(job, false) },
                        qrSharing = qrSharing, pdfSharing = pdfSharing,
                        onCosts = { costs = store.costs(job.id); screen = "costs" },
                        onActivate = { if (jobs.any { it.state == Job.ACTIVE && it.id != job.id }) activateId = job.id else setState(job.id, Job.ACTIVE) },
                        onPlan = { setState(job.id, Job.PLANNED) }, onComplete = { completeId = job.id })
                }
                "editor" -> {
                    val current = editor ?: if (editingId.isBlank()) drafts.firstOrNull { it.id == activeDraftId } else jobs.firstOrNull { it.id == editingId }
                    if (current != null) EditorScreen(current, step, editingId.isNotBlank(), error, area,
                        onChange = { changeEditor(it); error = "" }, onStep = { step = it; if (editingId.isBlank()) store.saveDraftStep(activeDraftId, step); error = "" }, onBack = { back() },
                        onExit = { editor = null; screen = if (editingId.isBlank()) "drafts" else "detail" },
                        onPickPhotos = { picker.launch("image/*") }, onPhoto = { photoPath = it },
                        onTakePhoto = { cameraJobId = current.id; photoCamera.launch(Intent(this@MainActivity, PhotoCaptureActivity::class.java)) },
                        onRemovePhoto = { path ->
                            changeEditor(current.copy(photos = current.photos - path))
                            val usedPhotos = jobs.flatMap { it.photos }.toSet() + drafts.flatMap { it.photos }
                            deleteUnusedPhotos(listOf(path), usedPhotos)
                        }, onNavigate = { navigate(current.address) }, onCalendar = { calendar(current) },
                        onFinish = {
                            val problem = validationError(current)
                            if (problem != null) {
                                error = problem
                                step = when {
                                    current.clients.none { it.name.isNotBlank() } || current.clients.any { !validPhone(it.phone) || (it.name.isBlank() && it.phone.isNotBlank()) } -> 0
                                    current.workers.any { !validPhone(it.phone) || (it.name.isBlank() && (it.phone.isNotBlank() || it.work.isNotBlank())) || (it.name.isNotBlank() && it.work.isBlank()) } -> 5
                                    current.inventory.any { it.name.isBlank() && (it.quantity.isNotBlank() || it.notes.isNotBlank()) } -> 4
                                    else -> 2
                                }
                                if (editingId.isBlank()) store.saveDraftStep(activeDraftId, step)
                            } else if (editingId.isBlank()) {
                                val saved = current.copy(clients = current.clients.filter { it.name.isNotBlank() },
                                    workers = current.workers.filter { it.name.isNotBlank() || it.phone.isNotBlank() || it.work.isNotBlank() },
                                    inventory = current.inventory.filter { it.name.isNotBlank() },
                                    priority = (jobs.maxOfOrNull { it.priority } ?: -1) + 1, updatedAt = System.currentTimeMillis())
                                val hadActive = jobs.any { it.state == Job.ACTIVE }
                                persist(jobs + saved)
                                store.deleteDraft(activeDraftId)
                                drafts = drafts.filterNot { it.id == activeDraftId }
                                activeDraftId = ""; editor = null
                                selectedId = saved.id; screen = "detail"
                                if (!hadActive) activateId = saved.id
                            } else { editor = null; selectedId = editingId; screen = "detail" }
                        })
                }
                "share" -> jobs.firstOrNull { it.id == selectedId }?.let { ShareScreen(it, area) }
                "costs" -> jobs.firstOrNull { it.id == selectedId }?.let { job -> CostsScreen(job, costs, area,
                    onChange = { costs = it; store.saveCosts(job.id, it) }, onExport = { previewPdf(job, true) }) }
                "pdf" -> PdfPreviewScreen(File(pdfPath), pdfSubject, area, onShare = { sharePdf(File(pdfPath), pdfSubject) })
                "preview" -> scanPreview?.let { incoming -> PreviewScreen(incoming, jobs.any { it.importedSource == scanSource }, area,
                    onCancel = { scanRaw = ""; screen = "home" }, onImport = {
                        val now = System.currentTimeMillis()
                        val copy = incoming.copy(id = UUID.randomUUID().toString(), state = Job.PLANNED,
                            priority = (jobs.maxOfOrNull { it.priority } ?: -1) + 1, createdAt = now, updatedAt = now,
                            photos = emptyList(), importedSource = scanSource)
                        persist(jobs + copy); scanRaw = ""; selectedId = copy.id; screen = "detail"
                    }) }
            }
        }

        activateId?.let { id ->
            val previous = jobs.firstOrNull { it.state == Job.ACTIVE && it.id != id }
            AlertDialog(onDismissRequest = { activateId = null }, title = { Text(if (previous == null) "Activate this job now?" else "Make this the active job?") },
                text = { Text(if (previous == null) "You can keep it planned and activate it later." else "${previous.label} will move back to planned. Only one job can be active.") },
                confirmButton = { TextButton(onClick = { setState(id, Job.ACTIVE); activateId = null }) { Text("Activate") } },
                dismissButton = { TextButton(onClick = { activateId = null }) { Text(if (previous == null) "Keep planned" else "Cancel") } })
        }
        deleteDraftId?.let { id -> AlertDialog(onDismissRequest = { deleteDraftId = null },
            title = { Text("Delete ${drafts.firstOrNull { it.id == id }?.label ?: "draft"}?") },
            text = { Text("This moves the draft and its photos to the Recycle bin in Settings. You can restore it later.") },
            confirmButton = { TextButton(onClick = { discardDraft(id); deleteDraftId = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteDraftId = null }) { Text("Cancel") } }) }
        if (clearAllData) AlertDialog(onDismissRequest = { clearAllData = false },
            title = { Text("Clear all JobTracker data?") },
            text = { Text("This erases all jobs, drafts, the Recycle bin, photos saved in JobTracker, private costs, settings, and app permissions on this phone. Copies exported elsewhere remain. The app will close.") },
            confirmButton = { TextButton(onClick = {
                clearAllData = false
                val cleared = runCatching {
                    (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }.getOrDefault(false)
                if (!cleared) message = "Could not clear app data. Try again from Android Settings."
            }) { Text("Clear all data") } },
            dismissButton = { TextButton(onClick = { clearAllData = false }) { Text("Cancel") } })
        completeId?.let { id -> AlertDialog(onDismissRequest = { completeId = null }, title = { Text("Mark job completed?") },
            text = { Text("It will move to History and can be restored later.") },
            confirmButton = { TextButton(onClick = { setState(id, Job.COMPLETED); completeId = null; screen = "home" }) { Text("Complete") } },
            dismissButton = { TextButton(onClick = { completeId = null }) { Text("Cancel") } }) }
        deleteJobId?.let { id -> AlertDialog(onDismissRequest = { deleteJobId = null },
            title = { Text("Delete ${jobs.firstOrNull { it.id == id }?.label ?: "job"}?") },
            text = { Text("This moves the job, its costs, and photos to the Recycle bin in Settings. You can restore it later.") },
            confirmButton = { TextButton(onClick = { deleteSavedJob(id); deleteJobId = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteJobId = null }) { Text("Cancel") } }) }
        photoPath?.let { path -> AlertDialog(onDismissRequest = { photoPath = null },
            text = { PhotoImage(path, Modifier.fillMaxWidth().height(350.dp)) },
            confirmButton = { TextButton(onClick = { photoPath = null }) { Text("Close") } }) }
        if (checkingUpdate) AlertDialog(onDismissRequest = {}, title = { Text("Checking for updates") },
            text = { CircularProgressIndicator() }, confirmButton = {})
        if (creatingPdf) AlertDialog(onDismissRequest = {}, title = { Text("Creating PDF preview") },
            text = { CircularProgressIndicator() }, confirmButton = {})
        latestRelease?.let { release ->
            val newer = isNewerRelease(release.tag, installedVersion)
            AlertDialog(onDismissRequest = { latestRelease = null },
                title = { Text(if (newer) "Update available" else "JobTracker is up to date") },
                text = { Text("Installed: v$installedVersion\nLatest release: ${release.tag}" +
                    if (newer) "\n\nDownload the APK, then tap the completed download notification to install it. Android may ask you to allow installs from JobTracker." else "") },
                confirmButton = {
                    TextButton(onClick = {
                        if (newer) {
                            try {
                                downloadRelease(this@MainActivity, release)
                                message = "Downloading ${release.tag}. Tap its notification when the download finishes to install it. Allow installs from JobTracker if Android asks."
                            } catch (_: Exception) {
                                message = "Couldn't start the update download. Please try again."
                            }
                        }
                        latestRelease = null
                    }) { Text(if (newer) "Download APK" else "OK") }
                },
                dismissButton = if (newer) ({ TextButton(onClick = { latestRelease = null }) { Text("Cancel") } }) else null)
        }
        if (message.isNotBlank()) AlertDialog(onDismissRequest = { message = "" }, title = { Text("JobTracker") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { message = "" }) { Text("OK") } })
    }
}
