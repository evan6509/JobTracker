package com.evanchubbuck.jobtracker

import android.content.Context
import java.io.File

/** Attach a camera result to a live job or draft without changing any other job. */
internal fun attachCapturedPhoto(context: Context, store: JobStore, jobId: String, path: String): Job? {
    val file = File(path).absoluteFile
    val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
    if (canonical.parentFile != File(context.filesDir, "photos").canonicalFile || !file.isFile || file.length() !in 1..25L * 1024 * 1024) return null
    val jobs = store.jobs()
    val saved = jobs.firstOrNull { it.id == jobId }
    val current = saved ?: store.drafts().firstOrNull { it.id == jobId } ?: return null
    val updated = current.copy(photos = (current.photos + file.path).distinct(), updatedAt = System.currentTimeMillis())
    if (saved != null) store.saveJobs(jobs.map { if (it.id == jobId) updated else it })
    else store.saveDraft(updated)
    return updated
}
