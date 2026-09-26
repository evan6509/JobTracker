package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftStoreTest {
    @Test fun migratesOldDraftAndKeepsEachDraftStep() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "draft_test_${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val prefs = context.getSharedPreferences("job_tracker", Context.MODE_PRIVATE)
        try {
            val old = Job(title = "Existing draft")
            prefs.edit().putString("draft", old.toJson().toString()).putInt("draft_step", 4).commit()
            val store = JobStore(context)
            assertEquals(listOf(old.id), store.drafts().map { it.id })
            assertEquals(4, store.draftStep(old.id))

            val second = Job(title = "Second")
            val third = Job(title = "Third")
            store.saveDraft(second)
            store.saveDraftStep(second.id, 6)
            store.saveDraft(third)
            assertEquals(3, store.drafts().size)
            assertEquals(4, store.draftStep(old.id))
            assertEquals(6, store.draftStep(second.id))
            assertFalse(prefs.contains("draft"))

            store.deleteDraft(second.id)
            assertEquals(listOf(old.id, third.id), store.drafts().map { it.id })
            assertFalse(prefs.contains("draft_step_${second.id}"))
            assertTrue(store.drafts().any { it.id == old.id })
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun syncTransfersThreeDraftsAndTheirSteps() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceName = "draft_source_${UUID.randomUUID()}"
        val targetName = "draft_target_${UUID.randomUUID()}"
        fun scoped(name: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        val sourceContext = scoped(sourceName)
        val targetContext = scoped(targetName)
        try {
            val source = JobStore(sourceContext)
            val target = JobStore(targetContext)
            val drafts = (1..3).map { Job(title = "Draft $it") }
            drafts.forEachIndexed { index, draft ->
                source.saveDraft(draft)
                source.saveDraftStep(draft.id, index + 1)
            }
            val archive = SyncArchive.create(sourceContext, source,
                SyncSelection(drafts = drafts.associate { it.id to SyncCategory.draftDefaults }))
            try {
                val result = SyncArchive.merge(targetContext, target, archive)
                assertFalse(result.draftSkipped)
                assertEquals(drafts.map { it.id }, target.drafts().map { it.id })
                drafts.forEachIndexed { index, draft -> assertEquals(index + 1, target.draftStep(draft.id)) }
            } finally {
                archive.delete()
            }
        } finally {
            base.getSharedPreferences(sourceName, Context.MODE_PRIVATE).edit().clear().commit()
            base.getSharedPreferences(targetName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
