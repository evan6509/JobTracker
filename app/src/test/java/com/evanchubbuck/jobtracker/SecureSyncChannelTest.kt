package com.evanchubbuck.jobtracker

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class SecureSyncChannelTest {
    private fun channels(): Triple<SecureSyncChannel, (ByteArray) -> SecureSyncChannel, ByteArrayOutputStream> {
        val (client, server) = SyncHandshakeTest.pair()
        assertEquals(syncVerificationCode(client), syncVerificationCode(server))
        client.verifyHandshake(); server.verifyHandshake()
        val clientContext = client.toConnectionContext()
        val serverContext = server.toConnectionContext()
        val wire = ByteArrayOutputStream()
        val sender = SecureSyncChannel(clientContext, DataInputStream(ByteArrayInputStream(byteArrayOf())), DataOutputStream(wire))
        val receiver = { bytes: ByteArray -> SecureSyncChannel(serverContext, DataInputStream(ByteArrayInputStream(bytes)), DataOutputStream(ByteArrayOutputStream())) }
        return Triple(sender, receiver, wire)
    }

    @Test fun encryptedArchiveRoundTripsWithoutExposingContents() {
        val folder = Files.createTempDirectory("sync-channel").toFile()
        try {
            val source = File(folder, "source.zip").apply { writeBytes(ByteArray(100000) { (it % 251).toByte() }) }
            val (sender, receiver, wire) = channels()
            sender.sendFile(source)
            assertFalse(wire.toByteArray().toList().windowed(64).any { it == source.readBytes().take(64) })
            val result = receiver(wire.toByteArray()).receiveFile(folder)
            assertArrayEquals(source.readBytes(), result.readBytes())
        } finally { folder.deleteRecursively() }
    }

    @Test fun relayTamperingOrTruncationRejectsBeforeFileCanBeMergedAndRemovesPartialFiles() {
        val folder = Files.createTempDirectory("sync-attack").toFile()
        try {
            val source = File(folder, "source.zip").apply { writeBytes(ByteArray(70000) { 42 }) }
            for (attack in listOf("tamper", "truncate")) {
                val (sender, receiver, wire) = channels()
                sender.sendFile(source)
                val bytes = wire.toByteArray()
                val modified = if (attack == "truncate") bytes.copyOf(bytes.size - 20) else bytes.also {
                    it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte()
                }
                assertNotNull(runCatching { receiver(modified).receiveFile(folder) }.exceptionOrNull())
                assertEquals(listOf("source.zip"), folder.list()!!.toList())
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun replayAndReflectionAreRejected() {
        val (sender, receiver, wire) = channels()
        sender.send("secret".toByteArray())
        val encoded = wire.toByteArray()
        val replay = receiver(encoded + encoded)
        assertEquals("secret", String(replay.receive()))
        assertNotNull(runCatching { replay.receive() }.exceptionOrNull())
        val (client, server) = SyncHandshakeTest.pair()
        syncVerificationCode(client); syncVerificationCode(server)
        client.verifyHandshake(); server.verifyHandshake()
        val context = client.toConnectionContext()
        assertNotNull(runCatching { context.decodeMessageFromPeer(context.encodeMessageToPeer(byteArrayOf(1))) }.exceptionOrNull())
    }
}
