package com.evanchubbuck.jobtracker

import android.content.Context
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
    fun draft(): Job? = runCatching { prefs.getString("draft", null)?.let { JSONObject(it).toJob() } }.getOrNull()
    fun draftStep(): Int = prefs.getInt("draft_step", 0).coerceIn(0, 7)
    fun saveDraftStep(step: Int) { prefs.edit().putInt("draft_step", step.coerceIn(0, 7)).apply() }

    fun costs(jobId: String): List<CostItem> = runCatching {
        val array = JSONArray(prefs.getString("costs_$jobId", "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { row -> CostItem(row.optString("description"), row.optString("amount")) } }
    }.getOrDefault(emptyList())

    fun saveCosts(jobId: String, items: List<CostItem>) {
        val array = JSONArray().apply { items.forEach { put(JSONObject().put("description", it.description).put("amount", it.amount)) } }
        prefs.edit().putString("costs_$jobId", array.toString()).apply()
    }

    fun saveDraft(job: Job?) {
        prefs.edit().apply {
            if (job == null) { remove("draft"); remove("draft_step") }
            else putString("draft", job.toJson().toString())
        }.apply()
    }

    fun saveJobs(jobs: List<Job>) {
        val array = JSONArray()
        jobs.forEach { array.put(it.toJson()) }
        prefs.edit().putString("jobs", array.toString()).apply()
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
