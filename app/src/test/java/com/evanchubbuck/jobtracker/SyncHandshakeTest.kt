package com.evanchubbuck.jobtracker

import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncHandshakeTest {
    companion object {
        internal fun pair(): Pair<Ukey2Handshake, Ukey2Handshake> {
            val cipher = Ukey2Handshake.HandshakeCipher.P256_SHA512
            val client = Ukey2Handshake.forInitiator(cipher)
            val server = Ukey2Handshake.forResponder(cipher)
            server.parseHandshakeMessage(client.nextHandshakeMessage)
            client.parseHandshakeMessage(server.nextHandshakeMessage)
            server.parseHandshakeMessage(client.nextHandshakeMessage)
            return client to server
        }
    }

    @Test fun realSocketHandshakeBindsTheSameCodeAndDirectionalChannel() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { listener ->
            try {
                val server = executor.submit<Pair<String, ByteArray>> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        val handshake = exchangeSyncHello(input, output, true)
                        val code = syncVerificationCode(handshake)
                        handshake.verifyHandshake()
                        val channel = SecureSyncChannel(handshake.toConnectionContext(), input, output)
                        channel.confirm()
                        code to channel.receive()
                    }
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 5000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val handshake = exchangeSyncHello(input, output, false)
                    val code = syncVerificationCode(handshake)
                    handshake.verifyHandshake()
                    val channel = SecureSyncChannel(handshake.toConnectionContext(), input, output)
                    channel.confirm()
                    val privateDetails = "Private client details 中文".toByteArray()
                    channel.send(privateDetails)
                    val received = server.get(10, TimeUnit.SECONDS)
                    assertEquals(code, received.first)
                    assertArrayEquals(privateDetails, received.second)
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun incompatiblePhonesAreRejectedBeforeHandshakeOrArchiveTransfer() {
        listOf("jobtracker-sync-v1", "jobtracker-sync-v2", "jobtracker-sync-v3", "unknown").forEach { version ->
            val bytes = ByteArrayOutputStream().apply {
                DataOutputStream(this).apply { writeUTF(version); writeLong(1000) }
            }
            val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
            val error = runCatching { exchangeSyncHello(input, DataOutputStream(ByteArrayOutputStream()), true) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(8, input.available())
        }
    }

    @Test fun substitutedKeyExchangeFailsCommitmentValidation() {
        val cipher = Ukey2Handshake.HandshakeCipher.P256_SHA512
        val client = Ukey2Handshake.forInitiator(cipher)
        val server = Ukey2Handshake.forResponder(cipher)
        val attacker = Ukey2Handshake.forInitiator(cipher)
        server.parseHandshakeMessage(client.nextHandshakeMessage)
        val response = server.nextHandshakeMessage
        client.parseHandshakeMessage(response)
        attacker.nextHandshakeMessage
        attacker.parseHandshakeMessage(response)
        assertNotNull(runCatching { server.parseHandshakeMessage(attacker.nextHandshakeMessage) }.exceptionOrNull())
    }
}
