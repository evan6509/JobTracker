package com.evanchubbuck.jobtracker

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.cos

private val legacyGeocodingExecutor by lazy {
    Executors.newFixedThreadPool(2) { work -> Thread(work, "job-address-lookup").apply { isDaemon = true } }
}

internal data class GeocoderBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** Ask the phone for local matches only when the entry does not name another area. */
internal fun nearbyGeocoderBounds(query: String, near: AddressPoint?): GeocoderBounds? {
    if (near == null || ',' in query || isCompleteUsAddress(query)) return null
    val words = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
    val roadEnd = setOf("st", "street", "ave", "av", "avenue", "rd", "road", "dr", "drive",
        "blvd", "boulevard", "ln", "lane", "ct", "court", "pl", "place", "hwy", "highway",
        "pkwy", "parkway", "cir", "circle", "trl", "trail")
    val streetTypeAt = words.indexOfLast { it in roadEnd }
    if (streetTypeAt >= 0 && streetTypeAt < words.lastIndex) return null
    val longitudeRadius = 0.45 / cos(Math.toRadians(near.latitude))
    if (!longitudeRadius.isFinite()) return null
    val box = GeocoderBounds(near.latitude - 0.45, near.longitude - longitudeRadius,
        near.latitude + 0.45, near.longitude + longitudeRadius)
    return box.takeIf { it.south >= -90 && it.north <= 90 && it.west >= -180 && it.east <= 180 }
}

internal class DeviceAddressLookup(context: Context) : AddressLookup {
    private val context = context.applicationContext
    private var countryCode = "US"

    override fun restrictToCountry(countryCode: String) {
        normalizedAddressCountry(countryCode)?.let { this.countryCode = it }
    }

    override suspend fun search(query: String, near: AddressPoint?) = lookup(query, countryCode, near)

    @Suppress("DEPRECATION")
    private suspend fun lookup(query: String, countryCode: String, near: AddressPoint?): List<AddressSuggestion> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        val bounds = nearbyGeocoderBounds(query, near)
        val addresses = withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                fun finish(rows: List<Address>) {
                    if (continuation.isActive) continuation.resume(rows)
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    val listener = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = finish(addresses)
                        override fun onError(errorMessage: String?) = finish(emptyList())
                    }
                    try {
                        if (bounds == null) geocoder.getFromLocationName(countryQuery(query, countryCode), 10, listener)
                        else geocoder.getFromLocationName(countryQuery(query, countryCode), 10,
                            bounds.south, bounds.west, bounds.north, bounds.east, listener)
                    } catch (_: Exception) { finish(emptyList()) }
                } else {
                    // Never block the editor or wait for a legacy geocoder after cancellation.
                    legacyGeocodingExecutor.execute {
                        if (continuation.isActive) finish(runCatching {
                            if (bounds == null) geocoder.getFromLocationName(countryQuery(query, countryCode), 10).orEmpty()
                            else geocoder.getFromLocationName(countryQuery(query, countryCode), 10,
                                bounds.south, bounds.west, bounds.north, bounds.east).orEmpty()
                        }.getOrDefault(emptyList()))
                    }
                }
            }
        }.orEmpty()
        return parseDeviceAddressSuggestions(addresses, countryCode)
    }

    private fun countryQuery(query: String, countryCode: String): String =
        "$query, ${Locale.Builder().setRegion(countryCode).build().getDisplayCountry(Locale.ENGLISH)}"
}

internal fun parseDeviceAddressSuggestions(addresses: List<Address>, countryCode: String?): List<AddressSuggestion> =
    addresses.mapNotNull { row ->
        val resultCountry = normalizedAddressCountry(row.countryCode).orEmpty()
        if (countryCode != null && resultCountry != countryCode) return@mapNotNull null
        val street = row.thoroughfare?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (!row.hasLatitude() || !row.hasLongitude()) return@mapNotNull null
        val number = row.subThoroughfare.orEmpty()
        val line = listOf(number, street).filter { it.isNotBlank() }.joinToString(" ")
        val area = listOf(row.locality ?: row.subAdminArea,
            listOfNotNull(row.adminArea, row.postalCode).joinToString(" "), row.countryName)
            .filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(", ")
        AddressSuggestion(line, area, AddressPoint(row.latitude, row.longitude), houseNumber = number, countryCode = resultCountry)
    }.distinctBy { it.address }
