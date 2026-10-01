package com.evanchubbuck.jobtracker

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFormattingTest {
    @Test fun formatsTenDigitNumbersAsTheyAreTyped() {
        assertEquals("219", displayPhone(phoneInput("219")))
        assertEquals("219-8", displayPhone(phoneInput("2198")))
        assertEquals("219-555-0142", displayPhone(phoneInput("2195550142")))
        assertEquals("219-555-0142", displayPhone(phoneInput("219-555-0142")))
    }

    @Test fun preservesInternationalNumbers() {
        assertEquals("+1 219 555 0142", displayPhone(phoneInput("+1 219 555 0142")))
        assertEquals("12195550142", displayPhone(phoneInput("12195550142")))
    }
}
