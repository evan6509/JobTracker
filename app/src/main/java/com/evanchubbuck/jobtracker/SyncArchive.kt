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

/** A complete job snapshot. Photo paths are replaced with files inside the archive. */
internal object SyncArchive {
    private const val MAX_ARCHIVE = 1024L * 1024 * 1024
    private const val MAX_PHOTO = 100L * 1024 * 1024
    private const val MAX_MANIFEST = 5L * 1024 * 1024

    fun create(context: Context, store: JobStore): File {
        val archive = File.createTempFile("jobtracker-send-", ".zip", context.cacheDir)
        try {
            val manifest = JSONObject().put("format", "jobtracker-sync-v1")
            val records = JSONArray()
            val photos = mutableListOf<Pair<String, File>>()
            store.jobs().forEachIndexed { index, job ->
                val record = JSONObject().put("job", job.toJson(false))
                record.put("photos", photoEntries(context, job, "photos/jobs/$index", photos))
                val costs = store.costs(job.id)
                record.put("costs", JSONArray().apply {
                    costs.forEach { put(JSONObject().put("description", it.description).put("amount", it.amount)) }
                })
                record.put("costUpdatedAt", store.costsUpdatedAt(job.id).takeIf { it > 0 } ?: if (costs.isNotEmpty()) job.updatedAt else 0L)
                records.put(record)
            }
            manifest.put("jobs", records)
            store.draft()?.let { draft ->
                manifest.put("draft", JSONObject().put("job", draft.toJson(false))
                    .put("step", store.draftStep())
                    .put("photos", photoEntries(context, draft, "photos/draft", photos)))
            }
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
                require(manifest.optString("format") == "jobtracker-sync-v1") { "The other phone has an incompatible sync format." }
                val records = manifest.getJSONArray("jobs")
                require(records.length() <= 10000) { "Too many jobs in sync data." }
                val local = store.jobs()
                val merged = local.toMutableList()
                var added = 0
                var updated = 0
                var photoCount = 0
                var costCount = 0
                val newCosts = mutableListOf<Triple<String, List<CostItem>, Long>>()
                for (index in 0 until records.length()) {
                    val record = records.getJSONObject(index)
                    val remote = record.getJSONObject("job").toJob()
                    require(remote.id.isNotBlank()) { "A transferred job has no ID." }
                    val position = merged.indexOfFirst { it.id == remote.id }
                    val old = merged.getOrNull(position)
                    if (old == null || remoteWins(old.updatedAt, remote.updatedAt, old.toJson(false).toString(), remote.toJson(false).toString())) {
                        val paths = extractPhotos(context, zip, record.optJSONArray("photos"), created)
                        photoCount += paths.size
                        val value = remote.copy(photos = paths)
                        if (position < 0) { merged.add(value); added++ } else { merged[position] = value; updated++ }
                    }
                    val costs = record.optJSONArray("costs")?.let { array ->
                        (0 until array.length()).map { row -> array.getJSONObject(row).let { CostItem(it.optString("description"), it.optString("amount")) } }
                    } ?: emptyList()
                    val remoteStamp = record.optLong("costUpdatedAt", 0L)
                    val localCosts = store.costs(remote.id)
                    val localStamp = store.costsUpdatedAt(remote.id).takeIf { it > 0 } ?: if (localCosts.isNotEmpty()) old?.updatedAt ?: 0L else 0L
                    if (position < 0 || remoteWins(localStamp, remoteStamp, localCosts.toString(), costs.toString())) {
                        newCosts += Triple(remote.id, costs, remoteStamp)
                        if (costs.isNotEmpty()) costCount++
                    }
                }
                val draftRecord = manifest.optJSONObject("draft")
                val localDraft = store.draft()
                val remoteDraft = draftRecord?.getJSONObject("job")?.toJob()
                val takeDraft = remoteDraft != null && (localDraft == null ||
                    (localDraft.id == remoteDraft.id && remoteWins(localDraft.updatedAt, remoteDraft.updatedAt,
                        localDraft.toJson(false).toString(), remoteDraft.toJson(false).toString())))
                val newDraft = if (takeDraft) remoteDraft!!.copy(photos = extractPhotos(context, zip, draftRecord!!.optJSONArray("photos"), created)) else null
                if (newDraft != null) photoCount += newDraft.photos.size
                val draftSkipped = remoteDraft != null && localDraft != null && localDraft.id != remoteDraft.id
                val active = merged.filter { it.state == Job.ACTIVE }.maxWithOrNull(compareBy<Job> { it.updatedAt }.thenBy { it.id })?.id
                val finalJobs = merged.map { if (it.state == Job.ACTIVE && it.id != active) it.copy(state = Job.PLANNED) else it }
                store.saveJobs(finalJobs)
                newCosts.forEach { (id, costs, stamp) -> store.saveCostsAt(id, costs, stamp) }
                if (newDraft != null) {
                    store.saveDraft(newDraft)
                    store.saveDraftStep(draftRecord!!.optInt("step", 0))
                }
                return SyncResult(added, updated, photoCount, costCount, draftSkipped)
            }
        } catch (error: Exception) {
            created.forEach(File::delete)
            throw error
        }
    }

    private fun extractPhotos(context: Context, zip: ZipFile, names: JSONArray?, created: MutableList<File>): List<String> {
        if (names == null) return emptyList()
        require(names.length() <= 1000) { "Too many photos in one job." }
        val folder = File(context.filesDir, "photos").apply { mkdirs() }
        var total = created.sumOf { it.length() }
        return (0 until names.length()).map { index ->
            val name = names.getString(index)
            require(Regex("photos/(jobs/[0-9]+|draft)/[0-9]+").matches(name)) { "Invalid photo name." }
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
