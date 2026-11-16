package com.evanchubbuck.jobtracker

private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_PEER_CLOCK_LEAD_MS = 3650L * MILLIS_PER_DAY

internal fun peerSyncRevisionLimit(): Long =
    System.currentTimeMillis().coerceIn(0L, Long.MAX_VALUE / 2) + MAX_PEER_CLOCK_LEAD_MS

internal fun storedSyncRevision(value: Long, fallback: Long = 0L): Long =
    // Keep locally advanced revisions above the incoming limit readable.
    if (value in 0L..(peerSyncRevisionLimit() + MILLIS_PER_DAY)) value else fallback

internal fun nextSyncRevision(value: Long): Long = value + 1

internal enum class SyncCategory(val label: String, val fields: List<String>) {
    NAME("Job name", listOf("title")),
    STATUS("Status", listOf("state", "priority")),
    CLIENTS("Clients", listOf("clients")),
    SITE("Job site", listOf("address")),
    SCHEDULE("Schedule", listOf("startDate", "startTime", "endDate", "timeZone")),
    WORK("Work details", listOf("description")),
    INVENTORY("Materials", listOf("inventory")),
    WORKERS("Outside workers", listOf("workers")),
    PHOTOS("Photos", emptyList()),
    COSTS("Prices", listOf("materialPrices"));

    companion object {
        val jobDefaults: Set<SyncCategory> = entries.filterNot { it == COSTS }.toSet()
        val draftDefaults: Set<SyncCategory> = jobDefaults
    }
}

/** Choices are made separately on each phone for what that phone sends. */
internal data class SyncSelection(
    val jobs: Map<String, Set<SyncCategory>> = emptyMap(),
    val drafts: Map<String, Set<SyncCategory>> = emptyMap()
) {
    fun categories(id: String, draft: Boolean): Set<SyncCategory> =
        (if (draft) drafts else jobs)[id].orEmpty()

    fun choose(id: String, draft: Boolean, selected: Boolean): SyncSelection {
        val source = if (draft) drafts else jobs
        val changed = if (selected) source + (id to if (draft) SyncCategory.draftDefaults else SyncCategory.jobDefaults)
            else source - id
        return if (draft) copy(drafts = changed) else copy(jobs = changed)
    }

    fun setCategory(id: String, draft: Boolean, category: SyncCategory, selected: Boolean): SyncSelection {
        val source = if (draft) drafts else jobs
        var changedCategories = if (selected) source[id].orEmpty() + category else source[id].orEmpty() - category
        if (category == SyncCategory.COSTS && selected) changedCategories = changedCategories + SyncCategory.INVENTORY
        if (category == SyncCategory.INVENTORY && !selected) changedCategories = changedCategories - SyncCategory.COSTS
        val changed = if (changedCategories.isEmpty()) source - id else source + (id to changedCategories)
        return if (draft) copy(drafts = changed) else copy(jobs = changed)
    }

    fun selectAllJobs(ids: List<String>): SyncSelection = copy(jobs = ids.associateWith { SyncCategory.jobDefaults })
    fun clearJobs(): SyncSelection = copy(jobs = emptyMap())
}

internal fun Job.syncValue(category: SyncCategory): Any = when (category) {
    SyncCategory.NAME -> title
    SyncCategory.STATUS -> state to priority
    SyncCategory.CLIENTS -> clients
    SyncCategory.SITE -> address
    SyncCategory.SCHEDULE -> listOf(startDate, startTime, endDate, timeZone)
    SyncCategory.WORK -> description
    SyncCategory.INVENTORY -> inventory.map { it.copy(price = "") }
    SyncCategory.WORKERS -> workers
    SyncCategory.PHOTOS -> photos
    SyncCategory.COSTS -> inventory.associate { it.id to it.price }.toSortedMap()
}
