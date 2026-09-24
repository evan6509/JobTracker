package com.evanchubbuck.jobtracker

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal fun sampledBitmap(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

internal fun copyPhoto(activity: Activity, uri: Uri): String? = runCatching {
    val folder = File(activity.filesDir, "photos").apply { mkdirs() }
    val target = File(folder, "${UUID.randomUUID()}.jpg")
    try {
        activity.contentResolver.openInputStream(uri)?.use { source -> target.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                total += read
                if (total > 25L * 1024 * 1024) error("Photo exceeds 25 MB")
                output.write(buffer, 0, read)
            }
        } } ?: error("Cannot read photo")
    } catch (error: Exception) { target.delete(); throw error }
    target.absolutePath
}.getOrNull()

internal fun validPhone(value: String): Boolean {
    if (value.isBlank()) return true
    val digits = value.count { it.isDigit() }
    return digits in 3..15 && value.all { it.isDigit() || it in "+-(). " }
}

internal fun validationError(job: Job): String? = when {
    job.clients.none { it.name.isNotBlank() } -> "Enter at least one client name."
    job.clients.any { it.name.isBlank() && it.phone.isNotBlank() } -> "Enter a name for every client with a phone number."
    job.clients.any { !validPhone(it.phone) } -> "Check the client phone numbers."
    job.workers.any { it.name.isBlank() && (it.phone.isNotBlank() || it.work.isNotBlank()) } -> "Enter a name for each outside worker."
    job.workers.any { it.name.isNotBlank() && it.work.isBlank() } -> "Enter assigned work for each outside worker."
    job.workers.any { !validPhone(it.phone) } -> "Check the worker phone numbers."
    job.startDate.isNotBlank() != job.startTime.isNotBlank() -> "Enter both a start date and time, or leave both blank."
    job.startDate.isNotBlank() && parseStart(job) == null -> "Use a valid start date and time, such as 2026-10-15 and 09:30."
    job.durationMinutes.isNotBlank() && (job.durationMinutes.toLongOrNull() ?: 0) <= 0 -> "Enter an estimated duration above zero minutes."
    else -> null
}

internal fun parseStart(job: Job): Long? {
    if (job.startDate.isBlank() || job.startTime.isBlank()) return null
    return runCatching {
        val input = "${job.startDate} ${job.startTime}"
        val position = ParsePosition(0)
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone(job.timeZone)
        }.parse(input, position)
        if (position.index == input.length) parsed?.time else null
    }.getOrNull()
}

internal fun sharePayload(job: Job): String = JSONObject().apply {
    put("format", "jobtracker-v1")
    put("source", "${job.id}:${job.updatedAt}")
    val snapshot = job.toJson(includeLocalPhotos = false)
    listOf("id", "state", "priority", "createdAt", "updatedAt", "importedSource").forEach(snapshot::remove)
    put("job", snapshot)
}.toString()

internal fun qrBitmap(value: String): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 700, 700,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
