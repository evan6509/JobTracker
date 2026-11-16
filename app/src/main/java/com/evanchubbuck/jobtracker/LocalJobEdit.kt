package com.evanchubbuck.jobtracker

/** Rebase an edit made from an older screen onto current storage. */
internal fun applyLocalEdit(before: Job, edited: Job, latest: Job): Job {
    fun changed(category: SyncCategory) = before.syncValue(category) != edited.syncValue(category)
    val photos = if (before.photos == edited.photos) latest.photos else {
        val originalPaths = before.photos.toSet()
        val removed = originalPaths - edited.photos.toSet()
        val added = edited.photos.filterNot { it in originalPaths }
        (latest.photos.filterNot { it in removed } + added).distinct()
    }
    return latest.copy(
        title = if (changed(SyncCategory.NAME)) edited.title else latest.title,
        state = if (before.state != edited.state) edited.state else latest.state,
        priority = if (before.priority != edited.priority) edited.priority else latest.priority,
        clients = if (changed(SyncCategory.CLIENTS)) edited.clients else latest.clients,
        address = if (changed(SyncCategory.SITE)) edited.address else latest.address,
        startDate = if (changed(SyncCategory.SCHEDULE)) edited.startDate else latest.startDate,
        startTime = if (changed(SyncCategory.SCHEDULE)) edited.startTime else latest.startTime,
        endDate = if (changed(SyncCategory.SCHEDULE)) edited.endDate else latest.endDate,
        timeZone = if (changed(SyncCategory.SCHEDULE)) edited.timeZone else latest.timeZone,
        description = if (changed(SyncCategory.WORK)) edited.description else latest.description,
        inventory = rebaseInventory(before.inventory, edited.inventory, latest.inventory),
        workers = if (changed(SyncCategory.WORKERS)) edited.workers else latest.workers,
        photos = photos,
        updatedAt = maxOf(latest.updatedAt, edited.updatedAt))
}

private fun rebaseInventory(
    before: List<InventoryItem>, edited: List<InventoryItem>, latest: List<InventoryItem>
): List<InventoryItem> {
    if (before == edited) return latest
    if (before == latest) return edited

    val originals = before.associateBy { it.id }
    val changes = edited.associateBy { it.id }
    val latestIds = latest.mapTo(mutableSetOf()) { it.id }
    val retained = latest.mapNotNull { current ->
        val original = originals[current.id] ?: return@mapNotNull current
        val edit = changes[current.id] ?: return@mapNotNull null
        current.copy(
            name = if (edit.name != original.name) edit.name else current.name,
            quantity = if (edit.quantity != original.quantity) edit.quantity else current.quantity,
            notes = if (edit.notes != original.notes) edit.notes else current.notes,
            price = if (edit.price != original.price) edit.price else current.price
        )
    }
    return retained + edited.filter { it.id !in originals && it.id !in latestIds }
}
