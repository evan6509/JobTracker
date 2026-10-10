package com.evanchubbuck.jobtracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class SyncResult(val added: Int, val updated: Int, val photos: Int, val draftSkipped: Boolean)

/** A selected job snapshot. Fields and photos not chosen by this phone never enter the archive. */
internal object SyncArchive {
    private const val MAX_ARCHIVE = 1024L * 1024 * 1024
    private const val MAX_PHOTO = 100L * 1024 * 1024
    private const val MAX_MANIFEST = 5L * 1024 * 1024

    fun create(context: Context, store: JobStore, selection: SyncSelection): File {
        val archive = File.createTempFile("jobtracker-send-", ".zip", context.cacheDir)
        try {
            val manifest = JSONObject().put("format", SYNC_FORMAT)
            val records = JSONArray()
            val photos = mutableListOf<Pair<String, File>>()
            store.jobs().filter { selection.jobs[it.id]?.isNotEmpty() == true }.forEachIndexed { index, job ->
                val categories = selection.jobs.getValue(job.id)
                records.put(selectedRecord(store, job, categories).apply {
                    if (SyncCategory.PHOTOS in categories) put("photos", photoEntries(context, job, "photos/jobs/$index", photos))

                })
            }
            manifest.put("jobs", records)
            val draftRecords = JSONArray()
            store.drafts().filter { selection.drafts[it.id]?.isNotEmpty() == true }.forEachIndexed { index, draft ->
                val categories = selection.drafts.getValue(draft.id)
                draftRecords.put(selectedRecord(store, draft, categories).apply {
                    put("step", store.draftStep(draft.id))
                    if (SyncCategory.PHOTOS in categories) put("photos", photoEntries(context, draft, "photos/drafts/$index", photos))
                })
            }
            manifest.put("drafts", draftRecords)
            val json = manifest.toString().toByteArray(Charsets.UTF_8)
            require(json.size <= MAX_MANIFEST) { "Too many jobs to sync at once." }
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json)
                zip.closeEntry()
                photos.forEach { (name, file) ->
                    require(file.length() <= MAX_PHOTO) { "A photo is too large to sync." }
                    zip.putNextEntry(ZipEntry(name))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            require(archive.length() <= MAX_ARCHIVE) { "The sync is too large for one transfer." }
            return archive
        } catch (error: Exception) {
            archive.delete()
            throw error
        }
    }

    private fun selectedRecord(store: JobStore, job: Job, categories: Set<SyncCategory>): JSONObject {
        require(SyncCategory.COSTS !in categories || SyncCategory.INVENTORY in categories) {
            "Select materials before sharing their prices."
        }
        require(SyncCategory.COSTS !in categories || job.inventory.all { validMaterialPrice(it.price) }) {
            "Check material prices before sharing them."
        }
        val source = job.toJson(includeLocalPhotos = false, includePrices = false)
        if (SyncCategory.COSTS in categories) source.put("materialPrices", JSONObject().apply {
            job.inventory.forEach { put(it.id, it.price) }
        })
        val selected = JSONObject().put("id", job.id).put("createdAt", job.createdAt).put("updatedAt", job.updatedAt)
        categories.forEach { category -> category.fields.forEach { key -> selected.put(key, source.get(key)) } }
        val stamps = store.fieldStamps(job)
        return JSONObject().put("job", selected)
            .put("fields", JSONArray().apply { categories.forEach { put(it.name) } })
            .apply {
                if (SyncCategory.COSTS in categories) put("priceUpdated", JSONObject().apply {
                    store.materialPriceStamps(job).forEach { (id, time) -> put(id, time) }
                })
            }
            .put("fieldUpdated", JSONObject().apply { categories.forEach { category ->
                put(category.name, stamps.getValue(category))
            } })
    }

    private fun photoEntries(context: Context, job: Job, prefix: String, output: MutableList<Pair<String, File>>): JSONArray {
        val names = JSONArray()
        val folder = File(context.filesDir, "photos").canonicalFile
        job.photos.forEachIndexed { index, path ->
            val file = File(path).canonicalFile
            if (file.isFile && file.parentFile == folder) {
                val name = "$prefix/$index"
                output += name to file
                names.put(name)
            }
        }
        return names
    }

    fun merge(context: Context, store: JobStore, archive: File): SyncResult {
        require(archive.length() in 1..MAX_ARCHIVE) { "Invalid sync transfer size." }
        val created = mutableListOf<File>()
        try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: error("Sync data is missing.")
                require(entry.size in 1..MAX_MANIFEST) { "Invalid sync data size." }
                val manifestBytes = ByteArrayOutputStream()
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(manifestBytes.size() + read <= MAX_MANIFEST) { "Sync data is too large." }
                        manifestBytes.write(buffer, 0, read)
                    }
                }
                val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8.name()))
                require(manifest.optString("format") == SYNC_FORMAT) {
                    "Update Job Tracker on both phones to sync materials with optional prices."
                }
                val records = manifest.getJSONArray("jobs")
                require(records.length() <= 10000) { "Too many jobs in sync data." }
                val local = store.jobs()
                val localDrafts = store.drafts()
                val merged = local.toMutableList()
                var added = 0
                var updated = 0
                var photoCount = 0
                val fieldStamps = mutableMapOf<String, Map<SyncCategory, Long>>()
                val priceStamps = mutableMapOf<String, Map<String, Long>>()
                val photoComparison = PhotoComparison(context, zip)
                val seenJobs = mutableSetOf<String>()
                for (index in 0 until records.length()) {
                    val record = records.getJSONObject(index)
                    val id = record.getJSONObject("job").optString("id")
                    require(id.isNotBlank() && seenJobs.add(id)) { "A transferred job is invalid." }
                    val position = merged.indexOfFirst { it.id == id }
                    val newPriority = (merged.maxOfOrNull { it.priority } ?: -1) + 1
                    val old = merged.getOrNull(position) ?: localDrafts.firstOrNull { it.id == id }
                        ?.copy(state = Job.PLANNED, priority = newPriority)
                    val choice = mergeSelectedRecord(context, store, zip, photoComparison, record, old,
                        newPriority, created)
                    photoCount += choice.photos
                    fieldStamps[id] = choice.stamps
                    priceStamps[id] = choice.priceStamps
                    if (position < 0) { merged.add(choice.job); added++ }
                    else if (choice.changed) { merged[position] = choice.job; updated++ }
                }
                val incomingDrafts = manifest.getJSONArray("drafts")
                require(incomingDrafts.length() <= 3) { "Too many drafts in sync data." }
                val savedIds = merged.map { it.id }.toSet()
                val combinedDrafts = localDrafts.filterNot { it.id in savedIds }.toMutableList()
                val draftSteps = combinedDrafts.associate { it.id to store.draftStep(it.id) }.toMutableMap()
                var draftSkipped = false
                val seenDrafts = mutableSetOf<String>()
                for (index in 0 until incomingDrafts.length()) {
                    val record = incomingDrafts.getJSONObject(index)
                    val id = record.getJSONObject("job").optString("id")
                    require(id.isNotBlank() && seenDrafts.add(id)) { "A transferred draft is invalid." }
                    // A completed setup remains a saved job even if the peer still has its old draft.
                    if (id in savedIds) continue
                    val position = combinedDrafts.indexOfFirst { it.id == id }
                    val localDraft = combinedDrafts.getOrNull(position)
                    if (localDraft == null && combinedDrafts.size >= 3) {
                        draftSkipped = true
                        continue
                    }
                    val choice = mergeSelectedRecord(context, store, zip, photoComparison, record, localDraft, 0, created)
                    photoCount += choice.photos
                    fieldStamps[id] = choice.stamps
                    priceStamps[id] = choice.priceStamps
                    if (choice.changed) {
                        if (position >= 0) combinedDrafts[position] = choice.job else combinedDrafts.add(choice.job)
                        draftSteps[id] = record.optInt("step", 0).coerceIn(0, 7)
                    }
                }
                val active = merged.filter { it.state == Job.ACTIVE }.maxWithOrNull(compareBy<Job> { it.updatedAt }.thenBy { it.id })?.id
                val finalJobs = merged.map { if (it.state == Job.ACTIVE && it.id != active) it.copy(state = Job.PLANNED) else it }
                store.saveSyncState(finalJobs, combinedDrafts, fieldStamps, priceStamps, draftSteps)
                val retainedPhotos = (finalJobs + combinedDrafts).flatMap { it.photos } + store.recycled().flatMap { it.job.photos }
                val retainedCanonical = retainedPhotos.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
                val obsolete = (local + localDrafts).flatMap { it.photos }.filter { path ->
                    runCatching { File(path).canonicalPath !in retainedCanonical }.getOrDefault(false)
                }
                // Cleanup must never turn a successfully persisted merge into a rollback of its new files.
                runCatching { deleteUnreferencedJobPhotos(context, obsolete, retainedPhotos.toSet()) }
                return SyncResult(added, updated, photoCount, draftSkipped)
            }
        } catch (error: Exception) {
            // SharedPreferences can expose new values in memory even when its disk commit fails.
            val retained = runCatching {
                (store.jobs() + store.drafts() + store.recycled().map { it.job }).flatMap { it.photos }
                    .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
            }.getOrNull()
            if (retained != null) created.filter { file ->
                runCatching { file.canonicalPath !in retained }.getOrDefault(false)
            }.forEach(File::delete)
            throw error
        }
    }

    private data class SelectedMerge(
        val job: Job,
        val stamps: Map<SyncCategory, Long>,
        val priceStamps: Map<String, Long>,
        val categories: Set<SyncCategory>,
        val changed: Boolean,
        val photos: Int
    )

    private fun mergeSelectedRecord(
        context: Context, store: JobStore, zip: ZipFile, photoComparison: PhotoComparison, record: JSONObject, old: Job?,
        newPriority: Int, created: MutableList<File>
    ): SelectedMerge {
        val array = record.getJSONArray("fields")
        val categories = (0 until array.length()).map { index ->
            SyncCategory.entries.firstOrNull { it.name == array.getString(index) }
                ?: error("Unknown job detail in sync data.")
        }.toSet()
        require(categories.isNotEmpty() && (SyncCategory.COSTS !in categories || SyncCategory.INVENTORY in categories)) {
            "Invalid selected job details."
        }
        val jobJson = record.getJSONObject("job")
        require(jobJson.optString("id").isNotBlank()) { "A transferred job is invalid." }
        categories.forEach { category -> category.fields.forEach { key ->
            require(jobJson.has(key)) { "A selected job detail is missing." }
        } }
        val remote = jobJson.toJob()
        require(remote.inventory.map { it.id }.distinct().size == remote.inventory.size) { "Duplicate material in sync data." }
        val prices = if (SyncCategory.COSTS in categories) jobJson.getJSONObject("materialPrices") else JSONObject()
        if (SyncCategory.COSTS in categories) {
            require(prices.keys().asSequence().toSet() == remote.inventory.map { it.id }.toSet()) {
                "Prices do not match the transferred materials."
            }
        }
        val remoteStamps = record.getJSONObject("fieldUpdated")
        val initial = old ?: Job(id = remote.id, createdAt = remote.createdAt,
            updatedAt = remote.updatedAt, priority = newPriority)
        var merged = initial
        val stamps = (old?.let(store::fieldStamps)
            ?: SyncCategory.entries.associateWith { 0L }).toMutableMap()
        val priceStamps = old?.let(store::materialPriceStamps).orEmpty().toMutableMap()
        var changed = old == null
        var photoCount = 0
        // Materials must merge before prices; never trust the order of incoming fields.
        categories.filterNot { it == SyncCategory.COSTS }.forEach { category ->
            val incomingStamp = remoteStamps.getLong(category.name)
            require(incomingStamp >= 0) { "Invalid job detail date." }
            val localStamp = stamps[category] ?: 0L
            val take = old == null || when {
                incomingStamp != localStamp -> incomingStamp > localStamp
                category == SyncCategory.PHOTOS -> {
                    val localPhotos = photoComparison.local(initial.photos)
                    val remotePhotos = photoComparison.remote(record.getJSONArray("photos"))
                    (localPhotos.size < initial.photos.size && remotePhotos.isNotEmpty()) ||
                        remoteWins(localStamp, incomingStamp, localPhotos.toString(), remotePhotos.toString())
                }
                else -> remoteWins(localStamp, incomingStamp,
                    initial.syncValue(category).toString(), remote.syncValue(category).toString())
            }
            if (take) {
                if (category == SyncCategory.PHOTOS) {
                    val names = record.getJSONArray("photos")
                    val localPhotos = photoComparison.local(initial.photos)
                    if (localPhotos.size != initial.photos.size || localPhotos != photoComparison.remote(names)) {
                        val paths = extractPhotos(context, zip, names, created)
                        merged = merged.copy(photos = paths)
                        photoCount += paths.size
                    }
                } else {
                    merged = applyCategory(merged, remote, category)
                }
                stamps[category] = incomingStamp
                changed = true
            }
        }
        if (SyncCategory.COSTS in categories) {
            val incomingStamp = remoteStamps.getLong(SyncCategory.COSTS.name)
            require(incomingStamp >= 0) { "Invalid price date." }
            val remotePrices = remote.inventory.associate { it.id to prices.getString(it.id) }.toSortedMap()
            val remotePriceStamps = record.optJSONObject("priceUpdated")
            require(remotePriceStamps == null || remotePriceStamps.keys().asSequence().toSet() == remotePrices.keys) {
                "Price dates do not match the transferred materials."
            }
            merged = merged.copy(inventory = merged.inventory.map { item ->
                val remotePrice = remotePrices[item.id] ?: return@map item
                require(validMaterialPrice(remotePrice)) { "Invalid material price in sync data." }
                // Older v3 peers have only the category timestamp; new peers preserve each item's edit time.
                val remoteTime = remotePriceStamps?.getLong(item.id) ?: incomingStamp
                require(remoteTime >= 0) { "Invalid material price date." }
                if (remoteWins(priceStamps[item.id] ?: 0L, remoteTime, item.price, remotePrice)) {
                    priceStamps[item.id] = remoteTime
                    changed = true
                    item.copy(price = remotePrice)
                } else item
            })
            stamps[SyncCategory.COSTS] = maxOf(stamps[SyncCategory.COSTS] ?: 0L, incomingStamp)
        }
        val finalPriceStamps = merged.inventory.associate { it.id to (priceStamps[it.id] ?: 0L) }
        if (changed) merged = merged.copy(updatedAt = maxOf(initial.updatedAt, remote.updatedAt))
        return SelectedMerge(merged, stamps, finalPriceStamps, categories, changed, photoCount)
    }

    private fun applyCategory(base: Job, remote: Job, category: SyncCategory): Job = when (category) {
        SyncCategory.NAME -> base.copy(title = remote.title)
        SyncCategory.STATUS -> base.copy(state = remote.state, priority = remote.priority)
        SyncCategory.CLIENTS -> base.copy(clients = remote.clients)
        SyncCategory.SITE -> base.copy(address = remote.address)
        SyncCategory.SCHEDULE -> base.copy(startDate = remote.startDate, startTime = remote.startTime,
            endDate = remote.endDate, timeZone = remote.timeZone)
        SyncCategory.WORK -> base.copy(description = remote.description)
        SyncCategory.INVENTORY -> base.copy(inventory = remote.inventory.map { item ->
            item.copy(price = base.inventory.firstOrNull { it.id == item.id }?.price.orEmpty())
        })
        SyncCategory.WORKERS -> base.copy(workers = remote.workers)
        SyncCategory.PHOTOS, SyncCategory.COSTS -> base
    }

    private fun extractPhotos(context: Context, zip: ZipFile, names: JSONArray?, created: MutableList<File>): List<String> {
        if (names == null) return emptyList()
        require(names.length() <= 1000) { "Too many photos in one job." }
        val folder = File(context.filesDir, "photos").apply { mkdirs() }
        var total = created.sumOf { it.length() }
        return (0 until names.length()).map { index ->
            val name = names.getString(index)
            require(Regex("photos/(jobs/[0-9]+|draft|drafts/[0-9]+)/[0-9]+").matches(name)) { "Invalid photo name." }
            val entry = zip.getEntry(name) ?: error("A transferred photo is missing.")
            require(entry.size in 0..MAX_PHOTO) { "A transferred photo is too large." }
            val file = File.createTempFile("synced_", ".jpg", folder)
            created += file
            zip.getInputStream(entry).use { input ->
                FileOutputStream(file).use { output ->
                    var photoBytes = 0L
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        photoBytes += read
                        total += read
                        require(photoBytes <= MAX_PHOTO && total <= MAX_ARCHIVE) { "Transferred photos are too large." }
                        output.write(buffer, 0, read)
                    }
                }
            }
            file.absolutePath
        }
    }

    /** Content fingerprints are stable across phone-specific filenames and work with existing v3 archives. */
    private class PhotoComparison(context: Context, private val zip: ZipFile) {
        private val folder = File(context.filesDir, "photos").canonicalFile
        private val localHashes = mutableMapOf<File, String>()
        private val remoteHashes = mutableMapOf<String, String>()
        private var remoteBytes = 0L

        fun local(paths: List<String>): List<String> = paths.mapNotNull { path ->
            runCatching {
                val file = File(path).canonicalFile
                if (!file.isFile || file.parentFile != folder) null
                else localHashes.getOrPut(file) { FileInputStream(file).use { hash(it, remote = false) } }
            }.getOrNull()
        }

        fun remote(names: JSONArray): List<String> {
            require(names.length() <= 1000) { "Too many photos in one job." }
            return (0 until names.length()).map { index ->
                val name = names.getString(index)
                remoteHashes.getOrPut(name) {
                    require(Regex("photos/(jobs/[0-9]+|draft|drafts/[0-9]+)/[0-9]+").matches(name)) { "Invalid photo name." }
                    val entry = zip.getEntry(name) ?: error("A transferred photo is missing.")
                    require(entry.size in 0..MAX_PHOTO) { "A transferred photo is too large." }
                    zip.getInputStream(entry).use { hash(it, remote = true) }
                }
            }
        }

        private fun hash(input: InputStream, remote: Boolean): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            var bytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                bytes += read
                if (remote) remoteBytes += read
                require(bytes <= MAX_PHOTO && remoteBytes <= MAX_ARCHIVE) { "Transferred photos are too large." }
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun remoteWins(localTime: Long, remoteTime: Long, localValue: String, remoteValue: String): Boolean =
    remoteTime > localTime || (remoteTime == localTime && remoteValue > localValue)
