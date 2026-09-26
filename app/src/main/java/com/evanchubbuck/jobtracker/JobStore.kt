package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Person(val name: String = "", val phone: String = "", val work: String = "")
data class InventoryItem(val name: String = "", val quantity: String = "", val notes: String = "")
data class CostItem(val description: String = "", val amount: String = "")

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

    fun jobs(): List<Job> = readArray("jobs").mapNotNull { runCatching { it.toJob() }.getOrNull() }
    internal fun recycled(): List<RecycledJob> = readArray("recycle_bin").mapNotNull {
        runCatching { it.toRecycledJob() }.getOrNull()
    }

    /** Save the recoverable copy and remove the live item in one preference update. */
    internal fun recycle(id: String, draft: Boolean): Boolean {
        val current = if (draft) drafts() else jobs()
        val job = current.firstOrNull { it.id == id } ?: return false
        val entry = RecycledJob(job, draft, step = if (draft) draftStep(id) else 0,
            costs = if (draft) emptyList() else costs(id), costUpdatedAt = if (draft) 0L else costsUpdatedAt(id),
            fieldStamps = fieldStamps(job))
        val editor = prefs.edit().putString("recycle_bin", recycleJson(recycled() + entry))
        if (draft) {
            writeDrafts(editor, current.filterNot { it.id == id })
            editor.remove("draft_step_$id")
        } else {
            editor.putString("jobs", jobsJson(current.filterNot { it.id == id }))
                .remove("costs_$id").remove("costs_updated_$id")
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
        SyncCategory.entries.filterNot { it == SyncCategory.COSTS }.forEach { category ->
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
                .putString("costs_${restored.id}", JSONArray().apply { entry.costs.forEach {
                    put(JSONObject().put("description", it.description).put("amount", it.amount))
                } }.toString()).putLong("costs_updated_${restored.id}", entry.costUpdatedAt)
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

    fun costs(jobId: String): List<CostItem> = runCatching {
        val array = JSONArray(prefs.getString("costs_$jobId", "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { row -> CostItem(row.optString("description"), row.optString("amount")) } }
    }.getOrDefault(emptyList())

    fun costsUpdatedAt(jobId: String): Long = prefs.getLong("costs_updated_$jobId", 0L)

    fun saveCosts(jobId: String, items: List<CostItem>) {
        saveCostsAt(jobId, items, System.currentTimeMillis())
    }

    fun saveCostsAt(jobId: String, items: List<CostItem>, updatedAt: Long) {
        val array = JSONArray().apply { items.forEach { put(JSONObject().put("description", it.description).put("amount", it.amount)) } }
        prefs.edit().putString("costs_$jobId", array.toString()).putLong("costs_updated_$jobId", updatedAt).apply()
    }

    fun deleteCosts(jobId: String) {
        prefs.edit().remove("costs_$jobId").remove("costs_updated_$jobId").apply()
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
        return SyncCategory.entries.filterNot { it == SyncCategory.COSTS }.associateWith { category ->
            if (saved == null) job.updatedAt else saved.optLong(category.name, 0L)
        }
    }

    internal fun saveFieldStamps(id: String, stamps: Map<SyncCategory, Long>) {
        val json = JSONObject().apply { stamps.forEach { (category, time) ->
            if (category != SyncCategory.COSTS) put(category.name, time)
        } }
        prefs.edit().putString("field_updated_$id", json.toString()).apply()
    }

    private fun recordChangedFields(old: Job?, updated: Job) {
        val stamps = old?.let(::fieldStamps).orEmpty().toMutableMap()
        var changed = old == null
        SyncCategory.entries.filterNot { it == SyncCategory.COSTS }.forEach { category ->
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
private fun InventoryItem.toJson() = JSONObject().put("name", name).put("quantity", quantity).put("notes", notes)
private fun JSONObject.toInventoryItem() = InventoryItem(optString("name"), optString("quantity"), optString("notes"))

fun Job.toJson(includeLocalPhotos: Boolean = true) = JSONObject().apply {
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
    put("inventory", JSONArray().apply { inventory.forEach { put(it.toJson()) } })
    put("workers", JSONArray().apply { workers.forEach { put(it.toJson()) } })
    if (includeLocalPhotos) put("photos", JSONArray().apply { photos.forEach { put(it) } })
    put("importedSource", importedSource)
}

fun JSONObject.toJob(): Job {
    fun people(key: String): List<Person> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toPerson() }
    }
    fun strings(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }
    return Job(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
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
        inventory = (optJSONArray("inventory") ?: JSONArray()).let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toInventoryItem() } },
        workers = people("workers"),
        photos = strings("photos"),
        importedSource = optString("importedSource")
    )
}
