package com.evanchubbuck.jobtracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

private class PdfPageWriter {
    private val document = PdfDocument()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 12f }
    private var pageNumber = 0
    private var page: PdfDocument.Page? = null
    private var y = 50f
    private val width = 612
    private val height = 792
    private val margin = 48f
    private val lineHeight = 18f

    private fun newPage() {
        page?.let(document::finishPage)
        page = document.startPage(PdfDocument.PageInfo.Builder(width, height, ++pageNumber).create())
        y = margin
    }

    private fun line(value: String, bold: Boolean = false, size: Float = 12f) {
        if (page == null || y > height - margin) newPage()
        paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        paint.textSize = size
        page!!.canvas.drawText(value, margin, y, paint)
        y += if (size > 12f) 27f else lineHeight
    }

    fun text(value: String, bold: Boolean = false, size: Float = 12f) {
        val content = value.ifBlank { "Not set" }
        paint.textSize = size
        paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        content.split('\n').forEach { paragraph ->
            if (paragraph.isBlank()) { line(" "); return@forEach }
            var current = ""
            paragraph.split(Regex("\\s+")).forEach { word ->
                val candidate = if (current.isBlank()) word else "$current $word"
                if (paint.measureText(candidate) > width - 2 * margin && current.isNotBlank()) {
                    line(current, bold, size)
                    current = word
                } else current = candidate
            }
            if (current.isNotBlank()) line(current, bold, size)
        }
    }

    fun section(title: String, value: String) {
        y += 6f
        text(title, bold = true)
        text(value)
    }

    fun photo(title: String, bitmap: Bitmap) {
        newPage()
        text(title, bold = true, size = 16f)
        val availableWidth = width - 2 * margin
        val availableHeight = height - y - margin
        val scale = minOf(availableWidth / bitmap.width, availableHeight / bitmap.height)
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        page!!.canvas.drawBitmap(bitmap, null, RectF(margin, y + 10f, margin + imageWidth, y + 10f + imageHeight), paint)
        y += imageHeight + 20f
    }

    fun save(file: File): File {
        if (page == null) newPage()
        page?.let(document::finishPage)
        file.outputStream().use(document::writeTo)
        document.close()
        return file
    }
}

private fun pdfFile(context: Context, name: String): File =
    File(File(context.cacheDir, "exports").apply { mkdirs() }, "$name-${UUID.randomUUID()}.pdf")

internal fun createJobPdf(context: Context, job: Job): File {
    val pdf = PdfPageWriter()
    pdf.text(job.label, bold = true, size = 20f)
    pdf.text("Job details")
    pdf.section("Clients", job.clients.joinToString("\n") { it.name + if (it.phone.isBlank()) "" else " · ${it.phone}" })
    pdf.section("Job site", job.address)
    pdf.section("Schedule", scheduleSummary(job))
    pdf.section("Work details", job.description)
    pdf.section("Inventory", job.inventory.filter { it.name.isNotBlank() }.joinToString("\n") { "${it.quantity.ifBlank { "1" }} × ${it.name}${if (it.notes.isBlank()) "" else " · ${it.notes}"}" })
    pdf.section("Outside workers", job.workers.joinToString("\n") { "${it.name}: ${it.work}${if (it.phone.isBlank()) "" else " · ${it.phone}"}" })
    job.photos.forEachIndexed { index, path -> sampledBitmap(path)?.let { pdf.photo("Reference photo ${index + 1}", it) } }
    return pdf.save(pdfFile(context, "job"))
}

internal fun createCostsPdf(context: Context, job: Job, costs: List<CostItem>): File {
    val pdf = PdfPageWriter()
    pdf.text("${job.label} · costs", bold = true, size = 20f)
    pdf.text("Private cost export")
    val saved = costs.filter { it.description.isNotBlank() || it.amount.isNotBlank() }
    saved.forEachIndexed { index, item ->
        pdf.section("${index + 1}. ${item.description.ifBlank { "Unnamed cost" }}", item.amount.toBigDecimalOrNull()?.asMoney() ?: "Amount not set")
    }
    val total = costs.mapNotNull { it.amount.toBigDecimalOrNull() }.fold(BigDecimal.ZERO) { sum, amount -> sum + amount }
    pdf.section("Total", total.asMoney())
    return pdf.save(pdfFile(context, "costs"))
}

private fun BigDecimal.asMoney(): String = "$" + setScale(2, RoundingMode.HALF_UP).toPlainString()
