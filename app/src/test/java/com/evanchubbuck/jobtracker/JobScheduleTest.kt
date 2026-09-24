package com.evanchubbuck.jobtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class JobScheduleTest {
    private val zone = "America/Chicago"

    @Test fun dateRangeIsInclusiveAndDisplaysMonthDayYear() {
        val job = Job(clients = listOf(Person("Client")), startDate = "2026-09-24", endDate = "2026-09-26", timeZone = zone)
        assertEquals("September 24, 2026 – September 26, 2026", scheduleSummary(job))
        val range = calendarRange(job)!!
        assertTrue(range.allDay)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("2026-09-24 00:00", formatter.format(Date(range.start)))
        assertEquals("2026-09-27 00:00", formatter.format(Date(range.end)))
    }

    @Test fun invalidOrBackwardDatesCannotReachCalendar() {
        val job = Job(clients = listOf(Person("Client")), startDate = "2026-09-26", endDate = "2026-09-24", timeZone = zone)
        assertNull(calendarRange(job))
        assertTrue(validationError(job)!!.contains("End date"))
        assertNull(parseDate("2026-02-30", zone))
    }

    @Test fun timedSingleDayGetsAnHourInCalendar() {
        val job = Job(clients = listOf(Person("Client")), startDate = "2026-09-24", startTime = "09:30", timeZone = zone)
        val range = calendarRange(job)!!
        assertFalse(range.allDay)
        assertEquals(60 * 60_000L, range.end - range.start)
    }

    @Test fun oldMinuteEstimateUsesItsStartTimeWhenMigrated() {
        assertEquals("2026-09-25", legacyEndDate("2026-09-24", "23:30", "90", zone))
    }
}
