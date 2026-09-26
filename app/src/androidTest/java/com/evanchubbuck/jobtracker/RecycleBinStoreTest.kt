package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class RecycleBinStoreTest {
    private fun isolated(test: (Context, JobStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "recycle_test_${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try { test(context, JobStore(context)) }
        finally { base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun persistedJobRestoresDetailsCostsAndPhotosWithoutReplacingActiveJob() = isolated { context, store ->
        val job = Job(title = "Saved job", state = Job.ACTIVE, address = "123 Test Street",
            description = "Keep details", workers = listOf(Person("Worker", "608-555-0142", "Paint")),
            inventory = listOf(InventoryItem("Paint", "2", "Blue")), photos = listOf("photo-path"), updatedAt = 1000)
        val costs = listOf(CostItem("Materials", "19.99"))
        store.saveJobs(listOf(job))
        store.saveCostsAt(job.id, costs, 1234)
        assertTrue(store.recycle(job.id, draft = false))
        assertFalse(store.recycle(job.id, draft = false))
        assertTrue(store.jobs().isEmpty())
        assertTrue(store.costs(job.id).isEmpty())

        val reopened = JobStore(context)
        val entry = reopened.recycled().single()
        assertEquals(job, entry.job)
        assertEquals(costs, entry.costs)
        val otherActive = Job(title = "Other active", state = Job.ACTIVE, priority = 5)
        reopened.saveJobs(listOf(otherActive))
        assertEquals(RecycleRestoreResult.RESTORED, reopened.restoreRecycled(entry.id))
        val restored = reopened.jobs().first { it.id == job.id }
        assertEquals(job.copy(state = Job.PLANNED, priority = 6, updatedAt = restored.updatedAt), restored)
        assertEquals(otherActive, reopened.jobs().first { it.id == otherActive.id })
        assertEquals(costs, reopened.costs(job.id))
        assertEquals(1234L, reopened.costsUpdatedAt(job.id))
        assertTrue(reopened.recycled().isEmpty())
    }

    @Test fun legacyDraftRestoresProgressAndHonorsThreeDraftLimit() = isolated { context, store ->
        val draft = Job(title = "Legacy draft", photos = listOf("draft-photo"))
        val prefs = context.getSharedPreferences("job_tracker", Context.MODE_PRIVATE)
        prefs.edit().putString("draft", draft.toJson().toString()).putInt("draft_step", 6).commit()
        assertTrue(store.recycle(draft.id, draft = true))
        val entry = JobStore(context).recycled().single()
        assertEquals(6, entry.step)
        assertFalse(prefs.contains("draft"))
        val others = (1..3).map { Job(title = "Other draft $it") }
        others.forEach { store.saveDraft(it) }
        assertEquals(RecycleRestoreResult.DRAFT_LIMIT, store.restoreRecycled(entry.id))
        assertEquals(3, store.drafts().size)
        assertEquals(entry, store.recycled().single())
        store.recycle(others.first().id, draft = true)
        assertEquals(RecycleRestoreResult.RESTORED, store.restoreRecycled(entry.id))
        assertEquals(draft, store.drafts().first { it.id == draft.id })
        assertEquals(6, store.draftStep(draft.id))
        assertEquals(3, store.drafts().size)
    }

    @Test fun duplicateSyncCopyCannotOverwriteLiveJobAndEachBinCopyKeepsItsStamps() = isolated { _, store ->
        val original = Job(title = "Original", updatedAt = 1000)
        store.saveJobs(listOf(original))
        store.saveCostsAt(original.id, listOf(CostItem("Original costs", "10")), 1000)
        store.recycle(original.id, draft = false)
        val first = store.recycled().single()
        val synced = original.copy(title = "Synced version", updatedAt = 9000)
        store.saveJobs(listOf(synced))
        store.saveCostsAt(original.id, listOf(CostItem("New costs", "20")), 9000)
        assertEquals(RecycleRestoreResult.ALREADY_EXISTS, store.restoreRecycled(first.id))
        assertEquals(synced, store.jobs().single())
        assertEquals("New costs", store.costs(original.id).single().description)
        assertEquals(first, store.recycled().single())
        store.recycle(synced.id, draft = false)
        val second = store.recycled().first { it.id != first.id }
        assertEquals(RecycleRestoreResult.RESTORED, store.restoreRecycled(first.id))
        assertEquals(1000L, store.fieldStamps(store.jobs().single())[SyncCategory.NAME])
        store.deleteRecycled(setOf(second.id))
        assertEquals("Original", store.jobs().single().title)
        assertEquals("Original costs", store.costs(original.id).single().description)
        assertEquals(1000L, store.fieldStamps(store.jobs().single())[SyncCategory.NAME])
    }

    @Test fun permanentDeletionKeepsPhotosUsedByLiveJobsAndOtherBinEntries() = isolated { context, store ->
        val folder = File(context.filesDir, "photos").apply { mkdirs() }
        val files = (1..3).map { File(folder, "recycle_test_${UUID.randomUUID()}").apply { writeText("photo") } }
        val outside = File(context.cacheDir, "recycle_test_${UUID.randomUUID()}").apply { writeText("other") }
        try {
            val deleted = Job(photos = files.map { it.path } + outside.path)
            val remainingBinJob = Job(photos = listOf(files[1].path))
            val liveDraft = Job(photos = listOf(files[0].path))
            store.saveJobs(listOf(deleted, remainingBinJob))
            store.saveDraft(liveDraft)
            store.recycle(deleted.id, draft = false)
            store.recycle(remainingBinJob.id, draft = false)
            assertTrue(files.all { it.exists() })
            val removed = store.deleteRecycled(setOf(store.recycled().first { it.job.id == deleted.id }.id))
            val retained = (store.jobs() + store.drafts() + store.recycled().map { it.job }).flatMap { it.photos }.toSet()
            deleteUnreferencedJobPhotos(context, removed.flatMap { it.job.photos }, retained)
            assertTrue(files[0].exists())
            assertTrue(files[1].exists())
            assertFalse(files[2].exists())
            assertTrue(outside.exists())
            val emptied = store.deleteRecycled(store.recycled().map { it.id }.toSet())
            deleteUnreferencedJobPhotos(context, emptied.flatMap { it.job.photos }, store.drafts().flatMap { it.photos }.toSet())
            assertFalse(files[1].exists())
            assertTrue(files[0].exists())
            assertTrue(store.recycled().isEmpty())
        } finally { files.forEach { it.delete() }; outside.delete() }
    }

    @Test fun recycledJobsAndDraftsAreExcludedFromSyncEvenIfSelected() = isolated { context, store ->
        val job = Job(title = "Deleted private job")
        val draft = Job(title = "Deleted draft")
        store.saveJobs(listOf(job))
        store.saveCosts(job.id, listOf(CostItem("Deleted costs", "100")))
        store.saveDraft(draft)
        store.recycle(job.id, draft = false)
        store.recycle(draft.id, draft = true)
        val archive = SyncArchive.create(context, store, SyncSelection(
            jobs = mapOf(job.id to SyncCategory.entries.toSet()), drafts = mapOf(draft.id to SyncCategory.draftDefaults)))
        try {
            ZipFile(archive).use { zip ->
                val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
                assertEquals(0, manifest.getJSONArray("jobs").length())
                assertEquals(0, manifest.getJSONArray("drafts").length())
                assertFalse(manifest.toString().contains("Deleted"))
            }
        } finally { archive.delete() }
    }
}
