package com.evanchubbuck.jobtracker

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Runs only while the user has the sync screen open. */
internal class WifiDirectSync(private val activity: Activity, private val store: JobStore) {
    var status by mutableStateOf("Open Sync phones on both phones, then search here.")
        private set
    var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
        private set
    var code by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<SyncResult?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var selection by mutableStateOf(SyncSelection())
        private set

    fun resetSelection() { selection = SyncSelection() }
    fun choose(id: String, draft: Boolean, selected: Boolean) { if (!busy) selection = selection.choose(id, draft, selected) }
    fun setCategory(id: String, draft: Boolean, category: SyncCategory, selected: Boolean) {
        if (!busy) selection = selection.setCategory(id, draft, category, selected)
    }
    fun selectAllJobs(ids: List<String>) { if (!busy) selection = selection.selectAllJobs(ids) }
    fun clearJobs() { if (!busy) selection = selection.clearJobs() }

    private var scope: CoroutineScope? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var transfer: Job? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    private var approval: CompletableDeferred<Boolean>? = null

    fun start() {
        if (scope != null) return
        result = null
        status = "Open Sync phones on both phones, then search here."
        if (!activity.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            status = "This phone does not support Wi-Fi Direct."
            return
        }
        val wifi = activity.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifi == null) { status = "Wi-Fi Direct is unavailable on this phone."; return }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        manager = wifi
        channel = wifi.initialize(activity, activity.mainLooper) { status = "Wi-Fi Direct stopped. Reopen this screen to try again." }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        status = "Turn on Wi-Fi to use Wi-Fi Direct."
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> refreshPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnection()
                }
            }
        }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshConnection()
        discover()
    }

    fun stop() {
        approval?.complete(false)
        approval = null
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null
        server = null
        scope?.cancel()
        scope = null
        transfer = null
        receiver?.let { runCatching { activity.unregisterReceiver(it) } }
        receiver = null
        val wifi = manager
        val wifiChannel = channel
        if (wifi != null && wifiChannel != null) runCatching { wifi.removeGroup(wifiChannel, null) }
        manager = null
        channel = null
        peers = emptyList()
        code = null
        busy = false
    }

    fun discover() {
        val wifi = manager ?: return
        val wifiChannel = channel ?: return
        if (transfer == null && result == null) refreshConnection()
        status = "Searching for nearby phones…"
        try {
            wifi.discoverPeers(wifiChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { refreshPeers() }
                override fun onFailure(reason: Int) { status = "Could not search (code $reason). Check Wi-Fi and Location, then try again." }
            })
        } catch (_: SecurityException) { status = "Nearby devices permission is needed to search." }
    }

    private fun refreshPeers() {
        val wifi = manager ?: return
        val wifiChannel = channel ?: return
        try { wifi.requestPeers(wifiChannel) { list -> peers = list.deviceList.toList().sortedBy { it.deviceName } } }
        catch (_: SecurityException) { status = "Nearby devices permission is needed to list phones." }
    }

    fun connect(peer: WifiP2pDevice) {
        val wifi = manager ?: return
        val wifiChannel = channel ?: return
        status = "Connecting to ${peer.deviceName.ifBlank { "phone" }}… Accept the connection prompt on the other phone."
        val config = WifiP2pConfig().apply { deviceAddress = peer.deviceAddress; wps.setup = WpsInfo.PBC }
        try {
            wifi.connect(wifiChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { status = "Waiting for Wi-Fi Direct connection…" }
                override fun onFailure(reason: Int) { status = "Could not connect (code $reason). Search again on both phones." }
            })
        } catch (_: SecurityException) { status = "Nearby devices permission is needed to connect." }
    }

    private fun refreshConnection() {
        val wifi = manager ?: return
        val wifiChannel = channel ?: return
        try {
            wifi.requestConnectionInfo(wifiChannel) { info ->
                if (info.groupFormed && transfer == null) beginTransfer(info)
            }
        } catch (_: SecurityException) { status = "Nearby devices permission is needed to sync." }
    }

    private fun beginTransfer(info: WifiP2pInfo) {
        val running = scope ?: return
        val outgoingSelection = selection
        busy = true
        transfer = running.launch {
            status = "Connected. Opening a secure transfer…"
            try {
                withContext(Dispatchers.IO) {
                    val connected = if (info.isGroupOwner) {
                        ServerSocket(PORT).also { server = it; it.soTimeout = 30_000 }.accept()
                    } else connectToOwner(info)
                    socket = connected
                    connected.soTimeout = 180_000
                    connected.use { exchange(it, info.isGroupOwner, outgoingSelection) }
                }
            } catch (_: TimeoutCancellationException) {
                status = "The code confirmation timed out. Search again on both phones."
            } catch (_: CancellationException) {
                // The user left the sync screen.
            } catch (error: Exception) {
                status = "Sync failed: ${error.message ?: "connection lost"}. Try again on both phones."
            } finally {
                code = null
                socket = null
                runCatching { server?.close() }
                server = null
                transfer = null
                busy = false
            }
        }
    }

    private suspend fun connectToOwner(info: WifiP2pInfo): Socket {
        val address = info.groupOwnerAddress ?: error("Could not find the other phone.")
        repeat(15) { attempt ->
            try { return Socket().apply { connect(java.net.InetSocketAddress(address, PORT), 2_000) } }
            catch (error: Exception) { if (attempt == 14) throw error; delay(1_000) }
        }
        error("Could not reach the other phone.")
    }

    private suspend fun exchange(connection: Socket, owner: Boolean, outgoingSelection: SyncSelection) {
        val input = DataInputStream(connection.getInputStream().buffered())
        val output = DataOutputStream(connection.getOutputStream().buffered())
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        output.writeUTF("jobtracker-sync-v2")
        output.write(nonce)
        output.flush()
        require(input.readUTF() == "jobtracker-sync-v2") { "Update Job Tracker on both phones to choose what gets shared." }
        val remoteNonce = ByteArray(16).also(input::readFully)
        val sorted = listOf(nonce, remoteNonce).sortedBy { it.toHex() }
        val digest = MessageDigest.getInstance("SHA-256").digest(sorted[0] + sorted[1])
        val number = ((digest[0].toInt() and 255) shl 16 or (digest[1].toInt() and 255) shl 8 or (digest[2].toInt() and 255)) % 1_000_000
        val choice = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            approval = choice
            code = "%06d".format(java.util.Locale.US, number)
            status = "Compare the code on both phones. Confirm on each phone to share the details each phone selected."
        }
        val accepted = withTimeout(120_000) { choice.await() }
        output.writeBoolean(accepted)
        output.flush()
        if (!accepted) error("Transfer canceled")
        require(input.readBoolean()) { "The other phone did not approve the transfer." }
        withContext(Dispatchers.Main) { code = null; approval = null; status = "Transferring selected details…" }
        val outgoing = SyncArchive.create(activity, store, outgoingSelection)
        var incoming: File? = null
        try {
            if (owner) {
                sendFile(output, outgoing)
                incoming = receiveFile(input)
            } else {
                incoming = receiveFile(input)
                sendFile(output, outgoing)
            }
            val summary = SyncArchive.merge(activity, store, incoming ?: error("No data was received."))
            withContext(Dispatchers.Main) {
                result = summary
                status = "Sync complete. Added ${summary.added}, updated ${summary.updated}, received ${summary.photos} photos and ${summary.costs} cost lists." +
                    if (summary.draftSkipped) " This phone already has three drafts; extra incoming drafts were kept on the other phone." else ""
            }
        } finally {
            outgoing.delete()
            incoming?.delete()
        }
    }

    fun confirm(yes: Boolean) { approval?.complete(yes) }

    private fun sendFile(output: DataOutputStream, file: File) {
        output.writeLong(file.length())
        FileInputStream(file).use { it.copyTo(output, bufferSize = 32 * 1024) }
        output.flush()
    }

    private fun receiveFile(input: DataInputStream): File {
        val length = input.readLong()
        require(length in 1..(1024L * 1024 * 1024)) { "Invalid transfer size." }
        val file = File.createTempFile("jobtracker-receive-", ".zip", activity.cacheDir)
        try {
            FileOutputStream(file).use { output ->
                var remaining = length
                val buffer = ByteArray(32 * 1024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(read > 0) { "Transfer ended early." }
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            return file
        } catch (error: Exception) { file.delete(); throw error }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object { const val PORT = 39173 }
}
