package com.evanchubbuck.jobtracker

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val usStateCodes = setOf(
    "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA",
    "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM",
    "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT", "VA", "WA",
    "WV", "WI", "WY", "PR", "GU", "VI", "AS", "MP"
)

private val usStateNames = setOf(
    "alabama", "alaska", "arizona", "arkansas", "california", "colorado", "connecticut", "delaware",
    "district of columbia", "florida", "georgia", "hawaii", "idaho", "illinois", "indiana", "iowa",
    "kansas", "kentucky", "louisiana", "maine", "maryland", "massachusetts", "michigan", "minnesota",
    "mississippi", "missouri", "montana", "nebraska", "nevada", "new hampshire", "new jersey", "new mexico",
    "new york", "north carolina", "north dakota", "ohio", "oklahoma", "oregon", "pennsylvania",
    "rhode island", "south carolina", "south dakota", "tennessee", "texas", "utah", "vermont", "virginia",
    "washington", "west virginia", "wisconsin", "wyoming", "puerto rico", "guam", "virgin islands",
    "american samoa", "northern mariana islands"
)

private fun censusAddressLine(query: String): String = query.trim()
    .replace(Regex(",\\s*(?:United States(?: of America)?|USA|US)$", RegexOption.IGNORE_CASE), "")

/** Address-range lookup is useful only after the user enters enough detail to identify a U.S. street. */
internal fun isCompleteUsAddress(query: String): Boolean {
    val address = censusAddressLine(query)
    if (address.length > 100 || typedHouseNumber(address) == null) return false
    val words = address.split(Regex("[\\s,]+"))
    if (words.size < 4) return false
    val end = address.lowercase(Locale.ROOT)
    val hasZip = words.last().matches(Regex("\\d{5}(?:-\\d{4})?"))
    val stateWord = words.getOrNull(words.lastIndex - if (hasZip) 1 else 0)?.uppercase(Locale.ROOT)
    val withoutZip = if (hasZip) end.removeSuffix(words.last().lowercase(Locale.ROOT)).trim() else end
    return hasZip || (stateWord != null && stateWord in usStateCodes) || usStateNames.any { withoutZip.endsWith(it) }
}

private fun censusName(value: String): String = value.trim().split(Regex("\\s+"))
    .filter { it.isNotBlank() }.joinToString(" ") { word ->
        if (word.length <= 2 && word.all(Char::isLetter)) word.uppercase(Locale.US)
        else word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
    }

/** Census coordinates are interpolated along an address range, so these are approximate suggestions. */
internal fun parseCensusAddressSuggestions(json: JSONObject, query: String): List<AddressSuggestion> {
    val number = typedHouseNumber(query) ?: return emptyList()
    val matches = json.optJSONObject("result")?.optJSONArray("addressMatches") ?: return emptyList()
    return (0 until minOf(matches.length(), 10)).mapNotNull { index ->
        runCatching {
            val match = matches.getJSONObject(index)
            val matchedNumber = typedHouseNumber(match.optString("matchedAddress"))
            if (!number.equals(matchedNumber, ignoreCase = true)) return@runCatching null
            val parts = match.getJSONObject("addressComponents")
            val street = censusName(listOf("preQualifier", "preDirection", "preType", "streetName",
                "suffixType", "suffixDirection", "suffixQualifier")
                .map { parts.optString(it) }.filter { it.isNotBlank() }.joinToString(" "))
            if (street.isBlank()) return@runCatching null
            val city = censusName(parts.optString("city"))
            val state = parts.optString("state").trim().uppercase(Locale.US)
            if (city.isBlank() || state !in usStateCodes) return@runCatching null
            val region = listOf(state, parts.optString("zip").trim()).filter { it.isNotBlank() }.joinToString(" ")
            val coordinates = match.getJSONObject("coordinates")
            val point = AddressPoint(coordinates.getDouble("y"), coordinates.getDouble("x"))
            require(point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                point.longitude.isFinite() && point.longitude in -180.0..180.0)
            AddressSuggestion(street, "$city, $region, United States", point,
                houseNumber = "", numberFromEntry = true, countryCode = "US", rangeMatch = true)
        }.getOrNull()
    }.distinctBy { it.address }
}

internal class CensusAddressLookup : AddressLookup {
    private var countryCode = "US"
    private val cache = linkedMapOf<String, List<AddressSuggestion>>()

    override fun restrictToCountry(countryCode: String) {
        normalizedAddressCountry(countryCode)?.let {
            if (it != this.countryCode) cache.clear()
            this.countryCode = it
        }
    }

    override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
        if (countryCode != "US" || !isCompleteUsAddress(query)) return emptyList()
        val key = censusAddressLine(query)
        cache[key]?.let { return it }
        val uri = Uri.parse("https://geocoding.geo.census.gov/geocoder/locations/onelineaddress").buildUpon()
            .appendQueryParameter("address", key)
            .appendQueryParameter("benchmark", "Public_AR_Current")
            .appendQueryParameter("format", "json").build()
        val result = withTimeoutOrNull(2_500) { request(uri, key) } ?: return emptyList()
        cache[key] = result
        if (cache.size > 24) cache.remove(cache.keys.first())
        return result
    }

    private suspend fun request(uri: Uri, query: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
            setRequestProperty("User-Agent", "JobTracker-Android (address lookup)")
            setRequestProperty("Accept", "application/json")
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Address range search unavailable")
                val body = connection.inputStream.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 256 * 1024) throw IOException("Address response too large")
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                if (continuation.isActive) continuation.resume(parseCensusAddressSuggestions(JSONObject(body), query))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
            }
        }
    }
}
