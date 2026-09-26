package com.evanchubbuck.jobtracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class SyncResult(val added: Int, val updated: Int, val photos: Int, val costs: Int, val draftSkipped: Boolean)

/** A selected job snapshot. Fields and photos not chosen by this phone never enter the archive. */
internal object SyncArchive {
    private const val MAX_ARCHIVE = 1024L * 1024 * 1024
    private const val MAX_PHOTO = 100L * 1024 * 1024
    private const val MAX_MANIFEST = 5L * 1024 * 1024

    fun create(context: Context, store: JobStore, selection: SyncSelection): File {
        val archive = File.createTempFile("jobtracker-send-", ".zip", context.cacheDir)
        try {
            val manifest = JSONObject().put("format", "jobtracker-sync-v2")
            val records = JSONArray()
            val photos = mutableListOf<Pair<String, File>>()
            store.jobs().filter { selection.jobs[it.id]?.isNotEmpty() == true }.forEachIndexed { index, job ->
                val categories = selection.jobs.getValue(job.id)
                records.put(selectedRecord(store, job, categories).apply {
                    if (SyncCategory.PHOTOS in categories) put("photos", photoEntries(context, job, "photos/jobs/$index", photos))
                    if (SyncCategory.COSTS in categories) {
                        val costs = store.costs(job.id)
                        put("costs", JSONArray().apply {
                            costs.forEach { put(JSONObject().put("description", it.description).put("amount", it.amount)) }
                        })
                        put("costUpdatedAt", store.costsUpdatedAt(job.id).takeIf { it > 0 }
                            ?: if (costs.isNotEmpty()) job.updatedAt else 0L)
                    }
                })
            }
            manifest.put("jobs", records)
            val draftRecords = JSONArray()
            store.drafts().filter { selection.drafts[it.id]?.isNotEmpty() == true }.forEachIndexed { index, draft ->
                val categories = selection.drafts.getValue(draft.id) - SyncCategory.COSTS
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
        val source = job.toJson(false)
        val selected = JSONObject().put("id", job.id).put("createdAt", job.createdAt).put("updatedAt", job.updatedAt)
        categories.forEach { category -> category.fields.forEach { key -> selected.put(key, source.get(key)) } }
        val stamps = store.fieldStamps(job)
        return JSONObject().put("job", selected)
            .put("fields", JSONArray().apply { categories.forEach { put(it.name) } })
            .put("fieldUpdated", JSONObject().apply { categories.forEach { category ->
                if (category != SyncCategory.COSTS) put(category.name, stamps.getValue(category))
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
                require(manifest.optString("format") == "jobtracker-sync-v2") {
                    "Update Job Tracker on both phones to choose what gets shared."
                }
                val records = manifest.getJSONArray("jobs")
                require(records.length() <= 10000) { "Too many jobs in sync data." }
                val local = store.jobs()
                val merged = local.toMutableList()
                var added = 0
                var updated = 0
                var photoCount = 0
                var costCount = 0
                val newCosts = mutableListOf<Triple<String, List<CostItem>, Long>>()
                val fieldStamps = mutableMapOf<String, Map<SyncCategory, Long>>()
                val seenJobs = mutableSetOf<String>()
                for (index in 0 until records.length()) {
                    val record = records.getJSONObject(index)
                    val id = record.getJSONObject("job").optString("id")
                    require(id.isNotBlank() && seenJobs.add(id)) { "A transferred job is invalid." }
                    val position = merged.indexOfFirst { it.id == id }
                    val old = merged.getOrNull(position)
                    val choice = mergeSelectedRecord(context, store, zip, record, old,
                        (merged.maxOfOrNull { it.priority } ?: -1) + 1, created)
                    photoCount += choice.photos
                    fieldStamps[id] = choice.stamps
                    if (choice.changed) {
                        if (position < 0) { merged.add(choice.job); added++ } else { merged[position] = choice.job; updated++ }
                    }
                    if (SyncCategory.COSTS in choice.categories) {
                        val array = record.getJSONArray("costs")
                        val costs = (0 until array.length()).map { row -> array.getJSONObject(row).let {
                            CostItem(it.optString("description"), it.optString("amount"))
                        } }
                        val remoteStamp = record.getLong("costUpdatedAt")
                        val localCosts = store.costs(id)
                        val localStamp = store.costsUpdatedAt(id).takeIf { it > 0 }
                            ?: if (localCosts.isNotEmpty()) old?.updatedAt ?: 0L else 0L
                        if (old == null || remoteWins(localStamp, remoteStamp, localCosts.toString(), costs.toString())) {
                            newCosts += Triple(id, costs, remoteStamp)
                            if (costs.isNotEmpty()) costCount++
                        }
                    }
                }
                val incomingDrafts = manifest.getJSONArray("drafts")
                require(incomingDrafts.length() <= 3) { "Too many drafts in sync data." }
                val combinedDrafts = store.drafts().toMutableList()
                val draftSteps = combinedDrafts.associate { it.id to store.draftStep(it.id) }.toMutableMap()
                var draftSkipped = false
                val seenDrafts = mutableSetOf<String>()
                for (index in 0 until incomingDrafts.length()) {
                    val record = incomingDrafts.getJSONObject(index)
                    val id = record.getJSONObject("job").optString("id")
                    require(id.isNotBlank() && seenDrafts.add(id)) { "A transferred draft is invalid." }
                    val position = combinedDrafts.indexOfFirst { it.id == id }
                    val localDraft = combinedDrafts.getOrNull(position)
                    if (localDraft == null && combinedDrafts.size >= 3) {
                        draftSkipped = true
                        continue
                    }
                    val choice = mergeSelectedRecord(context, store, zip, record, localDraft, 0, created, draft = true)
                    photoCount += choice.photos
                    fieldStamps[id] = choice.stamps
                    if (choice.changed) {
                        if (position >= 0) combinedDrafts[position] = choice.job else combinedDrafts.add(choice.job)
                        draftSteps[id] = record.optInt("step", 0).coerceIn(0, 7)
                    }
                }
                val active = merged.filter { it.state == Job.ACTIVE }.maxWithOrNull(compareBy<Job> { it.updatedAt }.thenBy { it.id })?.id
                val finalJobs = merged.map { if (it.state == Job.ACTIVE && it.id != active) it.copy(state = Job.PLANNED) else it }
                store.saveJobs(finalJobs, trackChanges = false)
                newCosts.forEach { (id, costs, stamp) -> store.saveCostsAt(id, costs, stamp) }
                store.saveDrafts(combinedDrafts)
                fieldStamps.forEach { (id, stamps) -> store.saveFieldStamps(id, stamps) }
                draftSteps.forEach { (id, step) -> store.saveDraftStep(id, step) }
                return SyncResult(added, updated, photoCount, costCount, draftSkipped)
            }
        } catch (error: Exception) {
            created.forEach(File::delete)
            throw error
        }
    }

    private data class SelectedMerge(
        val job: Job,
        val stamps: Map<SyncCategory, Long>,
        val categories: Set<SyncCategory>,
        val changed: Boolean,
        val photos: Int
    )

    private fun mergeSelectedRecord(
        context: Context, store: JobStore, zip: ZipFile, record: JSONObject, old: Job?,
        newPriority: Int, created: MutableList<File>, draft: Boolean = false
    ): SelectedMerge {
        val array = record.getJSONArray("fields")
        val categories = (0 until array.length()).map { index ->
            SyncCategory.entries.firstOrNull { it.name == array.getString(index) }
                ?: error("Unknown job detail in sync data.")
        }.toSet()
        require(categories.isNotEmpty() && (!draft || SyncCategory.COSTS !in categories)) {
            "Invalid selected job details."
        }
        val jobJson = record.getJSONObject("job")
        require(jobJson.optString("id").isNotBlank()) { "A transferred job is invalid." }
        categories.forEach { category -> category.fields.forEach { key ->
            require(jobJson.has(key)) { "A selected job detail is missing." }
        } }
        val remote = jobJson.toJob()
        val remoteStamps = record.getJSONObject("fieldUpdated")
        val initial = old ?: Job(id = remote.id, createdAt = remote.createdAt,
            updatedAt = remote.updatedAt, priority = newPriority)
        var merged = initial
        val stamps = (old?.let(store::fieldStamps)
            ?: SyncCategory.entries.filterNot { it == SyncCategory.COSTS }.associateWith { 0L }).toMutableMap()
        var changed = old == null
        var photoCount = 0
        categories.filterNot { it == SyncCategory.COSTS }.forEach { category ->
            val incomingStamp = remoteStamps.getLong(category.name)
            require(incomingStamp >= 0) { "Invalid job detail date." }
            val localStamp = stamps[category] ?: 0L
            val take = old == null || remoteWins(localStamp, incomingStamp,
                initial.syncValue(category).toString(), remote.syncValue(category).toString())
            if (take) {
                if (category == SyncCategory.PHOTOS) {
                    val paths = extractPhotos(context, zip, record.getJSONArray("photos"), created)
                    merged = merged.copy(photos = paths)
                    photoCount += paths.size
                } else {
                    merged = applyCategory(merged, remote, category)
                }
                stamps[category] = incomingStamp
                changed = true
            }
        }
        if (changed) merged = merged.copy(updatedAt = maxOf(initial.updatedAt, remote.updatedAt))
        return SelectedMerge(merged, stamps, categories, changed, photoCount)
    }

    private fun applyCategory(base: Job, remote: Job, category: SyncCategory): Job = when (category) {
        SyncCategory.NAME -> base.copy(title = remote.title)
        SyncCategory.STATUS -> base.copy(state = remote.state, priority = remote.priority)
        SyncCategory.CLIENTS -> base.copy(clients = remote.clients)
        SyncCategory.SITE -> base.copy(address = remote.address)
        SyncCategory.SCHEDULE -> base.copy(startDate = remote.startDate, startTime = remote.startTime,
            endDate = remote.endDate, timeZone = remote.timeZone)
        SyncCategory.WORK -> base.copy(description = remote.description)
        SyncCategory.INVENTORY -> base.copy(inventory = remote.inventory)
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
}

internal fun remoteWins(localTime: Long, remoteTime: Long, localValue: String, remoteValue: String): Boolean =
    remoteTime > localTime || (remoteTime == localTime && remoteValue > localValue)
