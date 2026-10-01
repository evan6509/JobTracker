package com.evanchubbuck.jobtracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Exercises the actual Android PDF writer, including the contents of the resulting file. */
class JobPdfTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val review get() = File(context.cacheDir, "pdf-review").apply { mkdirs() }
    private val job = Job(title = "Patio refresh", clients = listOf(Person("Taylor Example", "2025550100")),
        address = "123 Example Lane\nSampletown, IN 46000", startDate = "2026-10-06", endDate = "2026-10-08", startTime = "09:00",
        description = "Remove the damaged patio tiles and prepare the surface. Install the selected replacement tiles, grout the joints, and clean the work area.",
        inventory = listOf(InventoryItem("Internal material marker", "24", "Internal purchasing note", "987.65")),
        workers = listOf(Person("Internal worker marker", "2025550101", "Tile installation")))

    private fun contents(file: File): List<String> {
        assumeTrue("Text extraction requires Android 15 or newer", Build.VERSION.SDK_INT >= 35)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                List(renderer.pageCount) { index -> renderer.openPage(index).use { page ->
                    page.textContents.joinToString("\n") { it.text }
                } }
            }
        }
    }

    private fun record(file: File, name: String): File {
        file.copyTo(File(review, name), overwrite = true)
        return file
    }

    @Test fun clientDefaultsExcludeInternalDetailsAndPhoneNumbers() {
        val pdf = record(createJobPdf(context, job), "client-summary.pdf")
        val text = contents(pdf).joinToString("\n")
        assertTrue(text.contains("Taylor Example"))
        assertTrue(text.contains("Scope of work"))
        assertTrue(text.contains("123 Example Lane"))
        assertFalse(text.contains("Internal"))
        assertFalse(text.contains("202-555"))
        assertFalse(text.contains("Expense"))
        assertEquals("Patio refresh - Job summary.pdf", pdf.name)
    }

    @Test fun selectionControlsEverySectionAndPhoto() {
        val first = reference("reference-wide.png", 900, 480, "Before")
        val second = reference("reference-square.png", 700, 700, "After")
        val withPhotos = job.copy(photos = listOf(first.path, second.path))
        val options = JobPdfOptions.forJob(withPhotos).copy(clients = false, site = false, schedule = false, work = false,
            inventory = true, workers = true, phoneNumbers = true,
            photos = listOf(JobPdfPhoto(first.path, "Selected photo caption"), JobPdfPhoto(second.path, "Hidden photo caption", false)))
        val text = contents(record(createJobPdf(context, withPhotos, options), "selected-details.pdf")).joinToString("\n")
        assertFalse(text.contains("Taylor Example"))
        assertFalse(text.contains("123 Example Lane"))
        assertFalse(text.contains("Scope of work"))
        assertFalse(text.contains("Schedule"))
        assertTrue(text.contains("Internal material marker"))
        assertTrue(text.contains("Internal worker marker"))
        assertTrue(text.contains("202-555-0101"))
        assertTrue(text.contains("Selected photo caption"))
        assertFalse(text.contains("Hidden photo caption"))
    }

    @Test fun textPaginatesWithoutLosingContentOrCrossingMargins() {
        val paragraphs = (1..90).joinToString("\n") { "Work item $it: Prepare the surface, install the selected materials, and check the finished work." }
        val token = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(12)
        val lengthy = job.copy(title = "A detailed job summary with a longer title for the client", description = "$paragraphs\n$token\nFINAL WORK MARKER")
        val file = record(createJobPdf(context, lengthy), "long-summary.pdf")
        val pages = contents(file)
        assertTrue(pages.size > 2)
        val text = pages.joinToString("\n")
        assertTrue(text.contains("Work item 90"))
        assertTrue(text.contains("FINAL WORK MARKER"))
        assertTrue(text.filterNot(Char::isWhitespace).contains(token))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
            repeat(renderer.pageCount) { index -> renderer.openPage(index).use { page ->
                assertTrue(pages[index].contains("Page ${index + 1}"))
                page.textContents.flatMap { it.bounds }.forEach { bounds ->
                    assertTrue("Text outside page margin: page=${index + 1}, $bounds", bounds.left >= 46f && bounds.right <= 566f && bounds.top >= 40f && bounds.bottom <= 766f)
                }
            } }
        } }
    }

    @Test fun materialPricesAreOptionalAndOnlyEnteredPricesAreTotalled() {
        val materials = listOf(InventoryItem("Tile supply", "24", "Replacement patio tiles", "1250.50"),
            InventoryItem("Finishing supplies", "2", "Grout and finishing materials", "49.45"),
            InventoryItem("Delivery", notes = "Awaiting supplier quote"))
        val pricedJob = job.copy(inventory = materials)
        val options = JobPdfOptions(inventory = true)
        val withoutPrices = contents(createJobPdf(context, pricedJob, options)).joinToString("\n")
        assertTrue(withoutPrices.contains("Tile supply"))
        assertFalse(withoutPrices.contains("$1,250.50"))
        assertFalse(withoutPrices.contains("Entered prices"))
        val text = contents(record(createJobPdf(context, pricedJob, options.copy(prices = true)), "priced-materials.pdf")).joinToString("\n")
        assertTrue(text.contains("Tile supply"))
        assertTrue(text.contains("$1,250.50"))
        assertTrue(text.contains("$1,299.95"))
        assertTrue(text.contains("Not entered"))
        assertTrue(text.contains("Taylor Example"))
        assertFalse(text.contains("Expense"))
        val unpriced = contents(createJobPdf(context, pricedJob.copy(inventory = materials.map { it.copy(price = "") }),
            options.copy(prices = true))).joinToString("\n")
        assertFalse(unpriced.contains("$0.00"))
        assertFalse(unpriced.contains("Entered prices"))
    }

    @Test fun photosHaveCaptionsAndSharePagesWhenTheyFit() {
        val first = reference("reference-wide.png", 900, 480, "Before")
        val second = reference("reference-square.png", 700, 700, "After")
        val third = reference("reference-portrait.png", 480, 900, "Detail")
        val withPhotos = job.copy(photos = listOf(first.path, second.path, third.path))
        val options = JobPdfOptions.forJob(withPhotos).copy(photos = listOf(
            JobPdfPhoto(first.path, "Existing patio - reference before work begins."),
            JobPdfPhoto(second.path, "Selected tile pattern and finish."),
            JobPdfPhoto(third.path, "Edge detail to match the adjoining surface.")))
        val file = record(createJobPdf(context, withPhotos, options), "client-photos.pdf")
        val pages = contents(file)
        assertTrue(pages.size <= 3)
        assertTrue(pages.joinToString("\n").filterNot(Char::isWhitespace).contains("Edgedetailtomatch"))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
            repeat(renderer.pageCount) { index -> renderer.openPage(index).use { page ->
                // Android's image-content API omits these Canvas images. Check the rendered
                // pixels so this also catches an image lost through premature recycling.
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("Reference photo missing from page ${index + 1}", pixels.count {
                        Color.red(it) in 200..235 && Color.green(it) in 220..245 && Color.blue(it) in 210..240
                    } > 5_000)
                } finally { bitmap.recycle() }
            } }
        } }
    }

    @Test fun anUnreadableSelectedPhotoDoesNotSilentlyDisappear() {
        val missing = File(review, "missing-photo.png").path
        try {
            createJobPdf(context, job.copy(photos = listOf(missing)))
            fail("Expected unreadable selected photo to stop the export")
        } catch (_: PdfPhotoUnavailableException) { }
    }

    @Test fun hidingClientNamesAlsoHidesTheFallbackTitle() {
        val withoutTitle = job.copy(title = "")
        val options = JobPdfOptions.forJob(withoutTitle).copy(clients = false)
        val file = createJobPdf(context, withoutTitle, options)
        assertFalse(file.name.contains("Taylor"))
        assertFalse(contents(file).joinToString("\n").contains("Taylor"))
    }

    @Test fun companyNameIsIncludedWithAndWithoutMaterialPrices() {
        val company = "Example Building & Tile"
        val summary = record(createJobPdf(context, job, companyName = company), "company-summary.pdf")
        val priced = record(createJobPdf(context, job, JobPdfOptions(inventory = true, prices = true), company), "company-materials.pdf")
        for (file in listOf(summary, priced)) {
            val text = contents(file).first().filterNot(Char::isWhitespace)
            assertTrue(text.contains(company.filterNot(Char::isWhitespace)))
            assertTrue(text.contains("Patiorefresh"))
        }
    }

    @Test fun longCompanyNameWrapsAndContinuedPagesIdentifyTheCompany() {
        val company = "Example Building and Renovation Services for Homes and Businesses in the Northwest Region"
        val work = (1..80).joinToString("\n") { "Work item $it: Prepare, install, and check the selected materials." }
        val file = record(createJobPdf(context, job.copy(description = work), companyName = company), "company-long-summary.pdf")
        val pages = contents(file)
        assertTrue(pages.size > 1)
        assertTrue(pages.first().filterNot(Char::isWhitespace).contains(company.filterNot(Char::isWhitespace)))
        pages.drop(1).forEach { assertTrue(it.filterNot(Char::isWhitespace).contains("ExampleBuilding")) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
            repeat(renderer.pageCount) { index -> renderer.openPage(index).use { page ->
                page.textContents.flatMap { it.bounds }.forEach { bounds ->
                    assertTrue("Company/text outside margin: page=${index + 1}, $bounds",
                        bounds.left >= 46f && bounds.right <= 566f && bounds.top >= 40f && bounds.bottom <= 766f)
                }
            } }
        } }
    }

    @Test fun longMaterialDescriptionsContinueWithoutLosingLaterRows() {
        val materials = (1..24).map { index -> InventoryItem("Supply item $index", "2",
            "Details for item $index: " + "Materials and delivery for the selected work. ".repeat(if (index == 8) 85 else 3), "10.25") }
        val pages = contents(record(createJobPdf(context, job.copy(inventory = materials),
            JobPdfOptions(inventory = true, prices = true)), "long-materials.pdf"))
        assertTrue(pages.size > 2)
        val text = pages.joinToString("\n").filterNot(Char::isWhitespace)
        assertTrue(text.contains("Supplyitem24"))
        assertTrue(text.contains("$246.00"))
    }

    private fun reference(name: String, width: Int, height: Int, label: String): File {
        val file = File(review, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(226, 235, 229))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(118, 143, 132); strokeWidth = 4f }
        for (x in 0..width step 120) canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), p)
        for (y in 0..height step 120) canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), p)
        p.color = Color.rgb(32, 68, 53); p.textSize = 48f
        canvas.drawText(label, 28f, 65f, p)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
