package com.evanchubbuck.jobtracker

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhotoImportTest {
    @Test fun recreationCancellationCleansCopiesEvenWhenSynchronousCopyCompletesAfterCancel() = runBlocking {
        val folder = Files.createTempDirectory("photo-import").toFile()
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var attached = false
        try {
            val importing = launch(Dispatchers.Default) {
                importJobPhotos(listOf(1, 2), {
                    val file = java.io.File(folder, "$it.jpg").apply { writeText("photo") }
                    started.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    file.path
                }, { attached = true; true })
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            importing.cancel()
            resume.countDown()
            importing.join()
            assertFalse(attached)
            assertTrue(folder.listFiles()!!.isEmpty())
        } finally { resume.countDown(); folder.deleteRecursively() }
    }

    @Test fun rejectedAttachmentDeletesAllCopiesWhileSuccessfulAttachmentRetainsThem() = runBlocking {
        val folder = Files.createTempDirectory("photo-attach").toFile()
        try {
            val copy: (Int) -> String = { java.io.File(folder, "$it.jpg").apply { writeText("photo") }.path }
            importJobPhotos(listOf(1, 2), copy) { false }
            assertTrue(folder.listFiles()!!.isEmpty())
            importJobPhotos(listOf(1, 2), copy) { true }
            assertEquals(2, folder.listFiles()!!.size)
        } finally { folder.deleteRecursively() }
    }
}
