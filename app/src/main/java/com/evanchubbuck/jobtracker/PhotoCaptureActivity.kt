package com.evanchubbuck.jobtracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.evanchubbuck.jobtracker.ui.theme.JobTrackerTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        val dark = getSharedPreferences("job_tracker_settings", MODE_PRIVATE).getBoolean("dark_mode", true)
        setContent {
            val scope = rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            var saveError by remember { mutableStateOf("") }
            JobTrackerTheme(darkTheme = dark) {
                PhotoCaptureScreen(this, saving, saveError, onCancel = { finish() }, onUsePhoto = { file ->
                    saving = true
                    saveError = ""
                    scope.launch {
                        var path: String? = null
                        var returned = false
                        try {
                            withContext(Dispatchers.IO) { path = copyPhoto(this@PhotoCaptureActivity, Uri.fromFile(file)) }
                            if (path == null) {
                                saving = false
                                saveError = "This photo couldn't be added. Try taking it again."
                            } else {
                                setResult(Activity.RESULT_OK, Intent().putExtra(PHOTO_PATH, path))
                                returned = true
                                finish()
                            }
                        } finally {
                            if (!returned) path?.let { File(it).delete() }
                        }
                    }
                })
            }
        }
    }

    companion object { const val PHOTO_PATH = "photo_path" }
}

@Composable
private fun PhotoCaptureScreen(activity: ComponentActivity, saving: Boolean, saveError: String,
    onCancel: () -> Unit, onUsePhoto: (File) -> Unit) {
    val executor = remember(activity) { ContextCompat.getMainExecutor(activity) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requestedPermission by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) {
        if (!permission && !requestedPermission) {
            requestedPermission = true
            permissionRequest.launch(Manifest.permission.CAMERA)
        }
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    val previewView = remember(activity) { PreviewView(activity).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    val disposed = remember { AtomicBoolean(false) }
    var capturedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var ready by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            pendingFile?.delete()
            if (!activity.isChangingConfigurations) capturedPath?.let { File(it).delete() }
        }
    }
    DisposableEffect(permission, capturedPath, retry) {
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var imageCapture: ImageCapture? = null
        var bindingDisposed = false
        ready = false
        capture = null
        if (permission && capturedPath == null) {
            cameraError = ""
            val future = ProcessCameraProvider.getInstance(activity)
            future.addListener({
                if (!bindingDisposed && !disposed.get()) {
                    try {
                        val cameraProvider = future.get()
                        provider = cameraProvider
                        val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                            else CameraSelector.DEFAULT_FRONT_CAMERA
                        val previewCase = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val captureCase = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                        preview = previewCase
                        imageCapture = captureCase
                        cameraProvider.bindToLifecycle(activity, selector, previewCase, captureCase)
                        capture = captureCase
                        ready = true
                    } catch (_: Exception) {
                        cameraError = "The camera couldn't start. Try again or choose a saved photo."
                    }
                }
            }, executor)
        }
        onDispose {
            bindingDisposed = true
            preview?.let { provider?.unbind(it) }
            imageCapture?.let { provider?.unbind(it) }
        }
    }

    BackHandler(saving) { /* Finish the short file save before leaving. */ }
    Column(Modifier.fillMaxSize().background(UiCanvas).safeDrawingPadding().padding(horizontal = 20.dp)) {
        TextButton(onClick = onCancel, enabled = !saving, contentPadding = PaddingValues(vertical = 8.dp)) {
            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back")
        }
        Text(if (capturedPath == null) "Take photo" else "Preview photo", color = UiInk,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (capturedPath == null) "Capture a picture for this job." else "Use this picture or take another.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            val path = capturedPath
            when {
                path != null -> PhotoImage(path, Modifier.fillMaxSize())
                !permission -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow camera access to take a picture.", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    val openSettings = permissionDenied && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                    Button(onClick = {
                        if (openSettings) activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
                        else { requestedPermission = true; permissionRequest.launch(Manifest.permission.CAMERA) }
                    }) { Text(if (openSettings) "Open settings" else "Allow camera") }
                }
                cameraError.isNotBlank() -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(cameraError, color = Color.White)
                    Button(onClick = { retry++ }) { Text("Try again") }
                }
                else -> {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    if (!ready) CircularProgressIndicator()
                }
            }
        }
        if (saveError.isNotBlank()) Text(saveError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        val path = capturedPath
        if (path != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { File(path).delete(); capturedPath = null }, enabled = !saving, modifier = Modifier.weight(1f)) { Text("Retake") }
            Button(onClick = { onUsePhoto(File(path)) }, enabled = !saving, modifier = Modifier.weight(1f)) { Text(if (saving) "Adding photo…" else "Use photo") }
        } else Button(onClick = {
            val imageCapture = capture ?: return@Button
            try {
                val folder = File(activity.cacheDir, "captures").apply { mkdirs() }
                val file = File.createTempFile("capture-", ".jpg", folder)
                pendingFile = file
                imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            if (disposed.get()) file.delete() else { pendingFile = null; capturedPath = file.absolutePath }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            file.delete()
                            if (!disposed.get()) { pendingFile = null; cameraError = "The photo couldn't be taken. Try again." }
                        }
                    })
            } catch (_: Exception) {
                pendingFile?.delete()
                pendingFile = null
                cameraError = "The photo couldn't be taken. Try again."
            }
        }, enabled = ready && pendingFile == null && cameraError.isBlank(), modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_camera), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (pendingFile == null) "Take photo" else "Taking photo…")
        }
        Spacer(Modifier.height(16.dp))
    }
}
