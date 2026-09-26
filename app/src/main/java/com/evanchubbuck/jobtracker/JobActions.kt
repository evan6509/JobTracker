package com.evanchubbuck.jobtracker

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.text.ParsePosition
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal fun sampledBitmap(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
    val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycle() }
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

internal fun phoneInput(value: String): String {
    val digits = value.filter(Char::isDigit)
    return if (digits.length <= 10 && value.all { it.isDigit() || it == '-' || it == ' ' }) digits else value
}

internal fun displayPhone(value: String): String {
    if (value.isEmpty() || value.length > 10 || !value.all(Char::isDigit)) return value
    return buildString {
        append(value.take(3))
        if (value.length > 3) { append('-'); append(value.substring(3, minOf(6, value.length))) }
        if (value.length > 6) { append('-'); append(value.substring(6)) }
    }
}

internal fun validationError(job: Job): String? = when {
    job.clients.none { it.name.isNotBlank() } -> "Enter at least one client name."
    job.clients.any { it.name.isBlank() && it.phone.isNotBlank() } -> "Enter a name for every client with a phone number."
    job.clients.any { !validPhone(it.phone) } -> "Check the client phone numbers."
    job.workers.any { it.name.isBlank() && (it.phone.isNotBlank() || it.work.isNotBlank()) } -> "Enter a name for each outside worker."
    job.workers.any { it.name.isNotBlank() && it.work.isBlank() } -> "Enter assigned work for each outside worker."
    job.workers.any { !validPhone(it.phone) } -> "Check the worker phone numbers."
    job.startDate.isNotBlank() && parseDate(job.startDate, job.timeZone) == null -> "Choose a valid start date."
    job.endDate.isNotBlank() && parseDate(job.endDate, job.timeZone) == null -> "Choose a valid end date."
    job.startTime.isNotBlank() && (job.startDate.isBlank() || parseStart(job) == null) -> "Set a start date and a valid time, such as 09:30."
    job.endDate.isNotBlank() && job.startDate.isBlank() -> "Choose a start date before the end date."
    job.startDate.isNotBlank() && job.endDate.isNotBlank() && parseDate(job.endDate, job.timeZone)!! < parseDate(job.startDate, job.timeZone)!! -> "End date must be on or after the start date."
    job.inventory.any { it.name.isBlank() && (it.quantity.isNotBlank() || it.notes.isNotBlank()) } -> "Name every inventory item you add."
    else -> null
}

internal fun parseDate(value: String, zone: String): Long? {
    if (value.isBlank()) return null
    val position = ParsePosition(0)
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone(zone)
    }.parse(value, position)
    return if (position.index == value.length) date?.time else null
}

internal fun displayDate(value: String, zone: String): String = parseDate(value, zone)?.let {
    SimpleDateFormat("MMMM d, yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(it))
} ?: value

internal fun scheduleSummary(job: Job): String = when {
    job.startDate.isBlank() -> "Date not set"
    job.endDate.isBlank() -> displayDate(job.startDate, job.timeZone) + if (job.startTime.isBlank()) "" else " at ${job.startTime}"
    job.startDate == job.endDate -> displayDate(job.startDate, job.timeZone) + if (job.startTime.isBlank()) "" else " at ${job.startTime}"
    else -> "${displayDate(job.startDate, job.timeZone)} – ${displayDate(job.endDate, job.timeZone)}" + if (job.startTime.isBlank()) "" else " · starts ${job.startTime}"
}

internal fun parseStart(job: Job): Long? {
    if (job.startDate.isBlank()) return null
    if (job.startTime.isBlank()) return parseDate(job.startDate, job.timeZone)
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

internal data class CalendarRange(val start: Long, val end: Long, val allDay: Boolean)

internal fun calendarRange(job: Job): CalendarRange? {
    val startDay = parseDate(job.startDate, job.timeZone) ?: return null
    if (job.endDate.isNotBlank() && (parseDate(job.endDate, job.timeZone) ?: return null) < startDay) return null
    val start = parseStart(job) ?: return null
    val end = if (job.startTime.isBlank()) Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = parseDate(job.endDate.ifBlank { job.startDate }, "UTC") ?: return null
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis else {
        val endAtTime = parseStart(job.copy(startDate = job.endDate.ifBlank { job.startDate })) ?: return null
        if (endAtTime <= start) start + 60 * 60_000L else endAtTime
    }
    return CalendarRange(if (job.startTime.isBlank()) parseDate(job.startDate, "UTC") ?: return null else start,
        end, job.startTime.isBlank())
}

internal fun legacyEndDate(startDate: String, startTime: String, durationMinutes: String, zone: String): String {
    val start = parseStart(Job(startDate = startDate, startTime = startTime, timeZone = zone)) ?: return ""
    val minutes = durationMinutes.toLongOrNull()?.takeIf { it > 0 } ?: return ""
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone(zone) }
        .format(Date(start + minutes * 60_000L))
}

internal fun sharePayload(job: Job): String = JSONObject().apply {
    put("format", "jobtracker-v1")
    put("source", "${job.id}:${job.updatedAt}")
    val snapshot = job.copy(inventory = job.inventory.filter { it.name.isNotBlank() }).toJson(includeLocalPhotos = false)
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
