package com.evanchubbuck.jobtracker

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class GitHubRelease(val tag: String, val apkUrl: String)

private val versionPattern = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)")

internal fun isNewerRelease(tag: String, installedVersion: String): Boolean {
    val latest = versionPattern.matchEntire(tag)?.groupValues?.drop(1)?.map(String::toInt) ?: return false
    val installed = versionPattern.matchEntire(installedVersion)?.groupValues?.drop(1)?.map(String::toInt) ?: return false
    return latest.zip(installed).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
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
            throw IllegalStateException("GitHub release check failed (HTTP ${connection.responseCode}).")
        }
        val release = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val tag = release.getString("tag_name")
        require(versionPattern.matches(tag)) { "The latest release has an invalid version tag." }
        val expectedName = "JobTracker-$tag-debug.apk"
        val expectedUrl = "https://github.com/evan6509/JobTracker/releases/download/$tag/$expectedName"
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length()).asSequence()
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == expectedName && it.optString("browser_download_url") == expectedUrl }
            ?: throw IllegalStateException("The latest release has no JobTracker APK.")
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
