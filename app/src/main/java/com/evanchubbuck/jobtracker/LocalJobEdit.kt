package com.evanchubbuck.jobtracker

internal data class LocalEditResult(val job: Job, val conflicts: List<SyncCategory>)

/** Rebase an edit made from an older screen onto current storage. */
internal fun applyLocalEdit(before: Job, edited: Job, latest: Job): LocalEditResult {
    fun changed(category: SyncCategory) = before.syncValue(category) != edited.syncValue(category)
    val conflicts = mutableListOf<SyncCategory>()
    fun people(category: SyncCategory, original: List<Person>, desired: List<Person>, current: List<Person>): List<Person> =
        when {
            desired == original || desired == current -> current
            current == original -> desired
            // Person rows have no stable IDs. Refresh the list when both versions changed.
            else -> current.also { conflicts.add(category) }
        }
    val schedule = rebaseSchedule(before, edited, latest) ?: latest.also { conflicts.add(SyncCategory.SCHEDULE) }
    val photos = if (before.photos == edited.photos) latest.photos else {
        val originalPaths = before.photos.toSet()
        val removed = originalPaths - edited.photos.toSet()
        val added = edited.photos.filterNot { it in originalPaths }
        (latest.photos.filterNot { it in removed } + added).distinct()
    }
    val result = latest.copy(
        title = if (changed(SyncCategory.NAME)) edited.title else latest.title,
        state = if (before.state != edited.state) edited.state else latest.state,
        priority = if (before.priority != edited.priority) edited.priority else latest.priority,
        clients = people(SyncCategory.CLIENTS, before.clients, edited.clients, latest.clients),
        address = if (changed(SyncCategory.SITE)) edited.address else latest.address,
        startDate = schedule.startDate,
        startTime = schedule.startTime,
        endDate = schedule.endDate,
        timeZone = schedule.timeZone,
        description = if (changed(SyncCategory.WORK)) edited.description else latest.description,
        inventory = rebaseInventory(before.inventory, edited.inventory, latest.inventory),
        workers = people(SyncCategory.WORKERS, before.workers, edited.workers, latest.workers),
        photos = photos)
    val updated = if (result == latest) latest else result.copy(updatedAt = maxOf(latest.updatedAt, edited.updatedAt))
    return LocalEditResult(updated, conflicts)
}

private fun rebaseSchedule(before: Job, edited: Job, latest: Job): Job? {
    val original = before.syncValue(SyncCategory.SCHEDULE)
    if (edited.syncValue(SyncCategory.SCHEDULE) == original) return latest
    if (latest.syncValue(SyncCategory.SCHEDULE) == original) return edited

    if (before.startDate != edited.startDate) {
        val updated = changeJobStartDate(latest, edited.startDate)
        // Reapplying a changed start date can clear dependent fields.
        if (updated.endDate != latest.endDate && before.endDate != latest.endDate) return null
        if (updated.startTime != latest.startTime && before.startTime != latest.startTime) return null
        return updated.takeIf { scheduleValidationError(it) == null }
    }
    val updated = latest.copy(
        startTime = if (before.startTime != edited.startTime) edited.startTime else latest.startTime,
        endDate = if (before.endDate != edited.endDate) edited.endDate else latest.endDate,
        timeZone = if (before.timeZone != edited.timeZone) edited.timeZone else latest.timeZone
    )
    return updated.takeIf { scheduleValidationError(it) == null }
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
