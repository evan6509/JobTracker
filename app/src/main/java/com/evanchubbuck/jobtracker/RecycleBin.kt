package com.evanchubbuck.jobtracker

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class RecycledJob(
    val job: Job,
    val draft: Boolean,
    val id: String = UUID.randomUUID().toString(),
    val deletedAt: Long = System.currentTimeMillis(),
    val step: Int = 0,
    val costs: List<CostItem> = emptyList(),
    val costUpdatedAt: Long = 0L,
    val fieldStamps: Map<SyncCategory, Long> = emptyMap()
)

internal enum class RecycleRestoreResult { RESTORED, DRAFT_LIMIT, ALREADY_EXISTS, MISSING }

internal fun deleteUnreferencedJobPhotos(context: Context, paths: List<String>, retained: Set<String>) {
    val folder = File(context.filesDir, "photos").canonicalPath + File.separator
    paths.filter { path ->
        path !in retained && runCatching { File(path).canonicalPath.startsWith(folder) }.getOrDefault(false)
    }.forEach { File(it).delete() }
}

internal fun RecycledJob.toJson() = JSONObject().put("id", id).put("job", job.toJson())
    .put("draft", draft).put("deletedAt", deletedAt).put("step", step)
    .put("costs", JSONArray().apply { costs.forEach {
        put(JSONObject().put("description", it.description).put("amount", it.amount))
    } }).put("costUpdatedAt", costUpdatedAt)
    .put("fieldUpdated", JSONObject().apply { fieldStamps.forEach { (category, time) -> put(category.name, time) } })

internal fun JSONObject.toRecycledJob(): RecycledJob {
    val costs = optJSONArray("costs") ?: JSONArray()
    return RecycledJob(getJSONObject("job").toJob(), getBoolean("draft"), getString("id"),
        getLong("deletedAt"), optInt("step").coerceIn(0, 7),
        (0 until costs.length()).map { index -> costs.getJSONObject(index).let {
            CostItem(it.optString("description"), it.optString("amount"))
        } }, optLong("costUpdatedAt"), optJSONObject("fieldUpdated")?.let { stamps ->
            SyncCategory.entries.filter { stamps.has(it.name) }.associateWith { stamps.getLong(it.name) }
        }.orEmpty())
}
