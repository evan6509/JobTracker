package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Person(val name: String = "", val phone: String = "", val work: String = "")
data class InventoryItem(
    val name: String = "", val quantity: String = "", val notes: String = "",
    val price: String = "", val id: String = UUID.randomUUID().toString()
)
// Used only to read expenses saved by older app versions.
private data class LegacyCost(val description: String = "", val amount: String = "", val name: String = "")

private fun JSONObject.toLegacyCost(): LegacyCost = LegacyCost(
    description = optString("description"), amount = optString("amount"), name = optString("name"))

data class Job(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val state: String = PLANNED,
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val clients: List<Person> = listOf(Person()),
    val address: String = "",
    val startDate: String = "",
    val startTime: String = "",
    val endDate: String = "",
    val timeZone: String = java.util.TimeZone.getDefault().id,
    val description: String = "",
    val inventory: List<InventoryItem> = emptyList(),
    val workers: List<Person> = emptyList(),
    val photos: List<String> = emptyList(),
    val importedSource: String = ""
) {
    val label: String get() = title.ifBlank { clients.firstOrNull()?.name?.ifBlank { "Untitled job" } ?: "Untitled job" }

    companion object {
        const val PLANNED = "Planned"
        const val ACTIVE = "Active"
        const val COMPLETED = "Completed"
    }
}

class JobStore(context: Context) {
    private val prefs = context.getSharedPreferences("job_tracker", Context.MODE_PRIVATE)

    init { migrateMaterials() }

    /** Move old expense rows into materials atomically; keep every row and its original amount. */
    private fun migrateMaterials() {
        if (prefs.getInt("materials_schema", 0) >= 1) return
        val editor = prefs.edit()
        fun migrate(jobJson: JSONObject, costs: JSONArray, costStamp: Long, stamps: JSONObject): JSONObject {
            val id = jobJson.getString("id")
            val materials = jobJson.optJSONArray("inventory") ?: JSONArray()
            for (index in 0 until costs.length()) {
                val cost = costs.getJSONObject(index).toLegacyCost()
                materials.put(JSONObject().put("id", legacyMaterialId(id, "expense", index))
                    .put("name", cost.name.ifBlank { cost.description.ifBlank { "Material" } })
                    .put("quantity", "").put("notes", if (cost.name.isNotBlank() && cost.name != cost.description) cost.description else "")
                    .put("price", cost.amount))
            }
            jobJson.put("inventory", materials)
            if (costs.length() > 0) {
                val time = maxOf(costStamp, jobJson.optLong("updatedAt"))
                stamps.put(SyncCategory.INVENTORY.name, maxOf(time, stamps.optLong(SyncCategory.INVENTORY.name)))
                stamps.put(SyncCategory.COSTS.name, maxOf(time, stamps.optLong(SyncCategory.COSTS.name)))
                jobJson.put("updatedAt", time)
            }
            return jobJson
        }
        fun migrateLive(job: JSONObject): JSONObject {
            val id = job.getString("id")
            val oldStamps = prefs.getString("field_updated_$id", null)?.let(::JSONObject)
                ?: JSONObject().apply { SyncCategory.entries.forEach { put(it.name, job.optLong("updatedAt")) } }
            migrate(job, JSONArray(prefs.getString("costs_$id", "[]")), prefs.getLong("costs_updated_$id", 0), oldStamps)
            editor.putString("field_updated_$id", oldStamps.toString()).remove("costs_$id").remove("costs_updated_$id")
            return job
        }
        for (key in listOf("jobs", "drafts")) {
            if (prefs.contains(key)) {
                val array = JSONArray(prefs.getString(key, "[]"))
                for (index in 0 until array.length()) migrateLive(array.getJSONObject(index))
                editor.putString(key, array.toString())
            }
        }
        prefs.getString("draft", null)?.let { editor.putString("draft", migrateLive(JSONObject(it)).toString()) }
        if (prefs.contains("recycle_bin")) {
            val bin = JSONArray(prefs.getString("recycle_bin", "[]"))
            for (index in 0 until bin.length()) {
                val entry = bin.getJSONObject(index)
                val stamps = entry.optJSONObject("fieldUpdated") ?: JSONObject()
                migrate(entry.getJSONObject("job"), entry.optJSONArray("costs") ?: JSONArray(),
                    entry.optLong("costUpdatedAt"), stamps)
                entry.put("fieldUpdated", stamps).remove("costs")
                entry.remove("costUpdatedAt")
            }
            editor.putString("recycle_bin", bin.toString())
        }
        check(editor.putInt("materials_schema", 1).commit()) { "Could not save materials. Please reopen Job Tracker." }
    }

    fun jobs(): List<Job> = readArray("jobs").mapNotNull { runCatching { it.toJob() }.getOrNull() }
    internal fun recycled(): List<RecycledJob> = readArray("recycle_bin").mapNotNull {
        runCatching { it.toRecycledJob() }.getOrNull()
    }

    /** Save the recoverable copy and remove the live item in one preference update. */
    internal fun recycle(id: String, draft: Boolean): Boolean {
        val current = if (draft) drafts() else jobs()
        val job = current.firstOrNull { it.id == id } ?: return false
        val entry = RecycledJob(job, draft, step = if (draft) draftStep(id) else 0,
            fieldStamps = fieldStamps(job))
        val editor = prefs.edit().putString("recycle_bin", recycleJson(recycled() + entry))
        if (draft) {
            writeDrafts(editor, current.filterNot { it.id == id })
            editor.remove("draft_step_$id")
        } else {
            editor.putString("jobs", jobsJson(current.filterNot { it.id == id }))
        }
        editor.apply()
        return true
    }

    internal fun restoreRecycled(entryId: String): RecycleRestoreResult {
        val bin = recycled()
        val entry = bin.firstOrNull { it.id == entryId } ?: return RecycleRestoreResult.MISSING
        val liveJobs = jobs()
        val liveDrafts = drafts()
        if ((liveJobs + liveDrafts).any { it.id == entry.job.id }) return RecycleRestoreResult.ALREADY_EXISTS
        if (entry.draft && liveDrafts.size >= 3) return RecycleRestoreResult.DRAFT_LIMIT
        val restored = if (entry.draft) entry.job else entry.job.copy(
            state = if (entry.job.state == Job.ACTIVE) Job.PLANNED else entry.job.state,
            priority = (liveJobs.maxOfOrNull { it.priority } ?: -1) + 1,
            updatedAt = System.currentTimeMillis())
        val stamps = entry.fieldStamps.ifEmpty { fieldStamps(entry.job) }.toMutableMap()
        SyncCategory.entries.forEach { category ->
            if (entry.job.syncValue(category) != restored.syncValue(category)) stamps[category] = restored.updatedAt
        }
        val editor = prefs.edit().putString("recycle_bin", recycleJson(bin.filterNot { it.id == entryId }))
            .putString("field_updated_${restored.id}", JSONObject().apply {
                stamps.forEach { (category, time) -> put(category.name, time) }
            }.toString())
        if (entry.draft) {
            writeDrafts(editor, liveDrafts + restored)
            editor.putInt("draft_step_${restored.id}", entry.step)
        } else {
            editor.putString("jobs", jobsJson(liveJobs + restored))
        }
        editor.apply()
        return RecycleRestoreResult.RESTORED
    }

    internal fun deleteRecycled(entryIds: Set<String>): List<RecycledJob> {
        val bin = recycled()
        val removed = bin.filter { it.id in entryIds }
        val remaining = bin.filterNot { it.id in entryIds }
        val liveIds = (jobs() + drafts()).map { it.id }.toSet() + remaining.map { it.job.id }
        val editor = prefs.edit().putString("recycle_bin", recycleJson(remaining))
        removed.filter { it.job.id !in liveIds }.forEach { editor.remove("field_updated_${it.job.id}") }
        editor.apply()
        return removed
    }

    private fun jobsJson(jobs: List<Job>) = JSONArray().apply { jobs.forEach { put(it.toJson()) } }.toString()
    private fun recycleJson(entries: List<RecycledJob>) = JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString()
    fun drafts(): List<Job> = if (prefs.contains("drafts")) {
        readArray("drafts").mapNotNull { runCatching { it.toJob() }.getOrNull() }.take(3)
    } else {
        runCatching { prefs.getString("draft", null)?.let { JSONObject(it).toJob() } }.getOrNull()?.let(::listOf) ?: emptyList()
    }

    fun draftStep(id: String): Int {
        val legacyStep = if (!prefs.contains("drafts") && drafts().firstOrNull()?.id == id) prefs.getInt("draft_step", 0) else 0
        return prefs.getInt("draft_step_$id", legacyStep).coerceIn(0, 7)
    }

    fun saveDraftStep(id: String, step: Int) {
        prefs.edit().putInt("draft_step_$id", step.coerceIn(0, 7)).apply()
    }

    fun saveDraft(job: Job) {
        val existing = drafts()
        require(existing.any { it.id == job.id } || existing.size < 3) { "Only three drafts can be saved." }
        recordChangedFields(existing.firstOrNull { it.id == job.id }, job)
        val updated = if (existing.any { it.id == job.id }) existing.map { if (it.id == job.id) job else it } else existing + job
        saveDrafts(updated)
    }

    fun deleteDraft(id: String) {
        saveDrafts(drafts().filterNot { it.id == id })
        prefs.edit().remove("draft_step_$id").apply()
    }

    fun saveDrafts(drafts: List<Job>) {
        require(drafts.size <= 3 && drafts.map { it.id }.distinct().size == drafts.size)
        prefs.edit().also { writeDrafts(it, drafts) }.apply()
    }

    private fun writeDrafts(editor: SharedPreferences.Editor, drafts: List<Job>) {
        val array = JSONArray().apply { drafts.forEach { put(it.toJson()) } }
        val legacyId = if (!prefs.contains("drafts")) this.drafts().firstOrNull()?.id else null
        editor.apply {
            if (legacyId != null && drafts.any { it.id == legacyId } && !prefs.contains("draft_step_$legacyId")) {
                putInt("draft_step_$legacyId", prefs.getInt("draft_step", 0).coerceIn(0, 7))
            }
            putString("drafts", array.toString()).remove("draft").remove("draft_step")
        }
    }

    fun saveJobs(jobs: List<Job>, trackChanges: Boolean = true) {
        if (trackChanges) {
            val previous = jobs().associateBy { it.id }
            jobs.forEach { recordChangedFields(previous[it.id], it) }
        }
        val array = JSONArray()
        jobs.forEach { array.put(it.toJson()) }
        prefs.edit().putString("jobs", array.toString()).apply()
    }

    internal fun fieldStamps(job: Job): Map<SyncCategory, Long> {
        val saved = runCatching { prefs.getString("field_updated_${job.id}", null)?.let(::JSONObject) }.getOrNull()
        return SyncCategory.entries.associateWith { category ->
            if (saved == null) job.updatedAt else saved.optLong(category.name, 0L)
        }
    }

    internal fun saveFieldStamps(id: String, stamps: Map<SyncCategory, Long>) {
        val json = JSONObject().apply { stamps.forEach { (category, time) ->
            put(category.name, time)
        } }
        prefs.edit().putString("field_updated_$id", json.toString()).apply()
    }

    private fun recordChangedFields(old: Job?, updated: Job) {
        val stamps = old?.let(::fieldStamps).orEmpty().toMutableMap()
        var changed = old == null
        SyncCategory.entries.forEach { category ->
            if (old == null || old.syncValue(category) != updated.syncValue(category)) {
                stamps[category] = updated.updatedAt
                changed = true
            }
        }
        if (changed) saveFieldStamps(updated.id, stamps)
    }

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }
    }.getOrDefault(emptyList())
}

private fun Person.toJson() = JSONObject().put("name", name).put("phone", phone).put("work", work)
private fun JSONObject.toPerson() = Person(optString("name"), optString("phone"), optString("work"))
internal fun legacyMaterialId(jobId: String, kind: String, index: Int): String =
    UUID.nameUUIDFromBytes("$jobId:$kind:$index".toByteArray(Charsets.UTF_8)).toString()

private fun InventoryItem.toJson(includePrices: Boolean) = JSONObject()
    .put("id", id).put("name", name).put("quantity", quantity).put("notes", notes).apply {
        if (includePrices) put("price", price)
    }
private fun JSONObject.toInventoryItem(jobId: String, index: Int) = InventoryItem(
    optString("name"), optString("quantity"), optString("notes"), optString("price"),
    optString("id").ifBlank { legacyMaterialId(jobId, "material", index) })

fun Job.toJson(includeLocalPhotos: Boolean = true, includePrices: Boolean = true) = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("state", state)
    put("priority", priority)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("clients", JSONArray().apply { clients.forEach { put(it.toJson()) } })
    put("address", address)
    put("startDate", startDate)
    put("startTime", startTime)
    put("endDate", endDate)
    put("timeZone", timeZone)
    put("description", description)
    put("inventory", JSONArray().apply { inventory.forEach { put(it.toJson(includePrices)) } })
    put("workers", JSONArray().apply { workers.forEach { put(it.toJson()) } })
    if (includeLocalPhotos) put("photos", JSONArray().apply { photos.forEach { put(it) } })
    put("importedSource", importedSource)
}

fun JSONObject.toJob(): Job {
    val jobId = optString("id").ifBlank { UUID.randomUUID().toString() }
    fun people(key: String): List<Person> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toPerson() }
    }
    fun strings(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }
    return Job(
        id = jobId,
        title = optString("title"),
        state = optString("state", Job.PLANNED),
        priority = optInt("priority"),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        updatedAt = optLong("updatedAt", System.currentTimeMillis()),
        clients = people("clients").ifEmpty { listOf(Person()) },
        address = optString("address"),
        startDate = optString("startDate"),
        startTime = optString("startTime"),
        endDate = optString("endDate").ifBlank { legacyEndDate(optString("startDate"), optString("startTime"), optString("durationMinutes"), optString("timeZone", java.util.TimeZone.getDefault().id)) },
        timeZone = optString("timeZone", java.util.TimeZone.getDefault().id),
        description = optString("description").let { existing ->
            val oldDuration = optString("durationMinutes")
            if (optString("startDate").isBlank() && oldDuration.isNotBlank())
                listOf(existing, "Previous estimate: $oldDuration minutes (choose dates to replace it).").filter { it.isNotBlank() }.joinToString("\n")
            else existing
        },
        inventory = (optJSONArray("inventory") ?: JSONArray()).let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toInventoryItem(jobId, it) } },
        workers = people("workers"),
        photos = strings("photos"),
        importedSource = optString("importedSource")
    )
}
