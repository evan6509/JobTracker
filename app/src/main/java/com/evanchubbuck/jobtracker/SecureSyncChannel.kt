package com.evanchubbuck.jobtracker

import com.google.security.cryptauth.lib.securegcm.D2DConnectionContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

/** UKEY2's directional, sequenced, authenticated encryption covers every application record. */
internal class SecureSyncChannel(
    private val context: D2DConnectionContext,
    private val input: DataInputStream,
    private val output: DataOutputStream
) {
    fun send(bytes: ByteArray) = writeSyncFrame(output, context.encodeMessageToPeer(bytes))
    fun receive(): ByteArray = context.decodeMessageFromPeer(readSyncFrame(input))

    fun confirm() {
        val confirmation = (SYNC_FORMAT + ":approved").toByteArray(Charsets.UTF_8)
        send(confirmation)
        require(receive().contentEquals(confirmation)) { "The other phone did not approve this sync." }
    }

    fun sendFile(file: File) {
        val length = file.length()
        require(length in 1..MAX_ARCHIVE) { "Invalid transfer size." }
        send(ByteBuffer.allocate(9).put(1).putLong(length).array())
        file.inputStream().use { source ->
            val buffer = ByteArray(32768)
            var remaining = length
            while (remaining > 0) {
                val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                require(read > 0) { "Transfer ended early." }
                send(byteArrayOf(2) + buffer.copyOf(read))
                remaining -= read
            }
        }
        send(byteArrayOf(3))
    }

    /** Returns a file only after every record and the end marker have authenticated. */
    fun receiveFile(cacheDir: File): File {
        val header = receive()
        require(header.size == 9 && header[0] == 1.toByte()) { "Invalid transfer header." }
        val length = ByteBuffer.wrap(header, 1, 8).long
        require(length in 1..MAX_ARCHIVE) { "Invalid transfer size." }
        val file = File.createTempFile("jobtracker-receive-", ".zip", cacheDir)
        try {
            file.outputStream().use { target ->
                var remaining = length
                while (remaining > 0) {
                    val record = receive()
                    require(record.size in 2..32769 && record[0] == 2.toByte() && record.size - 1 <= remaining) {
                        "Invalid transfer contents."
                    }
                    target.write(record, 1, record.size - 1)
                    remaining -= record.size - 1
                }
                require(receive().contentEquals(byteArrayOf(3))) { "Invalid transfer ending." }
            }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private companion object { const val MAX_ARCHIVE = 1024L * 1024 * 1024 }
}
