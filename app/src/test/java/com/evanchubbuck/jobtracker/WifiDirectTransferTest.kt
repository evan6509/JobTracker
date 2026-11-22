package com.evanchubbuck.jobtracker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

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
            assertTrue(replacement.approval.complete(Unit))

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

    @Test fun cancelAfterLocalApprovalStopsTransferBeforeLatePeerApproval() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        val (localHandshake, peerHandshake) = SyncHandshakeTest.pair()
        assertEquals(syncVerificationCode(localHandshake), syncVerificationCode(peerHandshake))
        peerHandshake.verifyHandshake()
        val confirmation = (SYNC_FORMAT + ":approved").toByteArray(Charsets.UTF_8)
        val archiveStarted = AtomicBoolean(false)
        ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { peerSocket ->
                WifiDirectTransfer().use { active ->
                    val local = active.ownSocket(listener.accept())
                    local.soTimeout = 5_000
                    peerSocket.soTimeout = 5_000
                    val peer = SecureSyncChannel(peerHandshake.toConnectionContext(),
                        DataInputStream(peerSocket.getInputStream()), DataOutputStream(peerSocket.getOutputStream()))
                    val task = active.start(CoroutineScope(coroutineContext + Dispatchers.IO)) {
                        try {
                            val channel = active.waitForPeerApproval(localHandshake, DataInputStream(local.getInputStream()),
                                DataOutputStream(local.getOutputStream()))
                            archiveStarted.set(true)
                            channel.send(byteArrayOf(1))
                        } catch (error: IOException) {
                            ensureActive()
                            throw error
                        }
                    }
                    try {
                        active.approval.complete(Unit)
                        assertArrayEquals(confirmation, peer.receive())

                        active.cancel()
                        runCatching { peer.send(confirmation) }
                        val failure = runCatching { peer.receive() }.exceptionOrNull()
                        assertTrue(failure is IOException && failure !is SocketTimeoutException)
                        task.join()
                        assertTrue(task.isCancelled)
                        assertTrue(local.isClosed)
                        assertFalse(archiveStarted.get())
                    } finally { active.cancel() }
                }
            }
        }
    }
}
