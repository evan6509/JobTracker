package com.evanchubbuck.jobtracker

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class MaterialsTest {
    @Test fun pricesAreOptionalAndMustBeValidDollars() {
        listOf("", "0", "0.00", "12", "12.3", "12.34", ".50").forEach { assertTrue(it, validMaterialPrice(it)) }
        listOf("-1", "hello", "1.234", "1e3", "$12", "1,000").forEach { assertFalse(it, validMaterialPrice(it)) }
    }

    @Test fun totalUsesTheWholeEntryPriceAndIgnoresUnpricedItems() {
        val items = listOf(InventoryItem("Tiles", "24", price = "100.25"), InventoryItem("Grout", "2", price = "4.75"),
            InventoryItem("Delivery"))
        assertEquals(BigDecimal("105.00"), materialTotal(items))
    }

    @Test fun priceChoicesAreOffByDefaultAndRequireMaterials() {
        var selection = SyncSelection().choose("job", false, true)
        assertFalse(SyncCategory.COSTS in selection.categories("job", false))
        selection = selection.setCategory("job", false, SyncCategory.INVENTORY, false)
            .setCategory("job", false, SyncCategory.COSTS, true)
        assertTrue(SyncCategory.INVENTORY in selection.categories("job", false))
        selection = selection.setCategory("job", false, SyncCategory.INVENTORY, false)
        assertFalse(SyncCategory.COSTS in selection.categories("job", false))
        assertFalse(JobPdfOptions().prices)
    }

    @Test fun materialDetailsAndPricesHaveSeparateChangeTracking() {
        val job = Job(inventory = listOf(InventoryItem("Paint", "2", "Blue", "20")))
        val repriced = job.copy(inventory = listOf(job.inventory.single().copy(price = "25")))
        assertEquals(job.syncValue(SyncCategory.INVENTORY), repriced.syncValue(SyncCategory.INVENTORY))
        assertNotEquals(job.syncValue(SyncCategory.COSTS), repriced.syncValue(SyncCategory.COSTS))
    }
}
