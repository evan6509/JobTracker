package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class SelectiveSyncTest {
    @Test fun selectedFieldsExcludePrivateCostsAndPreserveReceiverDetails() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName = "sync_source_${UUID.randomUUID()}"
        val targetName = "sync_target_${UUID.randomUUID()}"
        fun scoped(name: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val sourceContext = scoped(sourceName)
        val targetContext = scoped(targetName)
        try {
            val source = JobStore(sourceContext)
            val target = JobStore(targetContext)
            val selected = Job(title = "New name", description = "Sender details", address = "Private address",
                updatedAt = 5000L)
            val omitted = Job(title = "Other job", updatedAt = 5000L)
            source.saveJobs(listOf(selected, omitted))
            source.saveCostsAt(selected.id, listOf(CostItem("Secret invoice", "99")), 5000L)
            val earlier = selected.copy(title = "Old name", description = "Earlier details",
                address = "Keep this address", updatedAt = 1000L)
            target.saveJobs(listOf(earlier))
            target.saveJobs(listOf(earlier.copy(description = "Keep these details", updatedAt = 9000L)))
            target.saveCostsAt(selected.id, listOf(CostItem("Existing cost", "12")), 1000L)
            val selection = SyncSelection(jobs = mapOf(selected.id to setOf(SyncCategory.NAME)))
            val archive = SyncArchive.create(sourceContext, source, selection)
            try {
                ZipFile(archive).use { zip ->
                    val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                    val record = manifest.getJSONArray("jobs").getJSONObject(0)
                    assertEquals(1, manifest.getJSONArray("jobs").length())
                    assertEquals(0, manifest.getJSONArray("drafts").length())
                    assertFalse(record.has("costs"))
                    assertFalse(record.has("photos"))
                    assertFalse(record.getJSONObject("job").has("address"))
                    assertFalse(record.getJSONObject("job").has("description"))
                    assertFalse(manifest.toString().contains("Secret invoice"))
                    assertFalse(manifest.toString().contains("Other job"))
                }
                SyncArchive.merge(targetContext, target, archive)
                val merged = target.jobs().single()
                assertEquals("New name", merged.title)
                assertEquals("Keep these details", merged.description)
                assertEquals("Keep this address", merged.address)
                assertEquals("Existing cost", target.costs(selected.id).single().description)
            } finally { archive.delete() }
        } finally {
            base.getSharedPreferences(sourceName, Context.MODE_PRIVATE).edit().clear().commit()
            base.getSharedPreferences(targetName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun costsTransferOnlyWhenSelected() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName = "sync_source_${UUID.randomUUID()}"
        val targetName = "sync_target_${UUID.randomUUID()}"
        fun scoped(name: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val sourceContext = scoped(sourceName)
        val targetContext = scoped(targetName)
        try {
            val job = Job(title = "Shared job", updatedAt = 5000L)
            val source = JobStore(sourceContext)
            val target = JobStore(targetContext)
            source.saveJobs(listOf(job))
            source.saveCostsAt(job.id, listOf(CostItem("Materials", "99")), 5000L)
            val archive = SyncArchive.create(sourceContext, source,
                SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.NAME, SyncCategory.COSTS))))
            try {
                SyncArchive.merge(targetContext, target, archive)
                assertEquals("Materials", target.costs(job.id).single().description)
                assertTrue(target.jobs().any { it.id == job.id })
            } finally { archive.delete() }
        } finally {
            base.getSharedPreferences(sourceName, Context.MODE_PRIVATE).edit().clear().commit()
            base.getSharedPreferences(targetName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
