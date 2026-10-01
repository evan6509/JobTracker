package com.evanchubbuck.jobtracker

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class SyncHandshakeTest {
    private val localNonce = ByteArray(16) { it.toByte() }
    private val remoteNonce = ByteArray(16) { (31 - it).toByte() }

    private fun greeting(format: String) = ByteArrayOutputStream().apply {
        DataOutputStream(this).apply {
            writeUTF(format)
            write(remoteNonce)
            writeBoolean(true)
            writeLong(1_000_000_000)
        }
    }.toByteArray()

    @Test fun matchingPhonesExchangeNoncesBeforeApproval() {
        val input = DataInputStream(ByteArrayInputStream(greeting("jobtracker-sync-v3")))
        val sent = ByteArrayOutputStream()
        assertArrayEquals(remoteNonce, exchangeSyncHello(input, DataOutputStream(sent), localNonce))
        // Hello must not consume the peer's approval or archive size.
        assertTrue(input.readBoolean())
        assertEquals(1_000_000_000L, input.readLong())
        DataInputStream(ByteArrayInputStream(sent.toByteArray())).use { output ->
            assertEquals("jobtracker-sync-v3", output.readUTF())
            assertArrayEquals(localNonce, ByteArray(16).also(output::readFully))
            assertEquals(0, output.available())
        }
    }

    @Test fun incompatiblePhonesAreRejectedBeforeApprovalOrArchiveTransfer() {
        listOf("jobtracker-sync-v1", "jobtracker-sync-v2", "jobtracker-sync-v4").forEach { format ->
            val input = DataInputStream(ByteArrayInputStream(greeting(format)))
            val sent = ByteArrayOutputStream()
            val failure = runCatching { exchangeSyncHello(input, DataOutputStream(sent), localNonce) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("Update Job Tracker on both phones"))
            // Even the nonce is unread: only the version header was consumed.
            assertEquals(16 + 1 + 8, input.available())
            DataInputStream(ByteArrayInputStream(sent.toByteArray())).use { output ->
                assertEquals(SYNC_FORMAT, output.readUTF())
                assertArrayEquals(localNonce, ByteArray(16).also(output::readFully))
                assertEquals(0, output.available())
            }
        }
    }
}
