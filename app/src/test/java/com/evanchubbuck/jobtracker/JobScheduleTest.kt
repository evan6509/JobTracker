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
        assertEquals("End date must be on or after the start date.", scheduleValidationError(job))
        assertNull(parseDate("2026-02-30", zone))
    }

    @Test fun pastStartChecksTheJobTimeZoneAndOptionalStartTime() {
        val now = parseStart(Job(startDate = "2026-09-28", startTime = "15:00", timeZone = zone))!!
        assertTrue(startIsPast(Job(startDate = "2026-09-27", timeZone = zone), now))
        assertFalse(startIsPast(Job(startDate = "2026-09-28", timeZone = zone), now))
        assertTrue(startIsPast(Job(startDate = "2026-09-28", startTime = "14:59", timeZone = zone), now))
        assertFalse(startIsPast(Job(startDate = "2026-09-28", startTime = "15:00", timeZone = zone), now))
        assertFalse(startIsPast(Job(startDate = "2026-09-29", timeZone = zone), now))
    }

    @Test fun timedSingleDayGetsAnHourInCalendar() {
        val job = Job(clients = listOf(Person("Client")), startDate = "2026-09-24", startTime = "09:30", timeZone = zone)
        val range = calendarRange(job)!!
        assertFalse(range.allDay)
        assertEquals(60 * 60_000L, range.end - range.start)
    }

    @Test fun currentMinuteIsNotPastUntilTheNextMinuteStarts() {
        listOf("America/Chicago", "Asia/Kolkata", "UTC").forEach { jobZone ->
            val job = Job(startDate = "2026-09-30", startTime = "23:59", timeZone = jobZone)
            val start = parseStart(job)!!
            assertFalse(startIsPast(job, start))
            assertFalse(startIsPast(job, start + 30_123))
            assertFalse(startIsPast(job, start + 59_999))
            assertTrue(startIsPast(job, start + 60_000))
            assertTrue(startIsPast(job.copy(startTime = "23:58"), start + 30_123))
        }
    }

    @Test fun oldMinuteEstimateUsesItsStartTimeWhenMigrated() {
        assertEquals("2026-09-25", legacyEndDate("2026-09-24", "23:30", "90", zone))
    }

    @Test fun pickerSelectionKeepsItsCalendarDayAcrossDeviceTimeZones() {
        val original = TimeZone.getDefault()
        try {
            listOf("America/Chicago", "America/Los_Angeles", "Pacific/Kiritimati").forEach { deviceZone ->
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
                listOf("2030-10-06", "2030-03-10", "2030-11-03").forEach { selectedDay ->
                    assertEquals(selectedDay, pickerDate(parseDate(selectedDay, "UTC")!!))
                }
            }
        } finally { TimeZone.setDefault(original) }
    }
}
