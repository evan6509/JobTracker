package com.evanchubbuck.jobtracker

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ReleaseDownloadTest {
    private val release = GitHubRelease("v1.2.7", "https://example.com/update.apk")

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

            assertTrue(runBlocking { awaitReleaseDownload(activity, id) })
            installRelease(activity, id)
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data?.scheme)
            assertEquals(id.toString(), intent.data?.lastPathSegment)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    @Test fun failedAndMissingDownloadsAreNotReadyToInstall() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val id = downloadRelease(context, release)
        setStatus(context, id, DownloadManager.STATUS_FAILED)

        assertFalse(awaitReleaseDownload(context, id))
        assertFalse(awaitReleaseDownload(context, id + 1))
    }
}
