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
