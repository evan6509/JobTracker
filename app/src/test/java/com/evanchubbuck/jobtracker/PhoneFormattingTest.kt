package com.evanchubbuck.jobtracker

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFormattingTest {
    @Test fun formatsTenDigitNumbersAsTheyAreTyped() {
        assertEquals("219", displayPhone(phoneInput("219")))
        assertEquals("219-8", displayPhone(phoneInput("2198")))
        assertEquals("219-850-3334", displayPhone(phoneInput("2198503334")))
        assertEquals("219-850-3334", displayPhone(phoneInput("219-850-3334")))
    }

    @Test fun preservesInternationalNumbers() {
        assertEquals("+1 219 850 3334", displayPhone(phoneInput("+1 219 850 3334")))
        assertEquals("12198503334", displayPhone(phoneInput("12198503334")))
    }
}
