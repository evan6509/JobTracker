package com.evanchubbuck.jobtracker

/** Rebase an edit made from an older screen onto current storage. */
internal fun applyLocalEdit(before: Job, edited: Job, latest: Job): Job {
    fun changed(category: SyncCategory) = before.syncValue(category) != edited.syncValue(category)
    val editedPrices = edited.inventory.associate { it.id to it.price }
    val oldPrices = before.inventory.associate { it.id to it.price }
    val latestPrices = latest.inventory.associate { it.id to it.price }
    val materials = (if (changed(SyncCategory.INVENTORY)) edited.inventory else latest.inventory).map { item ->
        item.copy(price = if (editedPrices.containsKey(item.id) && oldPrices[item.id] != editedPrices[item.id])
            editedPrices.getValue(item.id) else latestPrices[item.id] ?: item.price)
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
        inventory = materials,
        workers = if (changed(SyncCategory.WORKERS)) edited.workers else latest.workers,
        photos = if (changed(SyncCategory.PHOTOS)) edited.photos else latest.photos,
        updatedAt = maxOf(latest.updatedAt, edited.updatedAt))
}
