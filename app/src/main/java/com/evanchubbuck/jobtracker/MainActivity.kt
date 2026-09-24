package com.evanchubbuck.jobtracker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import com.evanchubbuck.jobtracker.ui.theme.JobTrackerTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.File
import java.util.UUID
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
        val sync = remember { WifiDirectSync(this, store) }
        val nearbyPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        var nearbyAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, nearbyPermission) == PackageManager.PERMISSION_GRANTED) }
        var networkAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) }
        val nearbyRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            nearbyAllowed = grants[nearbyPermission] == true
            if (Build.VERSION.SDK_INT >= 37) networkAllowed = grants[Manifest.permission.ACCESS_LOCAL_NETWORK] == true
        }
        var jobs by remember { mutableStateOf(store.jobs()) }
        var draft by remember { mutableStateOf(store.draft()) }
        var screen by rememberSaveable { mutableStateOf("home") }
        var selectedId by rememberSaveable { mutableStateOf("") }
        var editingId by rememberSaveable { mutableStateOf("") }
        var editor by remember { mutableStateOf<Job?>(null) }
        var step by rememberSaveable { mutableIntStateOf(0) }
        var error by remember { mutableStateOf("") }
        var message by remember { mutableStateOf("") }
        var replaceDraft by remember { mutableStateOf(false) }
        var activateId by remember { mutableStateOf<String?>(null) }
        var completeId by remember { mutableStateOf<String?>(null) }
        var photoPath by remember { mutableStateOf<String?>(null) }
        var costs by remember { mutableStateOf<List<CostItem>>(emptyList()) }
        var scanRaw by rememberSaveable { mutableStateOf("") }
        var checkingUpdate by remember { mutableStateOf(false) }
        var latestRelease by remember { mutableStateOf<GitHubRelease?>(null) }
        val scanPreview = remember(scanRaw) { runCatching { if (scanRaw.isBlank()) null else JSONObject(scanRaw).getJSONObject("job").toJob() }.getOrNull() }
        val scanSource = remember(scanRaw) { runCatching { JSONObject(scanRaw).getString("source") }.getOrDefault("") }
        val scope = rememberCoroutineScope()

        DisposableEffect(screen, nearbyAllowed, networkAllowed) {
            if (screen == "sync" && nearbyAllowed && networkAllowed) sync.start()
            onDispose { if (screen == "sync") sync.stop() }
        }
        LaunchedEffect(sync.result) {
            if (sync.result != null) {
                jobs = store.jobs()
                draft = store.draft()
                costs = store.costs(selectedId)
            }
        }

        fun persist(list: List<Job>) { jobs = list; store.saveJobs(list) }
        fun changeEditor(value: Job) {
            editor = value
            if (editingId.isBlank()) { val changed = value.copy(updatedAt = System.currentTimeMillis()); draft = changed; editor = changed; store.saveDraft(changed) }
            else persist(jobs.map { if (it.id == editingId) value.copy(updatedAt = System.currentTimeMillis()) else it })
        }
        fun openEditor(id: String = "", section: Int = 0) {
            editingId = id
            editor = if (id.isBlank()) draft ?: Job() else jobs.firstOrNull { it.id == id }
            if (id.isBlank()) editor?.let { draft = it; store.saveDraft(it) }
            step = if (id.isBlank() && draft != null) store.draftStep() else section
            error = ""; screen = "editor"
        }
        fun setState(id: String, state: String) {
            persist(changeJobState(jobs, id, state))
        }
        fun back() {
            when (screen) {
                "editor" -> if (step > 0) { step--; if (editingId.isBlank()) { store.saveDraftStep(step); draft?.let(::changeEditor) }; error = "" } else { screen = if (editingId.isBlank()) "home" else "detail"; editor = null }
                "share", "costs" -> screen = "detail"
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
        fun exportPdf(job: Job, costExport: Boolean) {
            try {
                val file = if (costExport) createCostsPdf(this, job, costs) else createJobPdf(this, job)
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, if (costExport) "${job.label} costs" else job.label)
                    clipData = android.content.ClipData.newUri(contentResolver, "JobTracker PDF", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                launch(Intent.createChooser(intent, "Send PDF"), "No app is available to send the PDF.")
            } catch (_: Exception) { message = "Could not create the PDF. Try again." }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val current = editor
            if (current != null && uris.isNotEmpty()) {
                val editSession = editingId
                scope.launch {
                    val paths = withContext(Dispatchers.IO) { uris.mapNotNull { copyPhoto(this@MainActivity, it) } }
                    val latest = editor
                    if (latest?.id == current.id && editingId == editSession) changeEditor(latest.copy(photos = latest.photos + paths))
                    else paths.forEach { File(it).delete() }
                    if (paths.size != uris.size) message = "Some photos could not be added."
                }
            }
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
            if (checkingUpdate) return
            checkingUpdate = true
            scope.launch {
                try {
                    latestRelease = fetchLatestRelease()
                } catch (e: Exception) {
                    message = e.message ?: "Could not check GitHub for updates. Check your connection and try again."
                } finally {
                    checkingUpdate = false
                }
            }
        }

        BackHandler(screen != "home") { back() }
        Scaffold(containerColor = UiCanvas, topBar = {
            Surface(color = UiInk) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (screen != "home") TextButton(onClick = { back() }) { Text("‹ Back", color = UiCanvas) }
                    Spacer(Modifier.weight(1f))
                    if (screen == "home") {
                        Text(if (darkMode) "🌙" else "☀️", fontSize = 24.sp)
                        Switch(checked = darkMode, onCheckedChange = onDarkMode,
                            modifier = Modifier.semantics { contentDescription = "Dark mode" })
                    } else if (screen == "detail") {
                        TextButton(onClick = { screen = "home" }) { Text("Home", color = UiCanvas) }
                    }
                }
            }
        }) { padding ->
            val area = Modifier.fillMaxSize().padding(padding)
            when (screen) {
                "home" -> HomeScreen(jobs, draft != null, area, onNew = { if (draft == null) openEditor() else replaceDraft = true }, onResume = { openEditor() },
                    onOpen = { selectedId = it; screen = "detail" }, onHistory = { screen = "history" }, onScan = { scan() },
                    onSync = { screen = "sync" },
                    onCheckUpdate = { checkForUpdate() },
                    onReorder = { from, to ->
                        persist(reorderVisibleJobs(jobs, from, to))
                    })
                "history" -> HistoryScreen(jobs.filter { it.state == Job.COMPLETED }, area) { selectedId = it; screen = "detail" }
                "sync" -> SyncScreen(sync, nearbyAllowed && networkAllowed, {
                    nearbyRequest.launch(if (Build.VERSION.SDK_INT in 31..32)
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                    else if (Build.VERSION.SDK_INT >= 37)
                        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK)
                    else arrayOf(nearbyPermission))
                }, area)
                "detail" -> jobs.firstOrNull { it.id == selectedId }?.let { job ->
                    DetailScreen(job, area, onEdit = { openEditor(job.id, it) }, onNavigate = { navigate(job.address) },
                        onCalendar = { calendar(job) }, onCall = { phone -> launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")), "No phone app is available.") },
                        onPhoto = { photoPath = it }, onShare = { screen = "share" }, onPdf = { exportPdf(job, false) },
                        onCosts = { costs = store.costs(job.id); screen = "costs" },
                        onActivate = { if (jobs.any { it.state == Job.ACTIVE && it.id != job.id }) activateId = job.id else setState(job.id, Job.ACTIVE) },
                        onPlan = { setState(job.id, Job.PLANNED) }, onComplete = { completeId = job.id })
                }
                "editor" -> {
                    val current = editor ?: if (editingId.isBlank()) draft ?: Job() else jobs.firstOrNull { it.id == editingId }
                    if (current != null) EditorScreen(current, step, editingId.isNotBlank(), error, area,
                        onChange = { changeEditor(it); error = "" }, onStep = { step = it; if (editingId.isBlank()) { store.saveDraftStep(step); draft?.let(::changeEditor) }; error = "" }, onBack = { back() },
                        onExit = { editor = null; screen = if (editingId.isBlank()) "home" else "detail" },
                        onPickPhotos = { picker.launch("image/*") }, onPhoto = { photoPath = it },
                        onRemovePhoto = { path ->
                            changeEditor(current.copy(photos = current.photos - path))
                            if (path.startsWith(File(filesDir, "photos").canonicalPath + File.separator)) File(path).delete()
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
                                if (editingId.isBlank()) store.saveDraftStep(step)
                            } else if (editingId.isBlank()) {
                                val saved = current.copy(clients = current.clients.filter { it.name.isNotBlank() },
                                    workers = current.workers.filter { it.name.isNotBlank() || it.phone.isNotBlank() || it.work.isNotBlank() },
                                    inventory = current.inventory.filter { it.name.isNotBlank() },
                                    priority = (jobs.maxOfOrNull { it.priority } ?: -1) + 1, updatedAt = System.currentTimeMillis())
                                val hadActive = jobs.any { it.state == Job.ACTIVE }
                                persist(jobs + saved); draft = null; store.saveDraft(null); editor = null
                                selectedId = saved.id; screen = "detail"
                                if (!hadActive) activateId = saved.id
                            } else { editor = null; selectedId = editingId; screen = "detail" }
                        })
                }
                "share" -> jobs.firstOrNull { it.id == selectedId }?.let { ShareScreen(it, area) }
                "costs" -> jobs.firstOrNull { it.id == selectedId }?.let { job -> CostsScreen(job, costs, area,
                    onChange = { costs = it; store.saveCosts(job.id, it) }, onExport = { exportPdf(job, true) }) }
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
        if (replaceDraft) AlertDialog(onDismissRequest = { replaceDraft = false },
            title = { Text("Start a different job?") },
            text = { Text("This will discard the unfinished draft. You can resume it instead from Home.") },
            confirmButton = { TextButton(onClick = {
                val photoFolder = File(filesDir, "photos").canonicalPath + File.separator
                draft?.photos?.filter { it.startsWith(photoFolder) }?.forEach { File(it).delete() }
                draft = null; store.saveDraft(null); replaceDraft = false; openEditor()
            }) { Text("Discard draft") } },
            dismissButton = { TextButton(onClick = { replaceDraft = false }) { Text("Cancel") } })
        completeId?.let { id -> AlertDialog(onDismissRequest = { completeId = null }, title = { Text("Mark job completed?") },
            text = { Text("It will move to History and can be restored later.") },
            confirmButton = { TextButton(onClick = { setState(id, Job.COMPLETED); completeId = null; screen = "home" }) { Text("Complete") } },
            dismissButton = { TextButton(onClick = { completeId = null }) { Text("Cancel") } }) }
        photoPath?.let { path -> AlertDialog(onDismissRequest = { photoPath = null },
            text = { PhotoImage(path, Modifier.fillMaxWidth().height(350.dp)) },
            confirmButton = { TextButton(onClick = { photoPath = null }) { Text("Close") } }) }
        if (checkingUpdate) AlertDialog(onDismissRequest = {}, title = { Text("Checking GitHub") },
            text = { CircularProgressIndicator() }, confirmButton = {})
        latestRelease?.let { release ->
            val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
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
                            } catch (e: Exception) {
                                message = e.message ?: "Could not start the download."
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
