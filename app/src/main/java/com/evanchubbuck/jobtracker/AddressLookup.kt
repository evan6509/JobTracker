package com.evanchubbuck.jobtracker

import android.content.Context
import android.net.Uri
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class AddressPoint(val latitude: Double, val longitude: Double)
internal data class AddressSuggestion(val street: String, val area: String, val point: AddressPoint,
    val houseNumber: String = typedHouseNumber(street).orEmpty(), val numberFromEntry: Boolean = false,
    val countryCode: String = "", val rangeMatch: Boolean = false) {
    val address: String get() = listOf(street, area).filter { it.isNotBlank() }.joinToString(", ")
}

internal class AddressLookupException(val explanation: String) : IOException()

internal interface AddressLookup {
    suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion>
    suspend fun searchStreet(query: String, near: AddressPoint?): List<AddressSuggestion> = search(query, near)
    fun restrictToCountry(countryCode: String) {}
}

internal fun normalizedAddressCountry(code: String?): String? = code?.trim()?.uppercase(Locale.ROOT)
    ?.takeIf { it in Locale.getISOCountries() }

internal fun selectAddressCountry(networkCountry: String?, regionCountry: String?): String =
    normalizedAddressCountry(networkCountry) ?: normalizedAddressCountry(regionCountry) ?: "US"

/** The mobile network reports the country the phone is in, without requesting GPS. */
internal fun currentAddressCountry(context: Context): String {
    val network = runCatching {
        (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso
    }.getOrNull()
    return selectAddressCountry(network, context.resources.configuration.locales[0]?.country)
}

internal fun typedHouseNumber(query: String): String? =
    Regex("^\\s*((?:[NSEW]\\s*)?\\d+[A-Za-z]?(?:[-/]\\d+[A-Za-z]?)?(?:\\s+1/2)?)(?=\\s|,|$)", RegexOption.IGNORE_CASE)
        .find(query)?.groupValues?.get(1)

internal data class AddressSearchEntry(val lookupQuery: String, val unit: String? = null)

/** Search the street address while keeping an entered apartment or suite in the selected result. */
internal fun addressSearchEntry(query: String): AddressSearchEntry {
    val entered = query.trim()
    val number = typedHouseNumber(entered) ?: return AddressSearchEntry(entered)
    val unitPattern = Regex(
        "(?:\\s+|,\\s*)(?:(?:apt\\.?|apartment|unit|suite|ste\\.?|floor|fl\\.?|room|rm\\.?)(?=\\s|#)\\s*#?\\s*|#\\s*)([A-Za-z0-9][A-Za-z0-9-]{0,15})",
        RegexOption.IGNORE_CASE)
    val match = unitPattern.find(entered) ?: return AddressSearchEntry(entered)
    val before = entered.substring(0, match.range.first)
    if (before.trim().removePrefix(number).trim().isEmpty()) return AddressSearchEntry(entered)
    val after = entered.substring(match.range.last + 1)
    val lookup = (before + after).replace(Regex("\\s+,"), ",")
        .replace(Regex(",\\s*,"), ",").trim().trimEnd(',')
    if (after.isNotBlank() && !after.trimStart().startsWith(',') && !isCompleteUsAddress(lookup))
        return AddressSearchEntry(entered)
    val unit = match.value.trim().trimStart(',').trim().replace(Regex("\\s+"), " ")
    return AddressSearchEntry(lookup, unit)
}

private val roadTypes = setOf("street", "avenue", "road", "drive", "boulevard", "lane", "court", "place", "highway", "parkway", "circle", "trail")

internal fun expandFinalStreetType(query: String): String {
    val suffix = Regex("\\s+(St|Ave|Av|Rd|Dr|Blvd|Ln|Ct|Pl|Hwy|Pkwy|Cir|Trl)\\.?$", RegexOption.IGNORE_CASE)
        .find(query.trim()) ?: return query
    val expanded = when (suffix.groupValues[1].lowercase(Locale.ROOT)) {
        "st" -> "Street"; "ave", "av" -> "Avenue"; "rd" -> "Road"; "dr" -> "Drive"
        "blvd" -> "Boulevard"; "ln" -> "Lane"; "ct" -> "Court"; "pl" -> "Place"
        "hwy" -> "Highway"; "pkwy" -> "Parkway"; "cir" -> "Circle"; "trl" -> "Trail"
        else -> return query
    }
    return query.trim().dropLast(suffix.value.length) + " " + expanded
}

private val addressDirections = mapOf("n" to "north", "s" to "south", "e" to "east", "w" to "west",
    "ne" to "northeast", "nw" to "northwest", "se" to "southeast", "sw" to "southwest")

private fun addressWords(value: String): List<String> = value.lowercase(Locale.ROOT)
    .replace(Regex("\\bu\\.?\\s*s\\.?\\s*(?:highway|hwy|route|rte)?\\s*-?\\s*(\\d+)\\b"), "us highway $1")
    .split(Regex("[^\\p{L}\\p{N}]+"))
    .filter { it.isNotBlank() }.map {
        when (it) {
            "ave", "av" -> "avenue"; "st" -> "street"; "rd" -> "road"; "dr" -> "drive"
            "blvd" -> "boulevard"; "ln" -> "lane"; "ct" -> "court"; "pl" -> "place"
            "hwy" -> "highway"; "pkwy" -> "parkway"; "cir" -> "circle"; "trl" -> "trail"
            else -> addressDirections[it] ?: it
        }
    }

private val usAddressStateNames = mapOf(
    "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
    "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
    "DC" to "District of Columbia", "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii",
    "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
    "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
    "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
    "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
    "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
    "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
    "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
    "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
    "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
    "WI" to "Wisconsin", "WY" to "Wyoming", "PR" to "Puerto Rico", "GU" to "Guam",
    "VI" to "Virgin Islands", "AS" to "American Samoa", "MP" to "Northern Mariana Islands",
)

/** Expand a U.S. region suffix before prefix matching, keeping partial city names searchable. */
private fun areaWords(value: String, countryCode: String, result: Boolean): List<String> {
    val country = normalizedAddressCountry(countryCode)
    if (country != null && country != "US") return addressWords(value)
    val enteredWords = value.split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotBlank() }
    val words = enteredWords.map { it.lowercase(Locale.ROOT) }
    var regionEnd = words.size
    val countrySuffix = listOf(listOf("united", "states", "of", "america"),
        listOf("united", "states"), listOf("usa"), listOf("us"))
        .firstOrNull { words.takeLast(it.size) == it }
    if (countrySuffix != null) regionEnd -= countrySuffix.size
    if (regionEnd >= 2 && words[regionEnd - 1].matches(Regex("\\d{4}")) &&
        words[regionEnd - 2].matches(Regex("\\d{5}"))) regionEnd -= 2
    else if (regionEnd > 0 && words[regionEnd - 1].matches(Regex("\\d{5}"))) regionEnd--
    val stateIndex = regionEnd - 1
    // A lone "Ma" can begin Madison; an explicit uppercase "MA" denotes a state.
    if (stateIndex < 0 || (!result && stateIndex == 0 &&
            enteredWords[0] != enteredWords[0].uppercase(Locale.ROOT))) return addressWords(value)
    val state = usAddressStateNames[words[stateIndex].uppercase(Locale.ROOT)] ?: return addressWords(value)
    val expanded = words.take(stateIndex) + state + words.drop(stateIndex + 1)
    return addressWords(expanded.joinToString(" "))
}

private fun areaMatches(requested: String, area: String, countryCode: String = ""): Boolean {
    val wanted = areaWords(requested, countryCode, result = false)
    if (wanted.isEmpty()) return true
    val cityPrefix = addressWords(requested)
    val city = addressWords(area.substringBefore(','))
    // "San Ma" can still be a partial city name, rather than San, Massachusetts.
    if (',' in area && (cityPrefix.size > 1 || cityPrefix == wanted) && cityPrefix.size <= city.size &&
        city.take(cityPrefix.size).zip(cityPrefix).all { (actual, typed) -> actual.startsWith(typed) }) return true
    return areaWords(area, countryCode, result = true).windowed(wanted.size).any { found ->
        found.zip(wanted).all { (actual, typed) ->
            actual.startsWith(typed)
        }
    }
}

private fun addressDistanceKm(point: AddressPoint, origin: AddressPoint): Double {
    val latitude = Math.toRadians(point.latitude - origin.latitude)
    val longitude = Math.toRadians(point.longitude - origin.longitude)
    val start = Math.toRadians(origin.latitude)
    val end = Math.toRadians(point.latitude)
    val arc = sin(latitude / 2) * sin(latitude / 2) +
        cos(start) * cos(end) * sin(longitude / 2) * sin(longitude / 2)
    return 12_742.0 * asin(sqrt(arc.coerceIn(0.0, 1.0)))
}

private fun hasStreetType(query: String, number: String): Boolean =
    addressWords(query.trim().removePrefix(number).trimStart().substringBefore(',')).any { it in roadTypes }

/** Keep the original region suffix: CT and NE have different meanings in streets and areas. */
private fun addressAreaTail(query: String, streetWordCount: Int): String {
    val streetPrefix = addressWords(query).take(streetWordCount)
    val streetEnd = Regex("[\\p{L}\\p{N}]+").findAll(query).firstOrNull { token ->
        addressWords(query.substring(0, token.range.last + 1)) == streetPrefix
    }?.range?.last ?: return ""
    return query.substring(streetEnd + 1).trim()
}

/** Keep only matching numbered results; street fallbacks retain, but do not verify, the typed number. */
internal fun numberedAddressSuggestions(query: String, rows: List<AddressSuggestion>): List<AddressSuggestion> {
    val number = typedHouseNumber(query) ?: return rows
    fun normalizeNumber(value: String) = value.filterNot(Char::isWhitespace).lowercase()
    val streetEntry = query.trim().removePrefix(number).trimStart().substringBefore(',')
    val queryStreet = addressWords(streetEntry)
    val directions = addressDirections.values.toSet()
    return rows.mapNotNull { row ->
        val streetName = row.street.removePrefix(row.houseNumber).trimStart()
        val streetWords = addressWords(streetName).let { found ->
            if (found.firstOrNull() in directions && queryStreet.firstOrNull() !in directions) found.drop(1) else found
        }
        val shared = minOf(streetWords.size, queryStreet.size)
        if (queryStreet.isNotEmpty() && (streetWords.isEmpty() ||
            !streetWords.take(shared).zip(queryStreet.take(shared)).all { (found, requested) -> found.startsWith(requested) })) {
            return@mapNotNull null
        }
        if (queryStreet.size > streetWords.size) {
            val areaTail = addressAreaTail(streetEntry, streetWords.size)
            val tailWords = addressWords(areaTail)
            val regionWords = areaWords(areaTail, row.countryCode, result = false)
            val incompleteStreetType = streetWords.none { it in roadTypes } && tailWords.firstOrNull() in roadTypes
            // A bare NE still belongs to the street; a city or comma makes the region explicit.
            val unmatchedDirection = tailWords.size == 1 && tailWords.single() in directions && regionWords != tailWords
            if (incompleteStreetType || unmatchedDirection || regionWords.firstOrNull() in roadTypes ||
                !areaMatches(areaTail, row.area, row.countryCode)) return@mapNotNull null
        }
        when {
            row.houseNumber.isBlank() -> row.copy(street = "$number $streetName", numberFromEntry = true)
            normalizeNumber(row.houseNumber) == normalizeNumber(number) -> row
            else -> null
        }
    }.sortedWith(compareBy<AddressSuggestion> { it.numberFromEntry }.thenBy { !it.rangeMatch })
        .distinctBy { it.address }
}

/** A matching house number or an explicitly entered city takes precedence over proximity. */
internal fun nearbyAddressSuggestions(rows: List<AddressSuggestion>, near: AddressPoint?, query: String = ""): List<AddressSuggestion> {
    val requestedArea = query.substringAfter(',', "").trim()
    fun confidencePenalty(row: AddressSuggestion): Double = when {
        !row.numberFromEntry -> 0.0
        row.rangeMatch -> 10.0
        else -> 20.0
    }
    return rows.withIndex().sortedWith(compareBy<IndexedValue<AddressSuggestion>> {
            requestedArea.isNotEmpty() && !areaMatches(requestedArea, it.value.area, it.value.countryCode)
        }
        .thenBy { confidencePenalty(it.value) + (near?.let { point -> addressDistanceKm(it.value.point, point) } ?: 0.0) }
        .thenBy { it.index }).map { it.value }.take(10)
}

/** The phone's address database can fill coverage gaps in OpenStreetMap house numbers. */
internal class JobAddressLookup(private val phone: AddressLookup, private val photon: AddressLookup,
    countryCode: String = "US", private val range: AddressLookup? = null) : AddressLookup {
    private val cache = linkedMapOf<Pair<String, AddressPoint?>, List<AddressSuggestion>>()
    private data class LookupOutcome(val rows: List<AddressSuggestion>, val error: Exception? = null)
    init { restrictToCountry(countryCode) }

    override fun restrictToCountry(countryCode: String) {
        val country = normalizedAddressCountry(countryCode) ?: return
        cache.clear()
        phone.restrictToCountry(country)
        photon.restrictToCountry(country)
        range?.restrictToCountry(country)
    }
    private suspend fun attempt(block: suspend () -> List<AddressSuggestion>): LookupOutcome = try {
        LookupOutcome(block())
    }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { LookupOutcome(emptyList(), error) }

    override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
        val key = query.trim() to near
        cache[key]?.let { return it }
        val entry = addressSearchEntry(key.first)
        val number = typedHouseNumber(entry.lookupQuery)
        var results = if (number == null || entry.lookupQuery.length <= number.length + 1)
            photon.search(entry.lookupQuery, near)
        else searchNumbered(entry.lookupQuery, number, near)
        results = nearbyAddressSuggestions(results, near, entry.lookupQuery)
        entry.unit?.let { unit ->
            results = results.map { row ->
                if (row.street.endsWith(unit, ignoreCase = true)) row
                else row.copy(street = "${row.street} $unit")
            }
        }
        cache[key] = results
        if (cache.size > 24) cache.remove(cache.keys.first())
        return results
    }

    private suspend fun searchNumbered(query: String, number: String, near: AddressPoint?): List<AddressSuggestion> = coroutineScope {
        val native = async { attempt { phone.search(query, near) } }
        val remote = async {
            // Give a quick on-device match a head start before contacting the public service.
            delay(250)
            attempt { photon.search(query, near) }
        }
        val requestedArea = query.substringAfter(',', "").trim()
        fun hasRelevantNumber(rows: List<AddressSuggestion>) = rows.any {
            !it.numberFromEntry && it.houseNumber.isNotBlank() && areaMatches(requestedArea, it.area, it.countryCode) &&
                (requestedArea.isNotBlank() || near == null || addressDistanceKm(it.point, near) <= 10.0)
        }
        val first = select<Pair<Boolean, LookupOutcome>> {
            native.onAwait { true to it }
            remote.onAwait { false to it }
        }
        val firstRows = numberedAddressSuggestions(query, first.second.rows)
        if (hasRelevantNumber(firstRows)) {
            if (first.first) remote.cancel() else native.cancel()
            return@coroutineScope firstRows
        }
        val second = if (first.first) remote.await() else native.await()
        val nativeRows = (if (first.first) first.second else second).rows
        val remoteOutcome = if (first.first) second else first.second

        var results = numberedAddressSuggestions(query, remoteOutcome.rows + nativeRows)
        if (!hasRelevantNumber(results) && range != null) {
            val rangeRows = numberedAddressSuggestions(query, attempt { range.search(query, near) }.rows)
            results = (rangeRows + results).distinctBy { it.address.lowercase(Locale.ROOT) }
        }
        val shouldRetryStreet = results.isEmpty() || (near != null && requestedArea.isEmpty() &&
            hasStreetType(query, number) && results.none { addressDistanceKm(it.point, near) <= 10.0 })
        var streetLookupError: Exception? = null
        if (shouldRetryStreet) {
            val streetQuery = expandFinalStreetType(query.trim().removePrefix(number).trimStart())
            if (streetQuery.isNotBlank()) {
                fun streetOnly(rows: List<AddressSuggestion>) = rows.map { row ->
                    row.copy(street = row.street.removePrefix(row.houseNumber).trimStart(), houseNumber = "")
                }
                // A source may know the street but have no record of this particular house number.
                val nativeStreet = async { attempt { phone.search(streetQuery, near) } }
                val remoteStreet = async {
                    delay(250)
                    if (remoteOutcome.error == null) attempt { photon.searchStreet(streetQuery, near) }
                    else remoteOutcome
                }
                val nativeMatches = numberedAddressSuggestions(query, streetOnly(nativeStreet.await().rows))
                val usefulNative = nativeMatches.any {
                    if (requestedArea.isNotBlank()) areaMatches(requestedArea, it.area, it.countryCode)
                    else near == null || addressDistanceKm(it.point, near) <= 10.0
                }
                val remoteMatches = if (usefulNative) {
                    remoteStreet.cancel()
                    emptyList()
                } else {
                    val outcome = remoteStreet.await()
                    streetLookupError = outcome.error
                    numberedAddressSuggestions(query, streetOnly(outcome.rows))
                }
                results = (nativeMatches + remoteMatches + results).distinctBy { it.address.lowercase(Locale.ROOT) }
            }
        }
        if (range != null && near != null && requestedArea.isEmpty() && hasStreetType(query, number) &&
            !hasRelevantNumber(results) && results.none { it.rangeMatch && addressDistanceKm(it.point, near) <= 50.0 }) {
            val localStreets = results.filter { it.numberFromEntry && !it.rangeMatch && it.countryCode == "US" &&
                addressDistanceKm(it.point, near) <= 50.0 }.take(2)
            val rangeRows = localStreets.map { street -> async { attempt { range.search(street.address, near) }.rows } }
                .awaitAll().flatten()
            results = (numberedAddressSuggestions(query, rangeRows) + results)
                .distinctBy { it.address.lowercase(Locale.ROOT) }
        }
        if (results.isEmpty()) (remoteOutcome.error ?: streetLookupError)?.let { throw it }
        results
    }
}

/** Photon permits moderate public use. Debouncing and a small cache avoid repeated requests. */
internal class PhotonAddressLookup : AddressLookup {
    private val cache = linkedMapOf<Triple<String, AddressPoint?, Boolean>, List<AddressSuggestion>>()
    private var countryCode = "US"

    override fun restrictToCountry(countryCode: String) {
        val country = normalizedAddressCountry(countryCode) ?: return
        if (country != this.countryCode) cache.clear()
        this.countryCode = country
    }

    override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> =
        searchWithLayers(query, near, streetOnly = false)

    override suspend fun searchStreet(query: String, near: AddressPoint?): List<AddressSuggestion> =
        searchWithLayers(query, near, streetOnly = true)

    private suspend fun searchWithLayers(query: String, near: AddressPoint?, streetOnly: Boolean): List<AddressSuggestion> {
        val key = Triple(query.trim(), near, streetOnly)
        cache[key]?.let { return it }
        return request(photonSearchUri(key.first, near, countryCode, streetOnly), countryCode).also { result ->
            cache[key] = result
            if (cache.size > 24) cache.remove(cache.keys.first())
        }
    }

    private suspend fun request(uri: Uri, countryCode: String? = null): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("User-Agent", "JobTracker-Android (address lookup)")
            setRequestProperty("Accept", "application/json")
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw AddressLookupException(
                    if (connection.responseCode == 429) "Address search is busy. Try again shortly, or type the address."
                    else "Address search is unavailable. You can still type the address.")
                val bytes = connection.inputStream.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 512 * 1024) throw IOException("Address response too large")
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                if (continuation.isActive) continuation.resume(parseAddressSuggestions(JSONObject(bytes), countryCode))
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                connection.disconnect()
            }
        }
    }
}

internal fun photonSearchUri(query: String, near: AddressPoint?, countryCode: String, streetOnly: Boolean): Uri {
    val uri = Uri.parse("https://photon.komoot.io/api/").buildUpon()
        .appendQueryParameter("q", query).appendQueryParameter("limit", "20")
        .appendQueryParameter("countrycode", countryCode)
        .appendQueryParameter("lang", Locale.getDefault().language)
    if (!streetOnly) uri.appendQueryParameter("layer", "house")
    uri.appendQueryParameter("layer", "street")
    near?.let {
        uri.appendQueryParameter("lat", it.latitude.toString()).appendQueryParameter("lon", it.longitude.toString())
    }
    return uri.build()
}

internal fun parseAddressSuggestions(json: JSONObject, countryCode: String? = null): List<AddressSuggestion> {
    val features = json.getJSONArray("features")
    return (0 until features.length()).mapNotNull { index ->
        runCatching {
            val feature = features.getJSONObject(index)
            val properties = feature.getJSONObject("properties")
            val resultCountry = normalizedAddressCountry(properties.optString("countrycode")).orEmpty()
            if (countryCode != null && resultCountry != normalizedAddressCountry(countryCode)) return@runCatching null
            val street = properties.optString("street").ifBlank {
                if (properties.optString("type") == "street") properties.optString("name") else ""
            }.trim()
            if (street.isBlank()) return@runCatching null
            val line = listOf(properties.optString("housenumber").trim(), street)
                .filter { it.isNotBlank() }.joinToString(" ")
            val city = properties.optString("city").ifBlank { properties.optString("town") }
                .ifBlank { properties.optString("village") }.ifBlank { properties.optString("district") }
            val region = listOf(properties.optString("state"), properties.optString("postcode"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val area = listOf(city, region, properties.optString("country"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
            val point = AddressPoint(coordinates.getDouble(1), coordinates.getDouble(0))
            require(point.latitude.isFinite() && point.latitude in -90.0..90.0 &&
                point.longitude.isFinite() && point.longitude in -180.0..180.0)
            AddressSuggestion(line, area, point, houseNumber = properties.optString("housenumber").trim(), countryCode = resultCountry)
        }.getOrNull()
    }.distinctBy { it.address }.take(20)
}
