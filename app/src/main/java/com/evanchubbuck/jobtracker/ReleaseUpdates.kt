package com.evanchubbuck.jobtracker

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

internal data class GitHubRelease(val tag: String, val apkUrl: String, val highlights: String = "")

internal data class ReleaseDownload(val id: Long, val tag: String, val highlights: String)

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal class UpdateCheckHttpException(val statusCode: Int) : IOException()
internal class UpdateCheckReleaseException(val userMessage: String) : Exception()

internal fun updateCheckErrorMessage(error: Throwable): String = when (error) {
    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
        "Couldn't reach GitHub to check for updates. Check your internet connection and try again. If you're online, GitHub may be temporarily unavailable."
    is UpdateCheckHttpException -> when (error.statusCode) {
        403, 429 -> "GitHub is temporarily limiting update checks. Please try again later."
        404 -> "No published update could be found. Please try again later."
        else -> "GitHub couldn't complete the update check. Please try again later."
    }
    is UpdateCheckReleaseException -> error.userMessage
    is IOException -> "Couldn't check for updates. Check your internet connection and try again."
    else -> "Couldn't check for updates right now. Please try again later."
}

private val versionPattern = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)")
private val releaseComment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
private val releaseBullet = Regex("^(?:[-*+]|\\d+[.)])\\s+")
private val releaseLink = Regex("\\[([^\\]]+)]\\([^)]*\\)")

internal fun releaseHighlights(body: String): String {
    val lines = body.replace(releaseComment, "").lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith('#') && !it.startsWith("[Full changelog](") }
        .toList()
    val bullets = lines.filter { releaseBullet.containsMatchIn(it) }
    return bullets.ifEmpty { lines }.take(3).joinToString("\n\n") { line ->
        val plainText = line.replace(releaseBullet, "")
            .replace(releaseLink, "$1")
            .replace("**", "")
            .replace("`", "")
        val text = if (plainText.length > 300) plainText.take(299).trimEnd() + "…" else plainText
        if (bullets.isNotEmpty()) "• $text" else text
    }
}

internal fun isNewerRelease(tag: String, installedVersion: String): Boolean {
    val latest = versionPattern.matchEntire(tag)?.groupValues?.drop(1)?.map(String::toInt) ?: return false
    val installed = versionPattern.matchEntire(installedVersion)?.groupValues?.drop(1)?.map(String::toInt) ?: return false
    return latest.zip(installed).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
}

/** One quiet attempt per app opening; failed checks are left for the next opening. */
internal suspend fun startupUpdate(
    installedVersion: String,
    fetch: suspend () -> GitHubRelease = ::fetchLatestRelease
): GitHubRelease? = try {
    fetch().takeIf { isNewerRelease(it.tag, installedVersion) }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

internal suspend fun fetchLatestRelease(): GitHubRelease = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.github.com/repos/evan6509/JobTracker/releases/latest")
        .openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "JobTracker-Android")
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw UpdateCheckHttpException(connection.responseCode)
        }
        val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val tag = release.getString("tag_name")
        if (!versionPattern.matches(tag)) {
            throw UpdateCheckReleaseException("Update information is unavailable right now. Please try again later.")
        }
        val expectedName = "JobTracker-$tag-debug.apk"
        val expectedUrl = "https://github.com/evan6509/JobTracker/releases/download/$tag/$expectedName"
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).asSequence()
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == expectedName && it.optString("browser_download_url") == expectedUrl }
            ?: throw UpdateCheckReleaseException("An update was found, but its download isn't available yet. Please try again later.")
        val notes = if (release.isNull("body")) "" else release.getString("body")
        GitHubRelease(tag, apk.getString("browser_download_url"), releaseHighlights(notes))
    } finally {
        connection.disconnect()
    }
}

internal fun downloadRelease(context: Context, release: GitHubRelease): Long {
    val request = DownloadManager.Request(Uri.parse(release.apkUrl))
        .setTitle("JobTracker ${release.tag}")
        .setDescription("Tap the completed download to install the update")
        .setMimeType(APK_MIME_TYPE)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(
            context, Environment.DIRECTORY_DOWNLOADS,
            "JobTracker-${release.tag}-${System.currentTimeMillis()}.apk"
        )
    val manager = context.getSystemService(DownloadManager::class.java)
    return manager.enqueue(request).also { check(it >= 0) }
}

internal suspend fun awaitReleaseDownload(context: Context, id: Long): Boolean {
    val manager = context.getSystemService(DownloadManager::class.java)
    val completed = Channel<Unit>(Channel.CONFLATED)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == id) {
                completed.trySend(Unit)
            }
        }
    }
    ContextCompat.registerReceiver(
        context, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_EXPORTED
    )
    try {
        // Query after registering to cover downloads that finished while the app was away.
        while (true) {
            val status = withContext(Dispatchers.IO) {
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    } else null
                }
            }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> return true
                DownloadManager.STATUS_FAILED, null -> return false
                else -> completed.receive()
            }
        }
    } finally {
        context.unregisterReceiver(receiver)
    }
}

internal fun installRelease(context: Context, id: Long) {
    val manager = context.getSystemService(DownloadManager::class.java)
    val apk = manager.getUriForDownloadedFile(id) ?: throw IOException("Update APK is unavailable")
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(apk, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
}
