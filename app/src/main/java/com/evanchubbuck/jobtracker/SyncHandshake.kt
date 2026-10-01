package com.evanchubbuck.jobtracker

import java.io.DataInputStream
import java.io.DataOutputStream

// Archive and transport must change together so incompatible phones stop
// before requesting approval or transferring any selected job data.
internal const val SYNC_FORMAT = "jobtracker-sync-v3"

internal fun exchangeSyncHello(input: DataInputStream, output: DataOutputStream, nonce: ByteArray): ByteArray {
    require(nonce.size == 16)
    output.writeUTF(SYNC_FORMAT)
    output.write(nonce)
    output.flush()
    require(input.readUTF() == SYNC_FORMAT) { "Update Job Tracker on both phones to sync materials with optional prices." }
    return ByteArray(16).also(input::readFully)
}
