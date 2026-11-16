package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.ContextWrapper
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class StoreRegressionTest {
    private fun phones(test: (Context, JobStore, Context, JobStore) -> Unit) {
        val base = RuntimeEnvironment.getApplication()
        val folders = List(2) { File(base.cacheDir, "regression_${UUID.randomUUID()}").apply { mkdirs() } }
        val contexts = folders.map { folder -> object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(folder.name, mode)
            override fun getFilesDir() = File(folder, "files").apply { mkdirs() }
            override fun getCacheDir() = File(folder, "cache").apply { mkdirs() }
        } }
        try { test(contexts[0], JobStore(contexts[0]), contexts[1], JobStore(contexts[1])) }
        finally {
            contexts.forEach { it.getSharedPreferences("job_tracker", 0).edit().clear().commit() }
            folders.forEach { it.deleteRecursively() }
        }
    }
    private fun transfer(sc: Context, source: JobStore, tc: Context, target: JobStore) {
        val selection = SyncSelection(jobs = source.jobs().associate { it.id to SyncCategory.entries.toSet() })
        val archive = SyncArchive.create(sc, source, selection)
        try { SyncArchive.merge(tc, target, archive) } finally { archive.delete() }
    }

    @Test fun commitWithoutSyncResultThenStaleScreenEditAndReorderKeepReceivedJobsAndFields() = phones { sc, source, tc, target ->
        val job = Job(id = "existing", title = "Old", address = "Old site", updatedAt = 1000)
        target.saveJobs(listOf(job))
        val stale = target.jobs()
        source.saveJobs(listOf(job.copy(address = "Received site", updatedAt = 2000), Job(id = "received", title = "Received")))
        transfer(sc, source, tc, target)
        // The screen has not received a completion callback and still has its pre-sync list.
        target.applyJobChanges(stale, listOf(job.copy(title = "Local edit", updatedAt = 3000, priority = 5)))
        val saved = target.jobs().associateBy { it.id }
        assertEquals(setOf("existing", "received"), saved.keys)
        assertEquals("Local edit", saved.getValue("existing").title)
        assertEquals("Received site", saved.getValue("existing").address)
        assertEquals(5, saved.getValue("existing").priority)
    }

    @Test fun leaveSyncBeforeCommitRollsBackJobsAndNewPhotos() = phones { sc, source, tc, target ->
        val photo = File(File(sc.filesDir, "photos").apply { mkdirs() }, "photo.jpg").apply { writeText("image") }
        source.saveJobs(listOf(Job(id = "incoming", photos = listOf(photo.path))))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf("incoming" to SyncCategory.jobDefaults)))
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            runBlocking {
                val merging = launch(Dispatchers.Default) {
                    val context = currentCoroutineContext()
                    SyncArchive.merge(tc, target, archive) {
                        paused.countDown()
                        check(resume.await(5, TimeUnit.SECONDS))
                        context.ensureActive()
                    }
                }
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                merging.cancel()
                resume.countDown()
                merging.join()
            }
            assertTrue(target.jobs().isEmpty())
            assertTrue(File(tc.filesDir, "photos").listFiles().orEmpty().isEmpty())
        } finally { resume.countDown(); archive.delete() }
    }

    @Test fun simultaneousLocalWriteWaitsForMergeThenRebasesOnCommittedSnapshot() = phones { sc, source, tc, target ->
        val old = Job(id = "same", title = "Old", updatedAt = 1000)
        target.saveJobs(listOf(old))
        source.saveJobs(listOf(old.copy(address = "Remote", updatedAt = 2000), Job(id = "new")))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = source.jobs().associate { it.id to SyncCategory.jobDefaults }))
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            runBlocking {
                val merge = async(Dispatchers.Default) { SyncArchive.merge(tc, target, archive) {
                    paused.countDown(); check(resume.await(5, TimeUnit.SECONDS))
                } }
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                val edit = async(Dispatchers.Default) {
                    JobStore(tc).applyJobChanges(listOf(old), listOf(old.copy(title = "Local", updatedAt = 3000)))
                }
                resume.countDown()
                merge.await(); edit.await()
            }
            assertEquals(2, target.jobs().size)
            assertEquals("Local", target.jobs().first { it.id == "same" }.title)
            assertEquals("Remote", target.jobs().first { it.id == "same" }.address)
        } finally { resume.countDown(); archive.delete() }
    }

    @Test fun futureRemoteRevisionsCannotUndoLaterLocalFieldAndPriceEdits() = phones { sc, source, tc, target ->
        val item = InventoryItem(id = "material", name = "Tiles", price = "10")
        val future = Job(id = "future", title = "Peer", updatedAt = 5000, inventory = listOf(item))
        source.saveJobs(listOf(future))
        transfer(sc, source, tc, target)
        val local = target.jobs().single().copy(title = "Local", updatedAt = 1000, inventory = listOf(item.copy(price = "25")))
        target.saveJobs(listOf(local))
        assertTrue(target.fieldStamps(local).getValue(SyncCategory.NAME) > 5000)
        assertTrue(target.materialPriceStamps(local).getValue(item.id) > 5000)
        transfer(sc, source, tc, target)
        transfer(tc, target, sc, source)
        for (store in listOf(source, target)) {
            assertEquals("Local", store.jobs().single().title)
            assertEquals("25", store.jobs().single().inventory.single().price)
        }
        target.saveJobs(listOf(target.jobs().single().copy(title = "Next", updatedAt = 500)))
        assertTrue(target.fieldStamps(target.jobs().single()).getValue(SyncCategory.NAME) > 5001)
    }

    @Test fun legacyRecycleMigrationFillsEveryMissingStampAndPreservesExplicitOnes() = phones { sc, source, tc, _ ->
        val prefs = tc.getSharedPreferences("job_tracker", 0)
        for (explicit in listOf(false, true)) {
            val job = Job(id = "legacy", title = "Restored name", address = "Restored site", updatedAt = 1000)
            val entry = RecycledJob(job, false, id = "bin").toJson().put("costs", JSONArray()
                .put(JSONObject().put("name", "Tiles").put("amount", "42"))).put("costUpdatedAt", 2000)
            if (explicit) entry.put("fieldUpdated", JSONObject().put(SyncCategory.WORK.name, 1500))
            else entry.remove("fieldUpdated")
            prefs.edit().clear().putString("recycle_bin", JSONArray().put(entry).toString()).commit()
            val store = JobStore(tc)
            assertEquals(RecycleRestoreResult.RESTORED, store.restoreRecycled("bin"))
            val restored = store.jobs().single()
            val stamps = store.fieldStamps(restored)
            assertEquals(1000L, stamps.getValue(SyncCategory.NAME))
            assertEquals(if (explicit) 1500L else 1000L, stamps.getValue(SyncCategory.WORK))
            assertEquals(2000L, stamps.getValue(SyncCategory.COSTS))
            source.saveJobs(listOf(job.copy(title = "Stale name", address = "Stale site", updatedAt = 500)), trackChanges = false)
            source.saveFieldStamps(job.id, SyncCategory.entries.associateWith { 500L })
            transfer(sc, source, tc, store)
            assertEquals("Restored name", store.jobs().single().title)
            assertEquals("Restored site", store.jobs().single().address)
        }
    }

    @Test fun staleMergeSnapshotRetriesAndPreservesConcurrentEditsAndPhotoOwnership() = phones { sc, source, tc, target ->
        val old = Job(id = "existing", title = "Old", updatedAt = 1000)
        target.saveJobs(listOf(old))
        val photo = File(File(sc.filesDir, "photos").apply { mkdirs() }, "image.jpg").apply { writeText("photo") }
        source.saveJobs(listOf(old.copy(address = "Remote", photos = listOf(photo.path), updatedAt = 2000)))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf(old.id to SyncCategory.jobDefaults)))
        var attempts = 0
        try {
            SyncArchive.merge(tc, target, archive) {
                if (attempts++ == 0) target.saveJobs(listOf(old.copy(title = "Concurrent edit", updatedAt = 3000)))
            }
            assertEquals(2, attempts)
            val saved = target.jobs().single()
            assertEquals("Concurrent edit", saved.title)
            assertEquals("Remote", saved.address)
            assertEquals(1, saved.photos.size)
            assertEquals(saved.photos.toSet(), File(tc.filesDir, "photos").listFiles()!!.map { it.path }.toSet())
        } finally { archive.delete() }
    }

    @Test fun retryKeepsConcurrentFieldAndPriceEditsAgainstNewerPeerAndLaterSync() = phones { sc, source, tc, target ->
        val tiles = InventoryItem(id = "tiles", name = "Tiles", price = "10")
        val paint = InventoryItem(id = "paint", name = "Paint", price = "5")
        val original = Job(id = "shared", title = "Original", inventory = listOf(tiles, paint), updatedAt = 1000)
        target.saveJobs(listOf(original))
        source.saveJobs(listOf(original.copy(title = "Peer name", address = "Peer site", updatedAt = 9000,
            inventory = listOf(tiles.copy(price = "30"), paint.copy(price = "15")))))
        val selection = SyncSelection(jobs = mapOf(original.id to SyncCategory.entries.toSet()))
        val archive = SyncArchive.create(sc, source, selection)
        var attempts = 0
        try {
            SyncArchive.merge(tc, target, archive) {
                if (attempts++ == 0) {
                    target.saveJobs(listOf(original.copy(title = "Local name", updatedAt = 2000,
                        inventory = listOf(tiles.copy(price = "25"), paint))))
                }
            }
            assertEquals(2, attempts)
            SyncArchive.merge(tc, target, archive)
            transfer(tc, target, sc, source)
            for (store in listOf(source, target)) {
                val saved = store.jobs().single()
                assertEquals("Local name", saved.title)
                assertEquals("Peer site", saved.address)
                assertEquals(listOf("25", "15"), saved.inventory.map { it.price })
            }
        } finally { archive.delete() }
    }

    @Test fun retryDoesNotResurrectRecycledRecordsOrResetLocallyChangedDraftStep() = phones { sc, source, tc, target ->
        val job = Job(id = "job", updatedAt = 1000)
        val recycledDraft = Job(id = "removed-draft", updatedAt = 1000)
        val draft = Job(id = "kept-draft", updatedAt = 1000)
        target.saveJobs(listOf(job))
        target.saveDraft(recycledDraft)
        target.saveDraft(draft)
        source.saveJobs(listOf(job))
        source.saveDraft(recycledDraft)
        source.saveDraft(draft.copy(title = "Peer name", updatedAt = 2000))
        source.saveDraftStep(draft.id, 2)
        val archive = SyncArchive.create(sc, source, SyncSelection(
            jobs = mapOf(job.id to SyncCategory.jobDefaults),
            drafts = listOf(recycledDraft, draft).associate { it.id to SyncCategory.draftDefaults }
        ))
        var attempts = 0
        try {
            SyncArchive.merge(tc, target, archive) {
                if (attempts++ == 0) {
                    assertTrue(target.recycle(job.id, draft = false))
                    assertTrue(target.recycle(recycledDraft.id, draft = true))
                    target.saveDraftStep(draft.id, 5)
                }
            }
            assertEquals(2, attempts)
            assertTrue(target.jobs().isEmpty())
            assertEquals(setOf(job.id, recycledDraft.id), target.recycled().map { it.job.id }.toSet())
            assertEquals("Peer name", target.drafts().single().title)
            assertEquals(5, target.draftStep(draft.id))
        } finally { archive.delete() }
    }

    @Test fun invalidIncomingRevisionsLeaveTheStoreIntact() = phones { sc, source, tc, target ->
        val row = InventoryItem(id = "material", name = "Paint", price = "20")
        val incoming = Job(id = "incoming", updatedAt = 1000, inventory = listOf(row))
        val local = Job(id = "local", title = "Keep")
        target.saveJobs(listOf(local))
        source.saveJobs(listOf(incoming))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf(incoming.id to SyncCategory.entries.toSet())))
        val manifest = ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }
        }
        try {
            val revisionFields = listOf(
                "job" to "createdAt",
                "job" to "updatedAt",
                "fieldUpdated" to SyncCategory.NAME.name,
                "fieldUpdated" to SyncCategory.COSTS.name,
                "priceUpdated" to row.id
            )
            for ((section, field) in revisionFields) {
                val payload = JSONObject(manifest)
                payload.getJSONArray("jobs").getJSONObject(0).getJSONObject(section).put(field, Long.MAX_VALUE - 1)
                ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(payload.toString().toByteArray())
                }
                assertThrows(IllegalArgumentException::class.java) { SyncArchive.merge(tc, target, archive) }
                assertEquals(listOf(local), target.jobs())
            }
        } finally { archive.delete() }
    }

    @Test fun savedOutOfRangeRevisionsDoNotPreventEditsOrExport() = phones { sc, source, tc, target ->
        val normal = Job(id = "normal", title = "Original", updatedAt = 1000)
        val invalid = Job(id = "invalid", title = "Old peer", updatedAt = Long.MAX_VALUE,
            inventory = listOf(InventoryItem(id = "material", name = "Paint", price = "20")))
        val prefs = sc.getSharedPreferences("job_tracker", 0)
        prefs.edit()
            .putString("jobs", JSONArray().put(normal.toJson()).put(invalid.toJson()).toString())
            .putString("field_updated_invalid", JSONObject().put(SyncCategory.NAME.name, Long.MAX_VALUE).toString())
            .putString("price_updated_invalid", JSONObject().put("material", Long.MAX_VALUE).toString())
            .commit()
        source.applyJobChanges(listOf(normal), listOf(normal.copy(title = "Updated", updatedAt = 2000)))
        transfer(sc, source, tc, target)
        assertEquals(listOf("Updated", "Old peer"), target.jobs().map { it.title })
        assertEquals("20", target.jobs().last().inventory.single().price)
    }

    @Test fun staleDraftEditorPreservesReceivedDetailsAndAdvancesRevision() = phones { _, _, _, target ->
        val before = Job(id = "draft", title = "Old", address = "Old", updatedAt = 1000)
        target.saveDraft(before)
        target.saveDraft(before.copy(address = "Received", updatedAt = 5000))
        val edited = requireNotNull(target.applyDraftChange(before, before.copy(title = "Local", updatedAt = 500)))
        assertEquals("Received", edited.address)
        assertEquals("Local", edited.title)
        assertTrue(edited.updatedAt > 5000)
        assertTrue(target.fieldStamps(edited).getValue(SyncCategory.NAME) > 5000)
    }

    @Test fun stalePhotoRemovalKeepsNewlySyncedPhotosOnJobsAndDrafts() = phones { sc, source, tc, target ->
        val sourceFolder = File(sc.filesDir, "photos").apply { mkdirs() }
        val targetFolder = File(tc.filesDir, "photos").apply { mkdirs() }
        for (draft in listOf(false, true)) {
            val id = if (draft) "draft" else "job"
            val localPhotos = listOf("A", "B").map { name ->
                File(targetFolder, "$id-$name.jpg").apply { writeText(name) }
            }
            val peerPhoto = File(sourceFolder, "$id-C.jpg").apply { writeText("C") }
            val before = Job(id = id, photos = localPhotos.map { it.path }, updatedAt = 1000)
            val incoming = before.copy(photos = listOf(peerPhoto.path), updatedAt = 2000)
            val selection = mapOf(id to setOf(SyncCategory.PHOTOS))
            if (draft) {
                target.saveDraft(before)
                source.saveDraft(incoming)
            } else {
                target.saveJobs(listOf(before))
                source.saveJobs(listOf(incoming))
            }
            val archive = SyncArchive.create(sc, source,
                if (draft) SyncSelection(drafts = selection) else SyncSelection(jobs = selection))
            try {
                SyncArchive.merge(tc, target, archive)
                assertTrue(localPhotos.none { it.exists() })
                val edit = before.copy(photos = listOf(localPhotos.last().path), updatedAt = 3000)
                val saved = if (draft) {
                    requireNotNull(target.applyDraftChange(before, edit))
                } else {
                    target.applyJobChanges(listOf(before), listOf(edit))
                    target.jobs().single()
                }
                assertEquals("C", File(saved.photos.single()).readText())
            } finally { archive.delete() }
        }
    }

    @Test fun staleMaterialEditsKeepSyncedItemsPropertiesAndPrices() = phones { sc, source, tc, target ->
        val tiles = InventoryItem(id = "tiles", name = "Tiles", quantity = "1", price = "10", notes = "Old")
        val removeLocally = InventoryItem(id = "brush", name = "Brush")
        val removedByPeer = InventoryItem(id = "tape", name = "Tape")
        val peerItem = InventoryItem(id = "paint", name = "Paint", price = "25")
        val localItem = InventoryItem(id = "roller", name = "Roller", price = "5")
        val before = Job(id = "shared", inventory = listOf(tiles, removeLocally, removedByPeer), updatedAt = 1000)
        target.saveJobs(listOf(before))
        val receivedTiles = tiles.copy(price = "30", notes = "New")
        source.saveJobs(listOf(before.copy(inventory = listOf(receivedTiles, removeLocally, peerItem), updatedAt = 2000)))
        transfer(sc, source, tc, target)
        val edit = before.copy(inventory = listOf(tiles.copy(quantity = "2"), removedByPeer, localItem), updatedAt = 3000)
        target.applyJobChanges(listOf(before), listOf(edit))
        assertEquals(listOf(receivedTiles.copy(quantity = "2"), peerItem, localItem), target.jobs().single().inventory)
    }

    @Test fun finishingDraftUsesLatestReceivedDetailsAndPhoto() = phones { sc, source, tc, target ->
        val localPhoto = File(File(tc.filesDir, "photos").apply { mkdirs() }, "local.jpg").apply { writeText("old") }
        val remotePhoto = File(File(sc.filesDir, "photos").apply { mkdirs() }, "remote.jpg").apply { writeText("new") }
        val item = InventoryItem(id = "material", name = "Paint", price = "10")
        val draft = Job(id = "draft", clients = listOf(Person("Client")), address = "Old site",
            inventory = listOf(item), photos = listOf(localPhoto.path), updatedAt = 1000)
        target.saveDraft(draft)
        val staleEditor = target.drafts().single()
        target.saveJobs(listOf(Job(id = "other", priority = 7)))
        source.saveDraft(draft.copy(address = "Received site", inventory = listOf(item.copy(price = "25")),
            photos = listOf(remotePhoto.path), updatedAt = 2000))
        val archive = SyncArchive.create(sc, source, SyncSelection(drafts = mapOf(draft.id to SyncCategory.entries.toSet())))
        try {
            SyncArchive.merge(tc, target, archive)
            assertFalse(localPhoto.exists())
            assertTrue(target.finishDraft(staleEditor.id) is DraftFinishResult.Saved)
            val saved = target.jobs().first { it.id == draft.id }
            assertEquals("Received site", saved.address)
            assertEquals("25", saved.inventory.single().price)
            assertEquals("new", File(saved.photos.single()).readText())
            assertEquals(8, saved.priority)
            assertTrue(target.drafts().isEmpty())
        } finally { archive.delete() }
    }

    @Test fun relayModifiedArchiveCannotReachPersistence() = phones { sc, source, tc, target ->
        val local = Job(id = "local", title = "Keep")
        target.saveJobs(listOf(local))
        source.saveJobs(listOf(Job(id = "received", title = "Private data")))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf("received" to SyncCategory.jobDefaults)))
        try {
            val (client, server) = SyncHandshakeTest.pair()
            assertEquals(syncVerificationCode(client), syncVerificationCode(server))
            client.verifyHandshake(); server.verifyHandshake()
            val wire = java.io.ByteArrayOutputStream()
            val sender = SecureSyncChannel(client.toConnectionContext(), java.io.DataInputStream(java.io.ByteArrayInputStream(byteArrayOf())), java.io.DataOutputStream(wire))
            sender.sendFile(archive)
            val modified = wire.toByteArray().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
            val receiver = SecureSyncChannel(server.toConnectionContext(), java.io.DataInputStream(java.io.ByteArrayInputStream(modified)), java.io.DataOutputStream(java.io.ByteArrayOutputStream()))
            val failure = runCatching {
                val received = receiver.receiveFile(tc.cacheDir)
                try { SyncArchive.merge(tc, target, received) } finally { received.delete() }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(listOf(local), target.jobs())
            assertTrue(tc.cacheDir.listFiles()!!.isEmpty())
        } finally { archive.delete() }
    }

    @Test fun unicodeQrBitmapRoundTripsActualPixels() {
        val text = sharePayload(Job(title = "李明 🛠️", address = "東京都", description = "Café — 修理 🔧"))
        val bitmap = requireNotNull(qrBitmap(text))
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
            assertEquals(text, decoded.text)
        } finally { bitmap.recycle() }
    }
}
