package com.evanchubbuck.jobtracker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PdfPagePreview(val bitmap: Bitmap, val pageCount: Int)

@Composable
internal fun PdfPreviewScreen(file: File, subject: String, modifier: Modifier, onShare: () -> Unit) {
    var pageIndex by rememberSaveable(file.absolutePath) { mutableIntStateOf(0) }
    var page by remember(file.absolutePath, pageIndex) { mutableStateOf<PdfPagePreview?>(null) }
    var renderFailed by remember(file.absolutePath, pageIndex) { mutableStateOf(false) }
    var zoom by remember(file.absolutePath, pageIndex) { mutableFloatStateOf(1f) }
    var pan by remember(file.absolutePath, pageIndex) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun constrainPan(offset: Offset, scale: Float): Offset {
        val bitmap = page?.bitmap ?: return Offset.Zero
        val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val maxX = ((bitmap.width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((bitmap.height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 4f)
        pan = constrainPan(pan + panChange, zoom)
    }

    LaunchedEffect(file.absolutePath, pageIndex) {
        try {
            page = withContext(Dispatchers.IO) { renderPdfPage(file, pageIndex) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            renderFailed = true
        }
    }

    Column(modifier.padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("PDF preview", color = UiInk, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero }, enabled = page != null) {
                Text(if (zoom > 1f) "Reset zoom" else "Zoom in")
            }
        }
        Text(subject, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .padding(8.dp).clipToBounds().onSizeChanged { viewport = it },
            contentAlignment = Alignment.Center
        ) {
            when {
                renderFailed -> Text("Could not display this page. You can still share the PDF.", color = UiInk)
                page == null -> CircularProgressIndicator()
                else -> Image(
                    bitmap = page!!.bitmap.asImageBitmap(),
                    contentDescription = "PDF page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxSize()
                        .transformable(transform, canPan = { zoom > 1f })
                        .pointerInput(file.absolutePath, pageIndex) {
                            detectTapGestures(onDoubleTap = {
                                zoom = if (zoom > 1f) 1f else 2f
                                pan = Offset.Zero
                            })
                        }
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text("Previous") }
            Text("Page ${pageIndex + 1} of ${page?.pageCount ?: "…"}", color = UiInk)
            OutlinedButton(onClick = { pageIndex++ }, enabled = page != null && pageIndex + 1 < page!!.pageCount) { Text("Next") }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onShare, enabled = file.isFile && (page != null || renderFailed), modifier = Modifier.fillMaxWidth()) { Text("Share PDF") }
    }
}

private fun renderPdfPage(file: File, pageIndex: Int): PdfPagePreview =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(pageIndex in 0 until renderer.pageCount)
            renderer.openPage(pageIndex).use { page ->
                val width = (page.width * 2).coerceAtMost(1600)
                val height = (page.height.toFloat() * width / page.width).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                PdfPagePreview(bitmap, renderer.pageCount)
            }
        }
    }
