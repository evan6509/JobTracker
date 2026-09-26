package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JobPhotosTest {
    @Test fun capturedPhotoAttachesToOnlyItsJobOrDraftAndPreservesDraftStep() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "photo_test_${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val photo = File(File(base.filesDir, "photos").apply { mkdirs() }, "test_${UUID.randomUUID()}.jpg").apply { writeText("fixture") }
        val outside = File(base.cacheDir, "test_${UUID.randomUUID()}.jpg").apply { writeText("fixture") }
        try {
            val store = JobStore(context)
            val job = Job(title = "Target", state = Job.ACTIVE)
            val other = Job(title = "Other job")
            val draft = Job(title = "Draft")
            store.saveJobs(listOf(job, other))
            store.saveCosts(job.id, listOf(CostItem("Materials", "10")))
            store.saveDraft(draft)
            store.saveDraftStep(draft.id, 6)
            assertNotNull(attachCapturedPhoto(context, store, job.id, photo.path))
            assertEquals(listOf(photo.path), JobStore(context).jobs().first { it.id == job.id }.photos)
            assertEquals(Job.ACTIVE, store.jobs().first { it.id == job.id }.state)
            assertEquals(other, store.jobs().first { it.id == other.id })
            assertEquals("Materials", store.costs(job.id).single().description)
            attachCapturedPhoto(context, store, job.id, photo.path)
            assertEquals(1, store.jobs().first { it.id == job.id }.photos.size)
            assertNotNull(attachCapturedPhoto(context, store, draft.id, photo.path))
            assertEquals(listOf(photo.path), store.drafts().single().photos)
            assertEquals(6, store.draftStep(draft.id))
            assertNull(attachCapturedPhoto(context, store, other.id, outside.path))
            store.recycle(job.id, draft = false)
            assertNull(attachCapturedPhoto(context, store, job.id, photo.path))
            assertEquals(other, store.jobs().single())
        } finally {
            photo.delete(); outside.delete()
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun portraitAndMirroredPhotosDisplayUsingTheirOrientation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "orientation_test_${UUID.randomUUID()}.jpg")
        val source = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        for (x in 0 until 80) for (y in 0 until 40) source.setPixel(x, y, if (x < 40) Color.RED else Color.GREEN)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            fun orientation(value: Int) {
                ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, value.toString()); saveAttributes() }
            }
            orientation(ExifInterface.ORIENTATION_ROTATE_90)
            sampledBitmap(file.path)!!.let { bitmap ->
                assertEquals(40, bitmap.width)
                assertEquals(80, bitmap.height)
                assertTrue(Color.red(bitmap.getPixel(20, 10)) > 200)
                assertTrue(Color.green(bitmap.getPixel(20, 70)) > 200)
                bitmap.recycle()
            }
            orientation(ExifInterface.ORIENTATION_ROTATE_270)
            sampledBitmap(file.path)!!.let { bitmap ->
                assertTrue(Color.green(bitmap.getPixel(20, 10)) > 200)
                assertTrue(Color.red(bitmap.getPixel(20, 70)) > 200)
                bitmap.recycle()
            }
            orientation(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
            sampledBitmap(file.path)!!.let { bitmap ->
                assertEquals(80, bitmap.width)
                assertTrue(Color.green(bitmap.getPixel(10, 20)) > 200)
                bitmap.recycle()
            }
        } finally { source.recycle(); file.delete() }
    }
}
