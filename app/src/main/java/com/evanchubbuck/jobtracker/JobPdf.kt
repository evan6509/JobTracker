package com.evanchubbuck.jobtracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID

internal data class JobPdfPhoto(val path: String, val caption: String, val included: Boolean = true)
internal class PdfPhotoUnavailableException : Exception()

internal data class JobPdfOptions(
    val clients: Boolean = true,
    val site: Boolean = true,
    val schedule: Boolean = true,
    val work: Boolean = true,
    val inventory: Boolean = false,
    val workers: Boolean = false,
    val phoneNumbers: Boolean = false,
    val photos: List<JobPdfPhoto> = emptyList(),
    val prices: Boolean = false
) {
    companion object {
        fun forJob(job: Job) = JobPdfOptions(photos = job.photos.mapIndexed { index, path ->
            JobPdfPhoto(path, "Reference photo ${index + 1}")
        })
    }
}

/** Letter pages, with a reserved footer and line-aware pagination for arbitrary user text. */
private class PdfPageWriter(private val jobName: String, private val reportName: String, companyName: String = "") : AutoCloseable {
    private val company = companyName.trim().replace(Regex("\\s+"), " ")
    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = TOP
    private val ink = Color.rgb(32, 43, 48)
    private val muted = Color.rgb(91, 105, 111)
    private val accent = Color.rgb(39, 102, 80)
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 225, 224); strokeWidth = 1f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 246, 244) }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun paint(size: Float, bold: Boolean = false, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun layout(value: String, width: Float = CONTENT_WIDTH, size: Float = 11f,
                       bold: Boolean = false, color: Int = ink, rightAligned: Boolean = false): StaticLayout =
        StaticLayout.Builder.obtain(value, 0, value.length, paint(size, bold, color), width.toInt())
            .setAlignment(if (rightAligned) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false).setLineSpacing(3f, 1f).build()

    private fun finishPage() {
        val current = page ?: return
        val canvas = current.canvas
        canvas.drawLine(MARGIN, 743f, WIDTH - MARGIN, 743f, rule)
        val footer = paint(9f, color = muted)
        val number = "Page $pageNumber"
        val name = TextUtils.ellipsize(jobName.replace('\n', ' '), footer, CONTENT_WIDTH - 90f, TextUtils.TruncateAt.END)
        canvas.drawText(name.toString(), MARGIN, 761f, footer)
        canvas.drawText(number, WIDTH - MARGIN - footer.measureText(number), 761f, footer)
        document.finishPage(current)
        page = null
    }

    private fun newPage() {
        finishPage()
        page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, ++pageNumber).create())
        y = TOP
        if (pageNumber > 1) {
            val p = paint(9f, true, muted)
            val report = reportName.uppercase()
            val reportWidth = p.measureText(report)
            if (company.isNotBlank()) {
                val name = TextUtils.ellipsize(company, p, CONTENT_WIDTH - reportWidth - 24f, TextUtils.TruncateAt.END)
                page!!.canvas.drawText(name.toString(), MARGIN, y + 9f, p)
                page!!.canvas.drawText(report, WIDTH - MARGIN - reportWidth, y + 9f, p)
            } else page!!.canvas.drawText(report, MARGIN, y + 9f, p)
            page!!.canvas.drawLine(MARGIN, y + 20f, WIDTH - MARGIN, y + 20f, rule)
            y += 34f
        }
    }

    private fun ensureSpace(height: Float) {
        if (page == null || y + height > BOTTOM) newPage()
    }

    private fun drawLayout(canvas: Canvas, text: StaticLayout, x: Float, top: Float) {
        canvas.save()
        canvas.translate(x, top)
        text.draw(canvas)
        canvas.restore()
    }

    fun text(value: String, size: Float = 11f, bold: Boolean = false, color: Int = ink,
             width: Float = CONTENT_WIDTH, x: Float = MARGIN) {
        if (value.isBlank()) return
        val text = layout(value.trim(), width, size, bold, color)
        var start = 0
        while (start < text.lineCount) {
            val firstHeight = (text.getLineBottom(start) - text.getLineTop(start)).toFloat()
            ensureSpace(firstHeight)
            var end = start + 1
            while (end < text.lineCount && y + text.getLineBottom(end) - text.getLineTop(start) <= BOTTOM) end++
            val height = (text.getLineBottom(end - 1) - text.getLineTop(start)).toFloat()
            val canvas = page!!.canvas
            canvas.save()
            canvas.clipRect(x, y, x + width, y + height)
            drawLayout(canvas, text, x, y - text.getLineTop(start))
            canvas.restore()
            y += height
            start = end
            if (start < text.lineCount) newPage()
        }
    }

    fun header() {
        ensureSpace(80f)
        page!!.canvas.drawRect(MARGIN, y, MARGIN + 32f, y + 3f, Paint().apply { color = accent })
        y += 16f
        if (company.isNotBlank()) {
            text(company, 16f, true, accent)
            y += 12f
        }
        text(reportName.uppercase(), 10f, true, accent)
        y += 9f
        text(jobName, 25f, true)
        y += 22f
    }

    fun section(title: String, value: String) {
        if (value.isBlank()) return
        val body = layout(value.trim())
        val firstLines = body.getLineBottom(minOf(1, body.lineCount - 1)).toFloat()
        ensureSpace(44f + firstLines)
        y += 10f
        page!!.canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, rule)
        y += 12f
        text(title, 12f, true, accent)
        y += 8f
        text(value)
        y += 12f
    }

    fun photo(bitmap: Bitmap, caption: String) {
        // Two landscape/square references fit on a page; portraits can use more space.
        val cap = layout(caption.ifBlank { "Reference photo" }, size = 10f, color = muted)
        val portrait = bitmap.height > bitmap.width * 1.35f
        val targetHeight = if (portrait) 420f else 240f
        val scale = minOf(CONTENT_WIDTH / bitmap.width, targetHeight / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val captionHeight = minOf(cap.height.toFloat(), 54f)
        ensureSpace(imageHeight + captionHeight + 30f)
        val x = MARGIN + (CONTENT_WIDTH - imageWidth) / 2f
        page!!.canvas.drawBitmap(bitmap, null, RectF(x, y, x + imageWidth, y + imageHeight), bitmapPaint)
        y += imageHeight + 9f
        text(caption.ifBlank { "Reference photo" }, 10f, color = muted)
        y += 21f
    }

    fun materialHeader() {
        ensureSpace(33f)
        page!!.canvas.drawRoundRect(RectF(MARGIN, y, WIDTH - MARGIN, y + 27f), 4f, 4f, fill)
        val p = paint(10f, true, muted)
        page!!.canvas.drawText("MATERIAL / DESCRIPTION", MARGIN + 10f, y + 18f, p)
        val amount = "PRICE"
        page!!.canvas.drawText(amount, WIDTH - MARGIN - 10f - p.measureText(amount), y + 18f, p)
        y += 38f
    }

    fun material(name: String, description: String, amount: String) {
        val amountWidth = 135f
        val leftWidth = CONTENT_WIDTH - amountWidth - 22f
        val title = layout(name, leftWidth, bold = true)
        val detail = description.takeIf { it.isNotBlank() }?.let { layout(it, leftWidth, color = muted) }
        val amountLayout = layout(amount, amountWidth, bold = true, rightAligned = true)
        val height = title.height + (detail?.height?.plus(5) ?: 0)
        val minimum = maxOf(minOf(height.toFloat(), 65f), amountLayout.height.toFloat()) + 24f
        if (page == null || y + minimum > BOTTOM) { newPage(); materialHeader() }
        val amountTop = y
        val amountPage = pageNumber
        drawLayout(page!!.canvas, amountLayout, WIDTH - MARGIN - amountLayout.width, amountTop)
        text(name, bold = true, width = leftWidth)
        if (detail != null) { y += 5f; text(description, color = muted, width = leftWidth) }
        if (pageNumber == amountPage) y = maxOf(y, amountTop + amountLayout.height)
        y += 11f
        ensureSpace(14f)
        page!!.canvas.drawLine(MARGIN, y, WIDTH - MARGIN, y, rule)
        y += 13f
    }

    fun total(amount: String) {
        val value = layout(amount, CONTENT_WIDTH - 150f, 14f, true, accent, rightAligned = true)
        val boxHeight = maxOf(42f, value.height + 22f)
        ensureSpace(boxHeight + 20f)
        y += 12f
        page!!.canvas.drawRoundRect(RectF(MARGIN, y, WIDTH - MARGIN, y + boxHeight), 5f, 5f, fill)
        page!!.canvas.drawText("Entered prices", MARGIN + 12f, y + 27f, paint(14f, true, accent))
        drawLayout(page!!.canvas, value, WIDTH - MARGIN - 12f - value.width, y + 11f)
        y += boxHeight + 12f
    }

    fun save(file: File): File {
        if (page == null) newPage()
        finishPage()
        file.outputStream().use(document::writeTo)
        return file
    }

    override fun close() {
        finishPage()
        document.close()
    }

    companion object {
        private const val WIDTH = 612
        private const val HEIGHT = 792
        private const val MARGIN = 48f
        private const val TOP = 44f
        private const val BOTTOM = 724f
        private const val CONTENT_WIDTH = WIDTH - MARGIN * 2
    }
}

internal fun clientPdfTitle(job: Job, options: JobPdfOptions): String =
    job.title.ifBlank { if (options.clients) job.label else "Job summary" }

private fun pdfFile(context: Context, name: String, kind: String): File {
    val title = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(70).ifBlank { "Job" }
    return File(File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }, "$title - $kind.pdf")
}

internal fun createJobPdf(context: Context, job: Job, options: JobPdfOptions = JobPdfOptions.forJob(job), companyName: String = ""): File =
    PdfPageWriter(clientPdfTitle(job, options), "Job summary", companyName).use { pdf ->
        pdf.header()
        if (options.clients) pdf.section("Prepared for", job.clients.filter { it.name.isNotBlank() }.joinToString("\n") {
            it.name + if (options.phoneNumbers && it.phone.isNotBlank()) "  |  ${displayPhone(it.phone)}" else ""
        })
        if (options.site) pdf.section("Job site", job.address)
        if (options.schedule && (job.startDate.isNotBlank() || job.endDate.isNotBlank() || job.startTime.isNotBlank()))
            pdf.section("Schedule", scheduleSummary(job, phoneTimeFormat(context)))
        if (options.work) pdf.section("Scope of work", job.description)
        if (options.inventory) {
            val materials = job.inventory.filter { it.name.isNotBlank() }
            if (options.prices && materials.isNotEmpty()) {
                require(materials.all { validMaterialPrice(it.price) }) { "Check material prices before exporting." }
                pdf.materialHeader()
                materials.forEach { item ->
                    pdf.material("${item.quantity.ifBlank { "1" }} x ${item.name}", item.notes,
                        if (item.price.isBlank()) "Not entered" else displayMaterialPrice(item.price))
                }
                if (materials.any { it.price.isNotBlank() }) pdf.total(materialTotal(materials).asMoney())
            } else pdf.section("Materials", materials.joinToString("\n") {
                "${it.quantity.ifBlank { "1" }} x ${it.name}${if (it.notes.isBlank()) "" else "  |  ${it.notes}"}"
            })
        }
        if (options.workers) pdf.section("Outside workers", job.workers.filter { it.name.isNotBlank() }.joinToString("\n") {
            "${it.name}${if (it.work.isBlank()) "" else " - ${it.work}"}${if (options.phoneNumbers && it.phone.isNotBlank()) "  |  ${displayPhone(it.phone)}" else ""}"
        })
        options.photos.filter { it.included && it.path in job.photos }.forEach { photo ->
            sampledBitmap(photo.path)?.let { bitmap ->
                try { pdf.photo(bitmap, photo.caption) } finally { bitmap.recycle() }
            } ?: throw PdfPhotoUnavailableException()
        }
        pdf.save(pdfFile(context, clientPdfTitle(job, options), "Job summary"))
    }

internal fun BigDecimal.asMoney(): String = DecimalFormat("$#,##0.00;-$#,##0.00", DecimalFormatSymbols(Locale.US))
    .apply { roundingMode = RoundingMode.HALF_UP }.format(this)
