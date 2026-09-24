package com.evanchubbuck.jobtracker

import org.junit.Assert.assertEquals
import org.junit.Test

class JobRulesTest {
    @Test fun activatingAnotherJobPlansThePreviousActiveJob() {
        val jobs = listOf(
            Job(id = "a", state = Job.ACTIVE),
            Job(id = "b", state = Job.PLANNED),
            Job(id = "c", state = Job.COMPLETED)
        )
        val changed = changeJobState(jobs, "b", Job.ACTIVE, now = 123L)
        assertEquals(listOf(Job.PLANNED, Job.ACTIVE, Job.COMPLETED), changed.map { it.state })
        assertEquals(1, changed.count { it.state == Job.ACTIVE })
    }

    @Test fun reorderingChangesPriorityButNotStateOrHistory() {
        val jobs = listOf(
            Job(id = "a", state = Job.ACTIVE, priority = 0),
            Job(id = "b", state = Job.PLANNED, priority = 1),
            Job(id = "c", state = Job.COMPLETED, priority = 9)
        )
        val changed = reorderVisibleJobs(jobs, 0, 1)
        assertEquals(listOf("b", "a"), changed.filter { it.state != Job.COMPLETED }.sortedBy { it.priority }.map { it.id })
        assertEquals(jobs.map { it.state }, changed.map { it.state })
        assertEquals(9, changed.last().priority)
    }
}
