package com.evanchubbuck.jobtracker

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Own every created file before crossing the cancellable dispatcher return boundary. */
internal suspend fun <T> importJobPhotos(items: List<T>, copy: (T) -> String?, attach: (List<String>) -> Boolean): Int {
    val created = mutableListOf<String>()
    var attached = false
    try {
        withContext(Dispatchers.IO) {
            items.forEach { item ->
                currentCoroutineContext().ensureActive()
                copy(item)?.let(created::add)
            }
        }
        currentCoroutineContext().ensureActive()
        attached = attach(created)
        return if (attached) created.size else 0
    } finally {
        if (!attached) created.forEach { File(it).delete() }
    }
}
