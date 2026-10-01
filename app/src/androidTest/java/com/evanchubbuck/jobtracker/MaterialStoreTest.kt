package com.evanchubbuck.jobtracker

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MaterialStoreTest {
    private fun isolated(test: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "materials_test_${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(requestedName: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try { test(context) } finally { base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun oldExpensesMigrateOnceWithoutLosingNamesDescriptionsAmountsOrPhotos() = isolated { context ->
        val prefs = context.getSharedPreferences("job_tracker", Context.MODE_PRIVATE)
        val legacy = Job(id = "example", title = "Migration example", updatedAt = 1000,
            photos = listOf("photo-path"), inventory = listOf(InventoryItem("Paint", "2", "Blue"))).toJson()
        legacy.getJSONArray("inventory").getJSONObject(0).apply { remove("id"); remove("price") }
        val costs = JSONArray().put(JSONObject().put("name", "Adhesive").put("description", "Tile adhesive").put("amount", "19.99"))
            .put(JSONObject().put("description", "Delivery").put("amount", "25"))
            .put(JSONObject().put("name", "Quote pending").put("description", "Ask supplier").put("amount", ""))
        prefs.edit().putString("jobs", JSONArray().put(legacy).toString()).putString("costs_example", costs.toString())
            .putLong("costs_updated_example", 2000).commit()
        val store = JobStore(context)
        val saved = store.jobs().single()
        assertEquals(4, saved.inventory.size)
        assertEquals("Paint", saved.inventory[0].name)
        assertEquals("", saved.inventory[0].price)
        assertEquals("Adhesive", saved.inventory[1].name)
        assertEquals("Tile adhesive", saved.inventory[1].notes)
        assertEquals("19.99", saved.inventory[1].price)
        assertEquals("Delivery", saved.inventory[2].name)
        assertEquals("25", saved.inventory[2].price)
        assertEquals("Quote pending", saved.inventory[3].name)
        assertEquals("Ask supplier", saved.inventory[3].notes)
        assertEquals("", saved.inventory[3].price)
        assertEquals(listOf("photo-path"), saved.photos)
        assertEquals(4, saved.inventory.map { it.id }.distinct().size)
        assertFalse(prefs.contains("costs_example"))
        assertEquals(2000L, store.fieldStamps(saved)[SyncCategory.COSTS])
        assertEquals(saved, JobStore(context).jobs().single())
        store.saveJobs(listOf(saved))
        assertEquals(saved, JobStore(context).jobs().single())
    }

    @Test fun legacyRecycleBinExpensesSurviveMigrationAndRestore() = isolated { context ->
        val prefs = context.getSharedPreferences("job_tracker", Context.MODE_PRIVATE)
        val job = Job(id = "recycled-example", title = "Deleted example", photos = listOf("saved-photo"), updatedAt = 1000)
        val legacy = RecycledJob(job, false, id = "bin-entry").toJson().put("costs", JSONArray()
            .put(JSONObject().put("name", "Tiles").put("description", "Blue tiles").put("amount", "42.50")))
            .put("costUpdatedAt", 2000)
        prefs.edit().putString("recycle_bin", JSONArray().put(legacy).toString()).commit()
        val store = JobStore(context)
        val entry = store.recycled().single()
        assertEquals("Tiles", entry.job.inventory.single().name)
        assertEquals("42.50", entry.job.inventory.single().price)
        assertEquals(listOf("saved-photo"), entry.job.photos)
        assertEquals(entry, JobStore(context).recycled().single())
        assertEquals(RecycleRestoreResult.RESTORED, store.restoreRecycled(entry.id))
        assertEquals(entry.job.inventory, store.jobs().single().inventory)
    }

    @Test fun draftsPreserveOptionalPricesAndProgress() = isolated { context ->
        val draft = Job(inventory = listOf(InventoryItem("Tiles", "24", "Blue", "120"), InventoryItem("Delivery")))
        val store = JobStore(context)
        store.saveDraft(draft)
        store.saveDraftStep(draft.id, 4)
        val reopened = JobStore(context)
        assertEquals(draft, reopened.drafts().single())
        assertEquals(4, reopened.draftStep(draft.id))
        reopened.recycle(draft.id, true)
        val entry = reopened.recycled().single()
        assertEquals(RecycleRestoreResult.RESTORED, reopened.restoreRecycled(entry.id))
        assertEquals(draft, reopened.drafts().single())
    }

    @Test fun qrPricesAreOmittedByDefaultAndRoundTripOnlyWhenIncluded() {
        val job = Job(inventory = listOf(InventoryItem("Tiles", "24", "Blue", "123.45")))
        val ordinary = JSONObject(sharePayload(job)).getJSONObject("job")
        assertFalse(ordinary.getJSONArray("inventory").getJSONObject(0).has("price"))
        assertEquals("", ordinary.toJob().inventory.single().price)
        val priced = JSONObject(sharePayload(job, true)).getJSONObject("job")
        assertEquals(job.inventory, priced.toJob().inventory)
        assertFalse(priced.has("photos"))
        // Older QR codes have no material IDs or prices and are still readable.
        priced.getJSONArray("inventory").getJSONObject(0).apply { remove("id"); remove("price") }
        val old = priced.toJob().inventory.single()
        assertEquals("Tiles", old.name)
        assertEquals("", old.price)
    }
}
