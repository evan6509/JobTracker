package com.evanchubbuck.jobtracker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class JobTimeFormatTest {
    private val twelve = JobTimeFormat(false, "h:mm a", Locale.US)
    private val twentyFour = JobTimeFormat(true, "HH:mm", Locale.US)

    @Test fun midnightNoonAndAfternoonUseTheRequestedClockFormat() {
        mapOf("00:00" to "12:00 AM", "12:00" to "12:00 PM", "13:30" to "1:30 PM", "23:59" to "11:59 PM").forEach { (stored, shown) ->
            assertEquals(shown, displayTime(stored, twelve))
            assertEquals(stored, displayTime(stored, twentyFour))
        }
    }

    @Test fun changingDisplayFormatDoesNotShiftTheJobTimeOrCalendarEvent() {
        val originalZone = TimeZone.getDefault()
        val job = Job(startDate = "2030-10-06", endDate = "2030-10-08", startTime = "13:30", timeZone = "America/Chicago")
        val originalEvent = calendarRange(job)
        try {
            listOf("America/Chicago", "Asia/Tokyo", "Pacific/Kiritimati").forEach {
                TimeZone.setDefault(TimeZone.getTimeZone(it))
                assertEquals("October 6, 2030 – October 8, 2030 · starts 1:30 PM", scheduleSummary(job, twelve))
                assertEquals("October 6, 2030 – October 8, 2030 · starts 13:30", scheduleSummary(job, twentyFour))
                assertEquals(originalEvent, calendarRange(job))
                assertEquals("13:30", job.startTime)
            }
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun canonicalStorageAlwaysUsesAsciiTwentyFourHourTime() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("00:05", storedTime(0, 5))
            assertEquals("12:00", storedTime(12, 0))
            assertEquals("23:59", storedTime(23, 59))
        } finally { Locale.setDefault(originalLocale) }
    }

    @Test fun blankOrInvalidSavedTimeDoesNotCrashFormatting() {
        listOf("", "invalid", "24:00", "12:60").forEach { assertEquals(it, displayTime(it, twelve)) }
    }
}
