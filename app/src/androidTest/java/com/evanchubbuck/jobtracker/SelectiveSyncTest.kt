package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SelectiveSyncTest {
    private fun phones(test: (Context, JobStore, Context, JobStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val names = List(2) { "sync_test_${UUID.randomUUID()}" }
        val folders = names.map { File(base.cacheDir, it).apply { mkdirs() } }
        val contexts = names.map { name -> object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
            override fun getFilesDir() = File(folders[names.indexOf(name)], "files").apply { mkdirs() }
            override fun getCacheDir() = File(folders[names.indexOf(name)], "cache").apply { mkdirs() }
        } }
        try { test(contexts[0], JobStore(contexts[0]), contexts[1], JobStore(contexts[1])) }
        finally {
            names.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
            folders.forEach { it.deleteRecursively() }
        }
    }

    private fun manifest(archive: java.io.File): JSONObject = ZipFile(archive).use { zip ->
        JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
    }

    private fun transfer(context: Context, source: JobStore, targetContext: Context, target: JobStore,
                         selection: SyncSelection): SyncResult {
        val archive = SyncArchive.create(context, source, selection)
        return try { SyncArchive.merge(targetContext, target, archive) } finally { archive.delete() }
    }

    private fun photo(context: Context, name: String, content: String): String =
        File(File(context.filesDir, "photos").apply { mkdirs() }, name).apply { writeText(content) }.absolutePath

    @Test fun selectedFieldsExcludeMaterialsAndPreserveReceiverDetails() = phones { sc, source, tc, target ->
        val selected = Job(title = "New name", description = "Sender details", address = "Private address", updatedAt = 5000,
            inventory = listOf(InventoryItem("Secret purchase", price = "99")))
        source.saveJobs(listOf(selected, Job(title = "Other job")))
        val earlier = selected.copy(title = "Old name", description = "Earlier details", address = "Keep address", updatedAt = 1000,
            inventory = listOf(InventoryItem("Existing supplies", price = "12")))
        target.saveJobs(listOf(earlier))
        target.saveJobs(listOf(earlier.copy(description = "Keep these details", updatedAt = 9000)))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf(selected.id to setOf(SyncCategory.NAME))))
        try {
            val data = manifest(archive)
            assertEquals(SYNC_FORMAT, data.getString("format"))
            assertEquals(1, data.getJSONArray("jobs").length())
            val json = data.getJSONArray("jobs").getJSONObject(0).getJSONObject("job")
            assertFalse(json.has("inventory"))
            assertFalse(json.has("materialPrices"))
            assertFalse(json.has("address"))
            assertFalse(data.toString().contains("Secret purchase"))
            assertFalse(data.toString().contains("Other job"))
            SyncArchive.merge(tc, target, archive)
            val merged = target.jobs().single()
            assertEquals("New name", merged.title)
            assertEquals("Keep these details", merged.description)
            assertEquals("Keep address", merged.address)
            assertEquals(earlier.inventory, merged.inventory)
        } finally { archive.delete() }
    }

    @Test fun materialsWithoutPricesPreserveMatchingLocalPricesAndDoNotLeakPrices() = phones { sc, source, tc, target ->
        val material = InventoryItem("Tiles", "24", "New description", "99.99")
        val job = Job(title = "Shared job", updatedAt = 5000, inventory = listOf(material, InventoryItem("Delivery", price = "9.87")))
        source.saveJobs(listOf(job))
        target.saveJobs(listOf(job.copy(updatedAt = 1000, inventory = listOf(material.copy(notes = "Old", price = "12.34")))))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.INVENTORY))))
        try {
            val data = manifest(archive)
            assertFalse(data.toString().contains("99.99"))
            assertFalse(data.toString().contains("9.87"))
            val json = data.getJSONArray("jobs").getJSONObject(0).getJSONObject("job")
            assertFalse(json.has("materialPrices"))
            assertFalse(json.getJSONArray("inventory").getJSONObject(0).has("price"))
            SyncArchive.merge(tc, target, archive)
            val items = target.jobs().single().inventory
            assertEquals("New description", items[0].notes)
            assertEquals("12.34", items[0].price)
            assertEquals("", items[1].price)
        } finally { archive.delete() }
    }

    @Test fun pricesTransferOnlyWhenSelectedAndBlankPriceCanClearAnOldPrice() = phones { sc, source, tc, target ->
        val job = Job(title = "Shared job", updatedAt = 5000,
            inventory = listOf(InventoryItem("Tiles", "24", "Blue", "99"), InventoryItem("Delivery")))
        source.saveJobs(listOf(job))
        target.saveJobs(listOf(job.copy(updatedAt = 1000, inventory = job.inventory.map { it.copy(price = "12") })))
        val archive = SyncArchive.create(sc, source,
            SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.INVENTORY, SyncCategory.COSTS))))
        try {
            assertTrue(manifest(archive).getJSONArray("jobs").getJSONObject(0).getJSONObject("job").has("materialPrices"))
            SyncArchive.merge(tc, target, archive)
            assertEquals(job.inventory, target.jobs().single().inventory)
        } finally { archive.delete() }
    }

    @Test fun newerLocalPricesSurviveOlderRemotePricesWhileMaterialDetailsUpdate() = phones { sc, source, tc, target ->
        val material = InventoryItem("Tiles", "24", "Old", "10")
        val old = Job(updatedAt = 1000, inventory = listOf(material))
        source.saveJobs(listOf(old))
        source.saveJobs(listOf(old.copy(updatedAt = 5000, inventory = listOf(material.copy(notes = "New", price = "20")))))
        target.saveJobs(listOf(old))
        target.saveJobs(listOf(old.copy(updatedAt = 9000, inventory = listOf(material.copy(price = "30")))))
        val archive = SyncArchive.create(sc, source,
            SyncSelection(jobs = mapOf(old.id to setOf(SyncCategory.INVENTORY, SyncCategory.COSTS))))
        try {
            SyncArchive.merge(tc, target, archive)
            val received = target.jobs().single().inventory.single()
            assertEquals("New", received.notes)
            assertEquals("30", received.price)
        } finally { archive.delete() }
    }

    @Test fun selectedDraftsCanSharePricesAndUnselectedJobsStayOut() = phones { sc, source, tc, target ->
        val draft = Job(title = "Draft", updatedAt = 5000, inventory = listOf(InventoryItem("Paint", price = "45.67")))
        source.saveDraft(draft)
        source.saveDraftStep(draft.id, 4)
        source.saveJobs(listOf(Job(title = "Unselected job")))
        val archive = SyncArchive.create(sc, source,
            SyncSelection(drafts = mapOf(draft.id to setOf(SyncCategory.NAME, SyncCategory.INVENTORY, SyncCategory.COSTS))))
        try {
            assertEquals(0, manifest(archive).getJSONArray("jobs").length())
            SyncArchive.merge(tc, target, archive)
            assertEquals(draft.inventory, target.drafts().single().inventory)
            assertEquals(4, target.draftStep(draft.id))
            assertTrue(target.jobs().isEmpty())
        } finally { archive.delete() }
    }

    @Test fun promotedDraftKeepsOmittedLocalFieldsAndCannotReturnAsAnotherDraft() = phones { sc, source, tc, target ->
        val draft = Job(title = "Shared draft", updatedAt = 1000, clients = listOf(Person("Client")))
        source.saveDraft(draft)
        transfer(sc, source, tc, target, SyncSelection(drafts = mapOf(draft.id to setOf(SyncCategory.NAME, SyncCategory.CLIENTS))))
        target.saveDraft(target.drafts().single().copy(description = "Local private work", updatedAt = 9000))
        assertTrue(source.finishDraft(draft.copy(title = "Finished job", updatedAt = 5000)))
        transfer(sc, source, tc, target, SyncSelection(jobs = mapOf(draft.id to setOf(SyncCategory.NAME, SyncCategory.CLIENTS))))
        assertTrue(target.drafts().isEmpty())
        assertEquals("Finished job", target.jobs().single().title)
        assertEquals("Local private work", target.jobs().single().description)
        assertFalse(target.finishDraft(draft))
        source.saveDraft(draft.copy(title = "Stale peer draft", updatedAt = 10000))
        transfer(sc, source, tc, target, SyncSelection(drafts = mapOf(draft.id to SyncCategory.draftDefaults)))
        assertTrue(target.drafts().isEmpty())
        assertEquals("Finished job", target.jobs().single().title)
    }

    @Test fun finishDraftPreservesExistingSavedJobAndRemovesStaleDraftAtomically() = phones { _, _, _, target ->
        val draft = Job(title = "Old draft", updatedAt = 1000)
        target.saveDraft(draft)
        val saved = draft.copy(title = "Newer saved job", description = "Keep work", updatedAt = 9000)
        target.saveJobs(listOf(saved))
        assertTrue(target.finishDraft(draft.copy(title = "Stale overwrite", updatedAt = 5000)))
        assertEquals(listOf(saved), target.jobs())
        assertTrue(target.drafts().isEmpty())
        assertFalse(target.finishDraft(draft))
    }

    @Test fun olderDuplicateSavedIdsReadAsNewestJobWithoutChangingIdOrder() = phones { _, _, tc, target ->
        val first = Job(title = "Old copy", updatedAt = 1000)
        val other = Job(title = "Other job", updatedAt = 5000)
        val latest = first.copy(title = "Newest copy", updatedAt = 9000)
        tc.getSharedPreferences("job_tracker", Context.MODE_PRIVATE).edit().putString("jobs",
            org.json.JSONArray().put(first.toJson()).put(other.toJson()).put(latest.toJson()).toString()).commit()
        assertEquals(listOf(latest, other), target.jobs())
        try {
            target.saveJobs(listOf(first, latest))
            fail("Duplicate IDs must not be saved again")
        } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(latest, other), target.jobs())
    }

    @Test fun unchangedPhotosPreservePathsForEqualAndNewerRemoteTimestamps() = phones { sc, source, tc, target ->
        val job = Job(updatedAt = 1000, photos = listOf(photo(sc, "source.jpg", "same photo")))
        source.saveJobs(listOf(job))
        val selection = SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.PHOTOS)))
        assertEquals(1, transfer(sc, source, tc, target, selection).photos)
        val path = target.jobs().single().photos.single()
        assertEquals(0, transfer(sc, source, tc, target, selection).photos)
        assertEquals(path, target.jobs().single().photos.single())
        source.saveJobs(listOf(job.copy(photos = listOf(photo(sc, "renamed.jpg", "same photo")), updatedAt = 5000)))
        assertEquals(0, transfer(sc, source, tc, target, selection).photos)
        assertEquals(path, target.jobs().single().photos.single())
        assertEquals(1, File(tc.filesDir, "photos").listFiles()!!.size)
    }

    @Test fun sameStampPhotoTransferRecoversMissingLocalFileAndNewerEmptyListClearsMissingRefs() = phones { sc, source, tc, target ->
        val job = Job(updatedAt = 1000, photos = listOf(photo(sc, "source.jpg", "photo")))
        source.saveJobs(listOf(job))
        val selection = SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.PHOTOS)))
        transfer(sc, source, tc, target, selection)
        File(target.jobs().single().photos.single()).delete()
        assertEquals(1, transfer(sc, source, tc, target, selection).photos)
        assertTrue(File(target.jobs().single().photos.single()).isFile)
        File(target.jobs().single().photos.single()).delete()
        source.saveJobs(listOf(job.copy(photos = emptyList(), updatedAt = 5000)))
        transfer(sc, source, tc, target, selection)
        assertTrue(target.jobs().single().photos.isEmpty())
    }

    @Test fun changedPhotosRemoveOnlySupersededManagedFiles() = phones { sc, source, tc, target ->
        val oldPath = photo(tc, "old.jpg", "old")
        val outside = File(tc.cacheDir, "outside.jpg").apply { writeText("outside") }
        val job = Job(updatedAt = 1000, photos = listOf(oldPath, outside.absolutePath))
        target.saveJobs(listOf(job))
        source.saveJobs(listOf(job.copy(updatedAt = 5000, photos = listOf(photo(sc, "new.jpg", "new")))))
        transfer(sc, source, tc, target, SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.PHOTOS))))
        assertFalse(File(oldPath).exists())
        assertTrue(outside.exists())
        assertEquals("new", File(target.jobs().single().photos.single()).readText())
    }

    @Test fun replacedPhotosRemainWhenAnotherJobDraftOrRecycleEntryUsesThem() {
        listOf("job", "draft", "recycle").forEach { owner -> phones { sc, source, tc, target ->
            val oldPath = photo(tc, "shared.jpg", "shared")
            val job = Job(updatedAt = 1000, photos = listOf(oldPath))
            val other = Job(updatedAt = 1000, photos = listOf(oldPath))
            target.saveJobs(listOf(job) + if (owner == "draft") emptyList() else listOf(other))
            if (owner == "draft") target.saveDraft(other)
            if (owner == "recycle") assertTrue(target.recycle(other.id, draft = false))
            source.saveJobs(listOf(job.copy(updatedAt = 5000, photos = listOf(photo(sc, "new.jpg", "new")))))
            transfer(sc, source, tc, target, SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.PHOTOS))))
            assertTrue("The $owner still uses the old photo", File(oldPath).isFile)
        } }
    }

    @Test fun failedDiskCommitDoesNotDeletePhotosAlreadyReferencedInMemory() = phones { sc, source, tc, _ ->
        val prefs = tc.getSharedPreferences("job_tracker", Context.MODE_PRIVATE)
        var failCommit = false
        val context = object : ContextWrapper(tc) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    val editor = prefs.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { editor.putInt(key, value) }
                        override fun remove(key: String): SharedPreferences.Editor = apply { editor.remove(key) }
                        override fun commit(): Boolean {
                            editor.commit()
                            return !failCommit
                        }
                    }
                }
            }
        }
        val target = JobStore(context)
        val job = Job(updatedAt = 1000, photos = listOf(photo(sc, "source.jpg", "photo")))
        source.saveJobs(listOf(job))
        failCommit = true
        try {
            transfer(sc, source, context, target, SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.PHOTOS))))
            fail("Expected failed persistence")
        } catch (_: IllegalStateException) { }
        assertTrue(File(target.jobs().single().photos.single()).isFile)
    }

    @Test fun pricesSelectedAfterMaterialOnlyTransferFillUnknownPricesAndConverge() = phones { sc, source, tc, target ->
        val tiles = InventoryItem("Tiles", price = "10")
        val delivery = InventoryItem("Delivery", price = "20")
        val old = Job(updatedAt = 1000, inventory = listOf(tiles))
        source.saveJobs(listOf(old)); target.saveJobs(listOf(old))
        source.saveJobs(listOf(old.copy(updatedAt = 5000, inventory = listOf(tiles, delivery))))
        target.saveJobs(listOf(old.copy(updatedAt = 9000, inventory = listOf(tiles.copy(price = "30")))))
        transfer(sc, source, tc, target, SyncSelection(jobs = mapOf(old.id to setOf(SyncCategory.INVENTORY))))
        assertEquals(listOf("30", ""), target.jobs().single().inventory.map { it.price })
        val selection = SyncSelection(jobs = mapOf(old.id to setOf(SyncCategory.INVENTORY, SyncCategory.COSTS)))
        transfer(sc, source, tc, target, selection)
        assertEquals(listOf("30", "20"), target.jobs().single().inventory.map { it.price })
        assertEquals(0, transfer(sc, source, tc, target, selection).updated)
        transfer(tc, target, sc, source, selection)
        assertEquals(target.jobs().single().inventory, source.jobs().single().inventory)
        assertEquals(0, transfer(tc, target, sc, source, selection).updated)
    }

    @Test fun newerMaterialAdditionDoesNotOverwriteAnUnrelatedNewerExistingPrice() = phones { sc, source, tc, target ->
        val tiles = InventoryItem("Tiles", price = "10")
        val old = Job(updatedAt = 1000, inventory = listOf(tiles))
        source.saveJobs(listOf(old)); target.saveJobs(listOf(old))
        target.saveJobs(listOf(old.copy(updatedAt = 5000, inventory = listOf(tiles.copy(price = "30")))))
        source.saveJobs(listOf(old.copy(updatedAt = 9000, inventory = listOf(tiles, InventoryItem("Delivery", price = "20")))))
        transfer(sc, source, tc, target, SyncSelection(jobs = mapOf(old.id to setOf(SyncCategory.INVENTORY, SyncCategory.COSTS))))
        assertEquals(listOf("30", "20"), target.jobs().single().inventory.map { it.price })
    }

    @Test fun olderV3ArchivesWithoutPerMaterialDatesStillTransferNewPrices() = phones { sc, source, tc, target ->
        val tiles = InventoryItem("Tiles", price = "10")
        val old = Job(updatedAt = 1000, inventory = listOf(tiles))
        source.saveJobs(listOf(old)); target.saveJobs(listOf(old))
        source.saveJobs(listOf(old.copy(updatedAt = 5000, inventory = listOf(tiles, InventoryItem("Delivery", price = "20")))))
        target.saveJobs(listOf(old.copy(updatedAt = 9000, inventory = listOf(tiles.copy(price = "30")))))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf(old.id to setOf(SyncCategory.INVENTORY, SyncCategory.COSTS))))
        val legacy = File.createTempFile("legacy-v3-", ".zip", sc.cacheDir)
        try {
            ZipFile(archive).use { zip -> ZipOutputStream(legacy.outputStream()).use { output ->
                zip.entries().asSequence().forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "manifest.json") {
                        val data = manifest(archive)
                        val jobs = data.getJSONArray("jobs")
                        for (index in 0 until jobs.length()) jobs.getJSONObject(index).remove("priceUpdated")
                        output.write(data.toString().toByteArray(Charsets.UTF_8))
                    } else zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            } }
            SyncArchive.merge(tc, target, legacy)
            assertEquals(listOf("30", "20"), target.jobs().single().inventory.map { it.price })
        } finally { archive.delete(); legacy.delete() }
    }
}
