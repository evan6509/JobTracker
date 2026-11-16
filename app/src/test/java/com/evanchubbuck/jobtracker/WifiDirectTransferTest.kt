package com.evanchubbuck.jobtracker

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class WifiDirectTransferTest {
    @Test fun oldTransferCanFinishAfterReplacementStartsWithoutClosingItsSockets() {
        val loopback = InetAddress.getLoopbackAddress()
        val old = WifiDirectTransfer()
        val oldListener = old.ownSocket(ServerSocket(0, 1, loopback))
        old.close()

        WifiDirectTransfer().use { replacement ->
            val listener = replacement.ownSocket(ServerSocket(0, 1, loopback))
            listener.soTimeout = 5_000
            old.close()
            assertTrue(oldListener.isClosed)
            assertTrue(old.approval.isCancelled)
            assertTrue(replacement.approval.complete(true))

            Socket(loopback, listener.localPort).use { client ->
                client.getOutputStream().write(42)
                replacement.ownSocket(listener.accept()).use { accepted ->
                    accepted.soTimeout = 5_000
                    assertEquals(42, accepted.getInputStream().read())
                }
            }
        }
    }

    @Test fun socketAcquiredAfterCancellationIsClosedImmediately() {
        val old = WifiDirectTransfer()
        old.close()
        val late = Socket()
        assertThrows(CancellationException::class.java) { old.ownSocket(late) }
        assertTrue(late.isClosed)
    }
}
