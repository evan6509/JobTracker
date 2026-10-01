package com.evanchubbuck.jobtracker

import java.math.BigDecimal

internal fun validMaterialPrice(value: String): Boolean = value.isBlank() ||
    (Regex("(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})").matches(value) &&
        value.toBigDecimalOrNull()?.let { it >= BigDecimal.ZERO } == true)

internal fun displayMaterialPrice(value: String): String = value.toBigDecimalOrNull()?.asMoney() ?: "$value (check price)"

/** Prices cover each whole entry; quantity never multiplies an entered price. */
internal fun materialTotal(items: List<InventoryItem>): BigDecimal = items
    .filter { validMaterialPrice(it.price) }.mapNotNull { it.price.toBigDecimalOrNull() }
    .fold(BigDecimal.ZERO, BigDecimal::add)

internal fun materialSummary(item: InventoryItem): String =
    "${item.quantity.ifBlank { "1" }} × ${item.name.ifBlank { "Unnamed item" }}" +
        (if (item.notes.isBlank()) "" else " · ${item.notes}") +
        (if (item.price.isBlank()) "" else " · ${displayMaterialPrice(item.price)}")
