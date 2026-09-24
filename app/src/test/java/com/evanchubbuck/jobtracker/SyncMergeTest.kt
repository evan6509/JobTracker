package com.evanchubbuck.jobtracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    @Test fun aNewerEditWinsInEitherDirection() {
        assertTrue(remoteWins(100, 101, "local", "remote"))
        assertFalse(remoteWins(101, 100, "local", "remote"))
    }

    @Test fun equalTimestampsResolveTheSameWayOnBothPhones() {
        val first = remoteWins(100, 100, "a", "b")
        val second = remoteWins(100, 100, "b", "a")
        assertTrue(first)
        assertFalse(second)
        assertFalse(remoteWins(100, 100, "a", "a"))
    }
}
