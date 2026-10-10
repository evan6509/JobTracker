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

    @Test fun changingOrClearingEndDateKeepsTheStartInstantAcrossPhoneTimeZones() {
        val originalZone = TimeZone.getDefault()
        val job = Job(startDate = "2026-10-20", startTime = "09:00", endDate = "2026-10-21", timeZone = zone)
        val originalStart = parseStart(job)!!
        val originalCalendarStart = calendarRange(job)!!.start
        try {
            listOf("America/New_York", "America/Los_Angeles", "Asia/Kolkata").forEach { phoneZone ->
                TimeZone.setDefault(TimeZone.getTimeZone(phoneZone))
                listOf("2026-10-23", "").forEach { endDate ->
                    val changed = changeJobEndDate(job, endDate)!!
                    assertEquals(endDate, changed.endDate)
                    assertEquals(zone, changed.timeZone)
                    assertEquals(originalStart, parseStart(changed)!!)
                    assertEquals(originalCalendarStart, calendarRange(changed)!!.start)
                    assertFalse(startIsPast(changed, originalStart - 30_000))
                }
            }
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun endDateChangesStillRejectInvalidOrBackwardDates() {
        val job = Job(startDate = "2026-10-20", endDate = "2026-10-21", timeZone = zone)
        assertNull(changeJobEndDate(job, "2026-10-19"))
        assertNull(changeJobEndDate(job, "2026-02-30"))
        assertNull(changeJobEndDate(job.copy(startDate = ""), "2026-10-21"))
        assertEquals("2026-10-20", changeJobEndDate(job, "2026-10-20")!!.endDate)
        assertEquals("", changeJobEndDate(job.copy(startDate = ""), "")!!.endDate)
    }

    @Test fun startDateAndTimeEditsKeepTheScheduledZoneAndClearDependentDates() {
        val originalZone = TimeZone.getDefault()
        val job = Job(startDate = "2026-10-20", startTime = "09:00", endDate = "2026-10-21", timeZone = zone)
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val movedDate = changeJobStartDate(job, "2026-10-22")
            assertEquals(zone, movedDate.timeZone)
            assertEquals("2026-10-22 14:00", utc.format(Date(parseStart(movedDate)!!)))
            assertEquals("", movedDate.endDate)

            val movedTime = changeJobStartTime(job, "10:30")
            assertEquals(zone, movedTime.timeZone)
            assertEquals("2026-10-20 15:30", utc.format(Date(parseStart(movedTime)!!)))
            assertEquals(job.endDate, movedTime.endDate)

            val clearedTime = changeJobStartTime(job, "")
            assertEquals(zone, clearedTime.timeZone)
            assertEquals("2026-10-20 05:00", utc.format(Date(parseStart(clearedTime)!!)))

            val clearedDate = changeJobStartDate(job, "")
            assertEquals(zone, clearedDate.timeZone)
            assertEquals("", clearedDate.startTime)
            assertEquals("", clearedDate.endDate)
            assertNull(calendarRange(clearedDate))
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun firstStartDateCapturesTheCurrentPhoneZone() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val unscheduled = Job(startTime = "09:00", timeZone = zone)
            val scheduled = changeJobStartDate(unscheduled, "2026-10-20")
            assertEquals("America/New_York", scheduled.timeZone)
            assertEquals(parseStart(Job(startDate = "2026-10-20", startTime = "09:00", timeZone = "America/New_York"))!!,
                calendarRange(scheduled)!!.start)
        } finally { TimeZone.setDefault(originalZone) }
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
