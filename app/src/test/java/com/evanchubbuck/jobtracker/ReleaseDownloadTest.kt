package com.evanchubbuck.jobtracker

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ReleaseDownloadTest.DownloadFiles::class])
class ReleaseDownloadTest {
    private val release = GitHubRelease("v1.2.7", "https://example.com/update.apk")

    @Implements(DownloadManager::class)
    class DownloadFiles : ShadowDownloadManager() {
        val files = mutableMapOf<Long, File>()

        @Implementation
        protected fun openDownloadedFile(id: Long): ParcelFileDescriptor =
            ParcelFileDescriptor.open(files[id] ?: throw FileNotFoundException(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun downloadedFile(context: Context, id: Long): File {
        val manager = context.getSystemService(DownloadManager::class.java)
        val file = File.createTempFile("update-", ".apk", context.cacheDir).apply { writeText("test APK") }
        Shadow.extract<DownloadFiles>(manager).files[id] = file
        return file
    }

    private fun setStatus(context: Context, id: Long, status: Int) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val request = shadowOf(manager).getRequest(id)
        Shadow.extract<ShadowDownloadManager.ShadowRequest>(request).status = status
    }

    @Test fun findsACompletedDownloadAndOpensTheInstallerWithReadAccess() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val id = downloadRelease(activity, release)
            setStatus(activity, id, DownloadManager.STATUS_SUCCESSFUL)
            downloadedFile(activity, id)

            assertTrue(runBlocking { awaitReleaseDownload(activity, id) })
            runBlocking { installRelease(activity, id) }
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data?.scheme)
            assertEquals(id.toString(), intent.data?.lastPathSegment)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun successfulRowWithMissingFileCanBeReplacedBySameRelease() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val id = downloadRelease(context, release)
        setStatus(context, id, DownloadManager.STATUS_SUCCESSFUL)
        assertFalse(awaitReleaseDownload(context, id))

        val file = downloadedFile(context, id)
        assertTrue(awaitReleaseDownload(context, id))
        assertTrue(file.delete())
        assertNull(downloadedReleaseUri(context, id))
        val failure = runCatching { installRelease(context, id) }.exceptionOrNull()
        assertTrue(failure is UpdateApkUnavailableException)

        removeReleaseDownload(context, id)
        val replacement = downloadRelease(context, release)
        setStatus(context, replacement, DownloadManager.STATUS_SUCCESSFUL)
        downloadedFile(context, replacement)
        assertTrue(replacement != id)
        assertTrue(awaitReleaseDownload(context, replacement))
    }

    @Test fun failedAndMissingDownloadsAreNotReadyToInstall() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val id = downloadRelease(context, release)
        setStatus(context, id, DownloadManager.STATUS_FAILED)

        assertFalse(awaitReleaseDownload(context, id))
        assertFalse(awaitReleaseDownload(context, id + 1))
    }

    @Test fun keepsTheDownloadAndLaterChoiceAcrossStoreInstances() {
        val context = RuntimeEnvironment.getApplication()
        val store = ReleaseDownloadStore(context)
        val download = ReleaseDownload(42, "v1.2.7", "Photo previews")
        store.save(download)
        assertEquals(download, ReleaseDownloadStore(context).load())

        val deferred = download.copy(deferred = true)
        store.save(deferred)
        assertEquals(deferred, ReleaseDownloadStore(context).load())

        store.save(null)
        assertNull(ReleaseDownloadStore(context).load())
    }
}
