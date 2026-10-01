package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.zip.ZipFile

class SelectiveSyncTest {
    private fun phones(test: (Context, JobStore, Context, JobStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val names = List(2) { "sync_test_${UUID.randomUUID()}" }
        val contexts = names.map { name -> object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
        } }
        try { test(contexts[0], JobStore(contexts[0]), contexts[1], JobStore(contexts[1])) }
        finally { names.forEach { base.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() } }
    }

    private fun manifest(archive: java.io.File): JSONObject = ZipFile(archive).use { zip ->
        JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
    }

    @Test fun selectedFieldsExcludeMaterialsAndPreserveReceiverDetails() = phones { sc, source, tc, target ->
        val selected = Job(title = "New name", description = "Sender details", address = "Private address", updatedAt = 5000,
            inventory = listOf(InventoryItem("Secret purchase", price = "99")))
        source.saveJobs(listOf(selected, Job(title = "Other job")))
        val earlier = selected.copy(title = "Old name", description = "Earlier details", address = "Keep address", updatedAt = 1000,
            inventory = listOf(InventoryItem("Existing supplies", price = "12")))
        target.saveJobs(listOf(earlier))
        target.saveJobs(listOf(earlier.copy(description = "Keep these details", updatedAt = 9000)))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf(selected.id to setOf(SyncCategory.NAME))))
        try {
            val data = manifest(archive)
            assertEquals(SYNC_FORMAT, data.getString("format"))
            assertEquals(1, data.getJSONArray("jobs").length())
            val json = data.getJSONArray("jobs").getJSONObject(0).getJSONObject("job")
            assertFalse(json.has("inventory"))
            assertFalse(json.has("materialPrices"))
            assertFalse(json.has("address"))
            assertFalse(data.toString().contains("Secret purchase"))
            assertFalse(data.toString().contains("Other job"))
            SyncArchive.merge(tc, target, archive)
            val merged = target.jobs().single()
            assertEquals("New name", merged.title)
            assertEquals("Keep these details", merged.description)
            assertEquals("Keep address", merged.address)
            assertEquals(earlier.inventory, merged.inventory)
        } finally { archive.delete() }
    }

    @Test fun materialsWithoutPricesPreserveMatchingLocalPricesAndDoNotLeakPrices() = phones { sc, source, tc, target ->
        val material = InventoryItem("Tiles", "24", "New description", "99.99")
        val job = Job(title = "Shared job", updatedAt = 5000, inventory = listOf(material, InventoryItem("Delivery", price = "9.87")))
        source.saveJobs(listOf(job))
        target.saveJobs(listOf(job.copy(updatedAt = 1000, inventory = listOf(material.copy(notes = "Old", price = "12.34")))))
        val archive = SyncArchive.create(sc, source, SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.INVENTORY))))
        try {
            val data = manifest(archive)
            assertFalse(data.toString().contains("99.99"))
            assertFalse(data.toString().contains("9.87"))
            val json = data.getJSONArray("jobs").getJSONObject(0).getJSONObject("job")
            assertFalse(json.has("materialPrices"))
            assertFalse(json.getJSONArray("inventory").getJSONObject(0).has("price"))
            SyncArchive.merge(tc, target, archive)
            val items = target.jobs().single().inventory
            assertEquals("New description", items[0].notes)
            assertEquals("12.34", items[0].price)
            assertEquals("", items[1].price)
        } finally { archive.delete() }
    }

    @Test fun pricesTransferOnlyWhenSelectedAndBlankPriceCanClearAnOldPrice() = phones { sc, source, tc, target ->
        val job = Job(title = "Shared job", updatedAt = 5000,
            inventory = listOf(InventoryItem("Tiles", "24", "Blue", "99"), InventoryItem("Delivery")))
        source.saveJobs(listOf(job))
        target.saveJobs(listOf(job.copy(updatedAt = 1000, inventory = job.inventory.map { it.copy(price = "12") })))
        val archive = SyncArchive.create(sc, source,
            SyncSelection(jobs = mapOf(job.id to setOf(SyncCategory.INVENTORY, SyncCategory.COSTS))))
        try {
            assertTrue(manifest(archive).getJSONArray("jobs").getJSONObject(0).getJSONObject("job").has("materialPrices"))
            SyncArchive.merge(tc, target, archive)
            assertEquals(job.inventory, target.jobs().single().inventory)
        } finally { archive.delete() }
    }

    @Test fun newerLocalPricesSurviveOlderRemotePricesWhileMaterialDetailsUpdate() = phones { sc, source, tc, target ->
        val material = InventoryItem("Tiles", "24", "Old", "10")
        val old = Job(updatedAt = 1000, inventory = listOf(material))
        source.saveJobs(listOf(old))
        source.saveJobs(listOf(old.copy(updatedAt = 5000, inventory = listOf(material.copy(notes = "New", price = "20")))))
        target.saveJobs(listOf(old))
        target.saveJobs(listOf(old.copy(updatedAt = 9000, inventory = listOf(material.copy(price = "30")))))
        val archive = SyncArchive.create(sc, source,
            SyncSelection(jobs = mapOf(old.id to setOf(SyncCategory.INVENTORY, SyncCategory.COSTS))))
        try {
            SyncArchive.merge(tc, target, archive)
            val received = target.jobs().single().inventory.single()
            assertEquals("New", received.notes)
            assertEquals("30", received.price)
        } finally { archive.delete() }
    }

    @Test fun selectedDraftsCanSharePricesAndUnselectedJobsStayOut() = phones { sc, source, tc, target ->
        val draft = Job(title = "Draft", updatedAt = 5000, inventory = listOf(InventoryItem("Paint", price = "45.67")))
        source.saveDraft(draft)
        source.saveDraftStep(draft.id, 4)
        source.saveJobs(listOf(Job(title = "Unselected job")))
        val archive = SyncArchive.create(sc, source,
            SyncSelection(drafts = mapOf(draft.id to setOf(SyncCategory.NAME, SyncCategory.INVENTORY, SyncCategory.COSTS))))
        try {
            assertEquals(0, manifest(archive).getJSONArray("jobs").length())
            SyncArchive.merge(tc, target, archive)
            assertEquals(draft.inventory, target.drafts().single().inventory)
            assertEquals(4, target.draftStep(draft.id))
            assertTrue(target.jobs().isEmpty())
        } finally { archive.delete() }
    }
}
