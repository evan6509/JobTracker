package com.evanchubbuck.jobtracker

/** State and priority changes are kept separate so reordering never activates a job. */
internal fun changeJobState(jobs: List<Job>, id: String, state: String, now: Long = System.currentTimeMillis()): List<Job> =
    jobs.map { job ->
        when {
            job.id == id -> job.copy(state = state, updatedAt = now)
            state == Job.ACTIVE && job.state == Job.ACTIVE -> job.copy(state = Job.PLANNED, updatedAt = now)
            else -> job
        }
    }

internal fun reorderVisibleJobs(jobs: List<Job>, from: Int, to: Int): List<Job> {
    val visible = jobs.filter { it.state != Job.COMPLETED }.sortedBy { it.priority }.toMutableList()
    if (from !in visible.indices || to !in visible.indices) return jobs
    visible.add(to, visible.removeAt(from))
    val ranks = visible.mapIndexed { index, job -> job.id to index }.toMap()
    return jobs.map { it.copy(priority = ranks[it.id] ?: it.priority) }
}
