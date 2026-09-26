package com.evanchubbuck.jobtracker

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

internal data class GitHubRelease(val tag: String, val apkUrl: String)

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
        GitHubRelease(tag, apk.getString("browser_download_url"))
    } finally {
        connection.disconnect()
    }
}

internal fun downloadRelease(context: Context, release: GitHubRelease): Long {
    val request = DownloadManager.Request(Uri.parse(release.apkUrl))
        .setTitle("JobTracker ${release.tag}")
        .setDescription("Tap the completed download to install the update")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(
            context, Environment.DIRECTORY_DOWNLOADS,
            "JobTracker-${release.tag}-${System.currentTimeMillis()}.apk"
        )
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return manager.enqueue(request)
}
