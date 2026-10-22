package com.evanchubbuck.jobtracker

import com.google.security.cryptauth.lib.securegcm.Ukey2Handshake
import java.io.DataInputStream
import java.io.DataOutputStream

// No fallback to the unauthenticated v3 transport.
internal const val SYNC_FORMAT = "jobtracker-sync-v4"

internal fun exchangeSyncHello(input: DataInputStream, output: DataOutputStream, owner: Boolean): Ukey2Handshake {
    output.writeUTF(SYNC_FORMAT)
    output.flush()
    require(input.readUTF() == SYNC_FORMAT) { "Update Job Tracker on both phones to use encrypted sync." }
    val cipher = Ukey2Handshake.HandshakeCipher.P256_SHA512
    val handshake = if (owner) Ukey2Handshake.forResponder(cipher) else Ukey2Handshake.forInitiator(cipher)
    if (owner) {
        handshake.parseHandshakeMessage(readSyncFrame(input))
        writeSyncFrame(output, handshake.nextHandshakeMessage)
        handshake.parseHandshakeMessage(readSyncFrame(input))
    } else {
        writeSyncFrame(output, handshake.nextHandshakeMessage)
        handshake.parseHandshakeMessage(readSyncFrame(input))
        writeSyncFrame(output, handshake.nextHandshakeMessage)
    }
    return handshake
}

internal fun syncVerificationCode(handshake: Ukey2Handshake): String {
    val bytes = handshake.getVerificationString(4)
    val number = java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
    return "%06d".format(java.util.Locale.US, number % 1_000_000)
}

internal fun writeSyncFrame(output: DataOutputStream, bytes: ByteArray) {
    require(bytes.size in 1..65536) { "Invalid sync message size." }
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
}

internal fun readSyncFrame(input: DataInputStream): ByteArray {
    val length = input.readInt()
    require(length in 1..65536) { "Invalid sync message size." }
    return ByteArray(length).also(input::readFully)
}
