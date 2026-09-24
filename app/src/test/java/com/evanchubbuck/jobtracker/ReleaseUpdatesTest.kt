package com.evanchubbuck.jobtracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseUpdatesTest {
    @Test fun comparesNumericVersionParts() {
        assertTrue(isNewerRelease("v1.10.0", "1.9.9"))
        assertTrue(isNewerRelease("v2.0.0", "1.99.99"))
        assertFalse(isNewerRelease("v1.1.1", "1.1.1"))
        assertFalse(isNewerRelease("v1.1.1", "1.1.2"))
        assertFalse(isNewerRelease("unexpected", "1.1.1"))
    }
}
