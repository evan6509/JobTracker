package com.evanchubbuck.jobtracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.UnknownHostException

class ReleaseUpdatesTest {
    @Test fun startupOnlyReportsNewerVersions() = runBlocking {
        val current = GitHubRelease("v1.2.3", "unused")
        assertNull(startupUpdate("1.2.3") { current })
        assertNull(startupUpdate("1.2.4") { current })
        assertEquals(current, startupUpdate("1.2.2") { current })
    }

    @Test fun startupFailureIsQuietAndDoesNotRetry() = runBlocking {
        var attempts = 0
        assertNull(startupUpdate("1.2.3") { attempts++; throw UnknownHostException() })
        assertEquals(1, attempts)
    }

    @Test(expected = CancellationException::class)
    fun startupCancellationIsPreserved(): Unit = runBlocking {
        startupUpdate("1.2.3") { throw CancellationException() }
        Unit
    }

    @Test fun comparesNumericVersionParts() {
        assertTrue(isNewerRelease("v1.10.0", "1.9.9"))
        assertTrue(isNewerRelease("v2.0.0", "1.99.99"))
        assertFalse(isNewerRelease("v1.1.1", "1.1.1"))
        assertFalse(isNewerRelease("v1.1.1", "1.1.2"))
        assertFalse(isNewerRelease("unexpected", "1.1.1"))
    }

    @Test fun explainsConnectionFailuresWithoutTechnicalDetails() {
        assertEquals(
            "Couldn't reach GitHub to check for updates. Check your internet connection and try again. If you're online, GitHub may be temporarily unavailable.",
            updateCheckErrorMessage(UnknownHostException("api.github.com"))
        )
        assertEquals(
            "Couldn't check for updates. Check your internet connection and try again.",
            updateCheckErrorMessage(IOException("Connection reset"))
        )
    }

    @Test fun explainsReleaseAndServerFailures() {
        assertEquals(
            "GitHub is temporarily limiting update checks. Please try again later.",
            updateCheckErrorMessage(UpdateCheckHttpException(403))
        )
        assertEquals(
            "GitHub couldn't complete the update check. Please try again later.",
            updateCheckErrorMessage(UpdateCheckHttpException(500))
        )
    }
}
