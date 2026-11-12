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

    // Shared by all instances, including a recreated Activity while the old sync finishes.
    internal inline fun <T> transaction(block: () -> T): T = synchronized(mutationLock, block)

    companion object { @PublishedApi internal val mutationLock = Any() }

    init { transaction { migrateMaterials() } }

    /** Move old expense rows into materials atomically; keep every row and its original amount. */
    private fun migrateMaterials() {
        if (prefs.getInt("materials_schema", 0) >= 1) return
        val editor = prefs.edit()
        fun migrate(jobJson: JSONObject, costs: JSONArray, costStamp: Long, stamps: JSONObject): JSONObject {
            SyncCategory.entries.forEach { category ->
                if (!stamps.has(category.name)) stamps.put(category.name, jobJson.optLong("updatedAt"))
            }
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

    fun jobs(): List<Job> = transaction {
        val byId = linkedMapOf<String, Job>()
        readArray("jobs").mapNotNull { runCatching { it.toJob() }.getOrNull() }.forEach { job ->
            val previous = byId[job.id]
            if (previous == null || job.updatedAt > previous.updatedAt) byId[job.id] = job
        }
        return byId.values.toList()
    }
    internal fun recycled(): List<RecycledJob> = transaction {
        readArray("recycle_bin").mapNotNull { runCatching { it.toRecycledJob() }.getOrNull() }
    }

    /** Save the recoverable copy and remove the live item in one preference update. */
    internal fun recycle(id: String, draft: Boolean): Boolean = transaction {
        val current = if (draft) drafts() else jobs()
        val job = current.firstOrNull { it.id == id } ?: return false
        val entry = RecycledJob(job, draft, step = if (draft) draftStep(id) else 0,
            fieldStamps = fieldStamps(job), priceStamps = materialPriceStamps(job))
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

    internal fun restoreRecycled(entryId: String): RecycleRestoreResult = transaction {
        val bin = recycled()
        val entry = bin.firstOrNull { it.id == entryId } ?: return RecycleRestoreResult.MISSING
        val liveJobs = jobs()
        val liveDrafts = drafts()
        if ((liveJobs + liveDrafts).any { it.id == entry.job.id }) return RecycleRestoreResult.ALREADY_EXISTS
        if (entry.draft && liveDrafts.size >= 3) return RecycleRestoreResult.DRAFT_LIMIT
        val restored = if (entry.draft) entry.job else entry.job.copy(
            state = if (entry.job.state == Job.ACTIVE) Job.PLANNED else entry.job.state,
            priority = (liveJobs.maxOfOrNull { it.priority } ?: -1) + 1,
            updatedAt = maxOf(System.currentTimeMillis(), nextRevision(
                entry.fieldStamps.values + entry.priceStamps.values + entry.job.updatedAt)))
        val stamps = SyncCategory.entries.associateWith { entry.fieldStamps[it] ?: entry.job.updatedAt }.toMutableMap()
        SyncCategory.entries.forEach { category ->
            if (entry.job.syncValue(category) != restored.syncValue(category)) stamps[category] = restored.updatedAt
        }
        val editor = prefs.edit().putString("recycle_bin", recycleJson(bin.filterNot { it.id == entryId }))
            .putString("field_updated_${restored.id}", JSONObject().apply {
                stamps.forEach { (category, time) -> put(category.name, time) }
            }.toString())
            .putString("price_updated_${restored.id}", priceStampsJson(entry.priceStamps.ifEmpty {
                restored.inventory.associate { it.id to (stamps[SyncCategory.COSTS] ?: entry.job.updatedAt) }
            }))
        if (entry.draft) {
            writeDrafts(editor, liveDrafts + restored)
            editor.putInt("draft_step_${restored.id}", entry.step)
        } else {
            editor.putString("jobs", jobsJson(liveJobs + restored))
        }
        editor.apply()
        return RecycleRestoreResult.RESTORED
    }

    internal fun deleteRecycled(entryIds: Set<String>): List<RecycledJob> = transaction {
        val bin = recycled()
        val removed = bin.filter { it.id in entryIds }
        val remaining = bin.filterNot { it.id in entryIds }
        val liveIds = (jobs() + drafts()).map { it.id }.toSet() + remaining.map { it.job.id }
        val editor = prefs.edit().putString("recycle_bin", recycleJson(remaining))
        removed.filter { it.job.id !in liveIds }.forEach {
            editor.remove("field_updated_${it.job.id}").remove("price_updated_${it.job.id}")
        }
        editor.apply()
        return removed
    }

    private fun jobsJson(jobs: List<Job>) = JSONArray().apply { jobs.forEach { put(it.toJson()) } }.toString()
    private fun recycleJson(entries: List<RecycledJob>) = JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString()
    fun drafts(): List<Job> = transaction {
        if (prefs.contains("drafts")) {
            readArray("drafts").mapNotNull { runCatching { it.toJob() }.getOrNull() }.take(3)
        } else {
            runCatching { prefs.getString("draft", null)?.let { JSONObject(it).toJob() } }.getOrNull()?.let(::listOf) ?: emptyList()
        }
    }

    fun draftStep(id: String): Int = transaction {
        val legacyStep = if (!prefs.contains("drafts") && drafts().firstOrNull()?.id == id) prefs.getInt("draft_step", 0) else 0
        return prefs.getInt("draft_step_$id", legacyStep).coerceIn(0, 7)
    }

    fun saveDraftStep(id: String, step: Int): Unit = transaction {
        prefs.edit().putInt("draft_step_$id", step.coerceIn(0, 7)).apply()
    }

    fun saveDraft(job: Job): Unit = transaction {
        val existing = drafts()
        require(existing.any { it.id == job.id } || existing.size < 3) { "Only three drafts can be saved." }
        val saved = recordChangedFields(existing.firstOrNull { it.id == job.id }, job)
        val updated = if (existing.any { it.id == job.id }) existing.map { if (it.id == job.id) saved else it } else existing + saved
        saveDrafts(updated)
    }

    fun deleteDraft(id: String): Unit = transaction {
        saveDrafts(drafts().filterNot { it.id == id })
        prefs.edit().remove("draft_step_$id").apply()
    }

    /** Finish once, retaining a saved copy if an older synced draft still exists. */
    fun finishDraft(job: Job): Boolean = transaction {
        val existingDrafts = drafts()
        val draft = existingDrafts.firstOrNull { it.id == job.id } ?: return false
        val existingJobs = jobs()
        val saved = existingJobs.firstOrNull { it.id == job.id }
        val result = saved ?: job.copy(updatedAt = localRevision(draft, job))
        val stamps = if (saved != null) fieldStamps(saved) else changedFieldStamps(draft, result)
        val priceStamps = if (saved != null) materialPriceStamps(saved) else changedPriceStamps(draft, result)
        val editor = prefs.edit()
            .putString("jobs", jobsJson(if (saved != null) existingJobs else existingJobs + result))
            .putString("field_updated_${job.id}", fieldStampsJson(stamps))
            .putString("price_updated_${job.id}", priceStampsJson(priceStamps))
            .remove("draft_step_${job.id}")
        writeDrafts(editor, existingDrafts.filterNot { it.id == job.id })
        editor.apply()
        return true
    }

    fun saveDrafts(drafts: List<Job>): Unit = transaction {
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

    fun saveJobs(jobs: List<Job>, trackChanges: Boolean = true): Unit = transaction {
        require(jobs.map { it.id }.distinct().size == jobs.size) { "A job can only be saved once." }
        val saved = if (trackChanges) {
            val previous = jobs().associateBy { it.id }
            jobs.map { recordChangedFields(previous[it.id], it) }
        } else jobs
        val array = JSONArray()
        saved.forEach { array.put(it.toJson()) }
        prefs.edit().putString("jobs", array.toString()).apply()
    }

    internal fun fieldStamps(job: Job): Map<SyncCategory, Long> = transaction {
        val saved = runCatching { prefs.getString("field_updated_${job.id}", null)?.let(::JSONObject) }.getOrNull()
        return SyncCategory.entries.associateWith { category ->
            saved?.optLong(category.name, job.updatedAt) ?: job.updatedAt
        }
    }

    internal fun saveFieldStamps(id: String, stamps: Map<SyncCategory, Long>): Unit = transaction {
        prefs.edit().putString("field_updated_$id", fieldStampsJson(stamps)).apply()
    }

    internal fun materialPriceStamps(job: Job): Map<String, Long> = transaction {
        val saved = runCatching { prefs.getString("price_updated_${job.id}", null)?.let(::JSONObject) }.getOrNull()
        val legacyStamp = fieldStamps(job).getValue(SyncCategory.COSTS)
        return job.inventory.associate { item ->
            item.id to if (saved == null) legacyStamp else saved.optLong(item.id, 0L)
        }
    }

    internal fun saveSyncState(jobs: List<Job>, drafts: List<Job>,
                               stamps: Map<String, Map<SyncCategory, Long>>,
                               priceStamps: Map<String, Map<String, Long>>, draftSteps: Map<String, Int>): Unit = transaction {
        require(jobs.map { it.id }.distinct().size == jobs.size)
        require(drafts.size <= 3 && drafts.map { it.id }.distinct().size == drafts.size)
        require(drafts.none { draft -> jobs.any { it.id == draft.id } })
        val editor = prefs.edit().putString("jobs", jobsJson(jobs))
        writeDrafts(editor, drafts)
        this.drafts().filterNot { old -> drafts.any { it.id == old.id } }.forEach {
            editor.remove("draft_step_${it.id}")
        }
        stamps.forEach { (id, values) -> editor.putString("field_updated_$id", fieldStampsJson(values)) }
        priceStamps.forEach { (id, values) -> editor.putString("price_updated_$id", priceStampsJson(values)) }
        draftSteps.forEach { (id, step) -> editor.putInt("draft_step_$id", step.coerceIn(0, 7)) }
        check(editor.commit()) { "Could not save synced jobs. Please try again." }
    }

    internal fun syncSnapshot(): SyncStoreSnapshot = transaction {
        val saved = jobs()
        val pending = drafts()
        val all = (saved + pending).distinctBy { it.id }
        SyncStoreSnapshot(prefs.all.toMap(), saved, pending,
            all.associate { it.id to fieldStamps(it) }, all.associate { it.id to materialPriceStamps(it) },
            pending.associate { it.id to draftStep(it.id) })
    }

    /** Only the final write holds the lock; photo parsing and hashing run against a snapshot. */
    internal fun commitSync(snapshot: SyncStoreSnapshot, jobs: List<Job>, drafts: List<Job>,
                            stamps: Map<String, Map<SyncCategory, Long>>, prices: Map<String, Map<String, Long>>,
                            steps: Map<String, Int>, beforeCommit: () -> Unit): Boolean = transaction {
        beforeCommit()
        if (prefs.all != snapshot.token) return false
        saveSyncState(jobs, drafts, stamps, prices, steps)
        return true
    }

    private fun fieldStampsJson(stamps: Map<SyncCategory, Long>) = JSONObject().apply {
        stamps.forEach { (category, time) -> put(category.name, time) }
    }.toString()

    private fun priceStampsJson(stamps: Map<String, Long>) = JSONObject().apply {
        stamps.forEach { (id, time) -> put(id, time) }
    }.toString()

    private fun localRevision(old: Job?, updated: Job): Long {
        if (old == null) return updated.updatedAt
        return maxOf(updated.updatedAt, nextRevision(fieldStamps(old).values + materialPriceStamps(old).values + old.updatedAt))
    }

    private fun nextRevision(observed: Collection<Long>): Long {
        val maximum = observed.max()
        check(maximum < Long.MAX_VALUE) { "This job has an invalid revision." }
        return maximum + 1
    }

    internal fun applyDraftChange(before: Job, edited: Job): Job? = transaction {
        val latest = drafts().firstOrNull { it.id == before.id } ?: return null
        saveDraft(applyLocalEdit(before, edited, latest))
        return drafts().first { it.id == before.id }
    }

    /** Apply only the user's changes to the latest saved snapshot; preserve concurrent sync additions. */
    internal fun applyJobChanges(before: List<Job>, desired: List<Job>) = transaction {
        val baseline = before.associateBy { it.id }
        val changes = desired.associateBy { it.id }
        val current = jobs()
        val result = current.mapNotNull { latest ->
            val old = baseline[latest.id]
            val edited = changes[latest.id]
            when {
                old == null -> latest
                edited == null -> null
                else -> applyLocalEdit(old, edited, latest)
            }
        } + desired.filter { it.id !in baseline && current.none { currentJob -> currentJob.id == it.id } }
        val activated = desired.firstOrNull { it.state == Job.ACTIVE && baseline[it.id]?.state != Job.ACTIVE }?.id
        saveJobs(if (activated == null) result else result.map {
            if (it.state == Job.ACTIVE && it.id != activated) it.copy(state = Job.PLANNED) else it
        })
    }

    private fun changedFieldStamps(old: Job?, updated: Job): Map<SyncCategory, Long> {
        val stamps = old?.let(::fieldStamps).orEmpty().toMutableMap()
        val revision = localRevision(old, updated)
        SyncCategory.entries.forEach { category ->
            if (old == null || old.syncValue(category) != updated.syncValue(category)) stamps[category] = revision
        }
        return stamps
    }

    private fun changedPriceStamps(old: Job?, updated: Job): Map<String, Long> {
        val stamps = old?.let(::materialPriceStamps).orEmpty()
        val previous = old?.inventory.orEmpty().associateBy { it.id }
        val revision = localRevision(old, updated)
        return updated.inventory.associate { item ->
            val prior = previous[item.id]
            item.id to when {
                prior == null -> revision
                prior.price != item.price -> revision
                else -> stamps[item.id] ?: 0L
            }
        }
    }

    private fun recordChangedFields(old: Job?, updated: Job): Job {
        val changed = old != null && SyncCategory.entries.any { old.syncValue(it) != updated.syncValue(it) }
        val saved = if (changed) updated.copy(updatedAt = localRevision(old, updated)) else updated
        val stamps = changedFieldStamps(old, saved)
        val prices = changedPriceStamps(old, saved)
        val editor = prefs.edit().putString("price_updated_${saved.id}", priceStampsJson(prices))
        if (old == null || stamps != fieldStamps(old)) editor.putString("field_updated_${saved.id}", fieldStampsJson(stamps))
        editor.apply()
        return saved
    }

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).map { array.getJSONObject(it) }
    }.getOrDefault(emptyList())
}

internal data class SyncStoreSnapshot(
    val token: Map<String, *>, val jobs: List<Job>, val drafts: List<Job>,
    val fields: Map<String, Map<SyncCategory, Long>>, val prices: Map<String, Map<String, Long>>,
    val steps: Map<String, Int>
)

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
