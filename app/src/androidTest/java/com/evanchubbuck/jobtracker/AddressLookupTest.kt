package com.evanchubbuck.jobtracker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class AddressLookupTest {
    @Test fun matchingAcceptsCommonDirectionsAndHighwayFormatsWithoutAcceptingWrongStreets() {
        val point = AddressPoint(41.57, -87.18)
        val west = AddressSuggestion("1234 West Example Street", "Example City", point)
        assertEquals(listOf(west), numberedAddressSuggestions("1234 Example St", listOf(west)))
        assertEquals(listOf(west), numberedAddressSuggestions("1234 W Example St, Example City", listOf(west)))
        assertTrue(numberedAddressSuggestions("1234 E Example St", listOf(west)).isEmpty())
        assertTrue(numberedAddressSuggestions("1234 Other St", listOf(west)).isEmpty())
        val highway = AddressSuggestion("1234 US-6", "Example City", point)
        assertEquals(listOf(highway), numberedAddressSuggestions("1234 U.S. Highway 6 Example City", listOf(highway)))
        assertEquals(listOf(highway), numberedAddressSuggestions("1234 US Hwy 6", listOf(highway)))
    }

    @Test fun streetRetryExpandsAnAbbreviatedSuffixWithoutChangingTheStreetName() {
        assertEquals("Hickory Street", expandFinalStreetType("Hickory St"))
        assertEquals("St John Avenue", expandFinalStreetType("St John Ave."))
        assertEquals("Hickory St Portage", expandFinalStreetType("Hickory St Portage"))
    }

    @Test fun apartmentAndSuiteDetailsStayInSelectedAddresses() = runBlocking {
        assertEquals(AddressSearchEntry("1234 Example St, Example City", "Apt 2"),
            addressSearchEntry("1234 Example St, Apt 2, Example City"))
        assertEquals(AddressSearchEntry("1234 Example St, Example City", "Suite B"),
            addressSearchEntry("1234 Example St Suite B, Example City"))
        assertEquals(AddressSearchEntry("1234 Example St", "#2"),
            addressSearchEntry("1234 Example St #2"))
        assertEquals(AddressSearchEntry("1234 Example St Portage IN", "Apt 2"),
            addressSearchEntry("1234 Example St Apt 2 Portage IN"))
        assertEquals(AddressSearchEntry("1234 Suite 2"), addressSearchEntry("1234 Suite 2"))

        val point = AddressPoint(41.57, -87.18)
        val exact = AddressSuggestion("1234 Example Street", "Example City", point)
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(exact)
        }
        val empty = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()
        }
        val result = JobAddressLookup(phone, empty).search("1234 Example St Apt 2, Example City", null).single()
        assertEquals("1234 Example Street Apt 2, Example City", result.address)
        assertEquals("1234", result.houseNumber)
        assertFalse(result.numberFromEntry)
    }

    @Test fun aStreetRetryRequestsStreetsWithoutHouseResults() {
        val near = AddressPoint(41.57, -87.18)
        val full = photonSearchUri("1234 Example St", near, "US", streetOnly = false)
        val street = photonSearchUri("Example Street", near, "US", streetOnly = true)
        assertEquals(listOf("house", "street"), full.getQueryParameters("layer"))
        assertEquals(listOf("street"), street.getQueryParameters("layer"))
        assertEquals("US", street.getQueryParameter("countrycode"))
        assertEquals("41.57", street.getQueryParameter("lat"))
    }

    @Test fun missingHouseNumbersRetryTheStreetAndKeepTheNumberUnconfirmed() = runBlocking {
        val point = AddressPoint(41.57, -87.18)
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) =
                if (query.startsWith("1234")) emptyList()
                else listOf(AddressSuggestion("999 Example Street", "Example City", point))

        }
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()

        }
        val result = JobAddressLookup(phone, photon).search("1234 Example St", null).single()
        assertEquals("1234 Example Street", result.street)
        assertTrue(result.numberFromEntry)
        assertEquals("", result.houseNumber)
    }

    @Test fun bothAddressServicesExcludeForeignAndUnknownCountries() {
        val response = JSONObject("""{"features":[
          {"properties":{"housenumber":"1234","street":"Main Street","countrycode":"us"},"geometry":{"coordinates":[-89.38,43.07]}},
          {"properties":{"housenumber":"1234","street":"شارع تجريبي","countrycode":"SA"},"geometry":{"coordinates":[46.7,24.7]}},
          {"properties":{"housenumber":"1234","street":"Queen Street","countrycode":"CA"},"geometry":{"coordinates":[-79.4,43.6]}},
          {"properties":{"housenumber":"1234","street":"Unknown Street"},"geometry":{"coordinates":[-89.38,43.07]}}
        ]}""")
        assertEquals(listOf("1234 Main Street"), parseAddressSuggestions(response, "US").map { it.street })
        assertEquals(listOf("1234 Queen Street"), parseAddressSuggestions(response, "CA").map { it.street })
        fun row(country: String?) = android.location.Address(java.util.Locale.US).apply {
            countryCode = country; thoroughfare = "Main Street"; subThoroughfare = "1234"
            latitude = 43.07; longitude = -89.38
        }
        val native = parseDeviceAddressSuggestions(listOf(row("us"), row("SA"), row("CA"), row(null)), "US")
        assertEquals(1, native.size)
        assertEquals("US", native.single().countryCode)
        assertEquals("CA", selectAddressCountry("ca", "US"))
        assertEquals("US", selectAddressCountry("", "us"))
        assertEquals("US", selectAddressCountry("invalid", ""))
    }

    @Test fun countryChangesClearOldSearchResults() = runBlocking {
        val point = AddressPoint(43.07, -89.38)
        var country = ""
        var phoneCalls = 0
        var fallbackCountry = ""
        val phone = object : AddressLookup {
            override fun restrictToCountry(countryCode: String) { country = countryCode }
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                phoneCalls++
                return listOf(AddressSuggestion("1234 Main Street", country, point, countryCode = country))
            }
        }
        val photon = object : AddressLookup {
            override fun restrictToCountry(countryCode: String) { fallbackCountry = countryCode }
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()

        }
        val lookup = JobAddressLookup(phone, photon, "US")
        assertEquals("US", lookup.search("1234 Main", null).single().countryCode)
        lookup.search("1234 Main", null)
        assertEquals(1, phoneCalls)
        lookup.restrictToCountry("CA")
        assertEquals("CA", fallbackCountry)
        assertEquals("CA", lookup.search("1234 Main", null).single().countryCode)
        assertEquals(2, phoneCalls)
    }

    @Test fun numberedQueriesPreferMatchingAddressesAndPreserveUnconfirmedNumbers() {
        val point = AddressPoint(43.07, -89.38)
        val street = AddressSuggestion("Example Avenue", "Example City", point, houseNumber = "")
        val exact = AddressSuggestion("1234 Example Avenue", "Example City", point)
        val otherNumber = AddressSuggestion("1235 Example Avenue", "Example City", point)
        val wrongStreet = AddressSuggestion("1234 Main Street", "Example City", point)
        val rows = numberedAddressSuggestions("1234 Example Ave", listOf(street, otherNumber, wrongStreet, exact))
        assertEquals(listOf(exact), rows)
        val fallback = numberedAddressSuggestions("1234 Example Avenue", listOf(street)).single()
        assertEquals("1234 Example Avenue, Example City", fallback.address)
        assertTrue(fallback.numberFromEntry)
        assertEquals("", fallback.houseNumber)
        assertEquals("N1234", typedHouseNumber("N1234 County Road A"))
        assertEquals("12 1/2", typedHouseNumber("12 1/2 Main Street"))
    }

    @Test fun nearbyMatchesWinWithinTheSameConfidenceLevel() {
        val near = AddressPoint(41.6, -87.2)
        val far = AddressSuggestion("1234 Example Avenue", "Far City", AddressPoint(43.1, -89.4))
        val nearby = AddressSuggestion("1234 Example Avenue", "Nearby City", AddressPoint(41.61, -87.21))
        val streetOnly = AddressSuggestion("1234 Example Avenue", "Next Door", near,
            houseNumber = "", numberFromEntry = true)
        assertEquals(listOf(nearby, streetOnly, far), nearbyAddressSuggestions(listOf(far, streetOnly, nearby), near))
        assertEquals(listOf(far, nearby, streetOnly), nearbyAddressSuggestions(listOf(far, streetOnly, nearby), null))
        assertEquals(listOf(far, nearby), nearbyAddressSuggestions(listOf(nearby, far), near,
            "1234 Example Avenue, Far City"))
    }

    @Test fun nearbyGeocoderBoundsDoNotConstrainAnEnteredCity() {
        val near = AddressPoint(41.57, -87.18)
        assertNotNull(nearbyGeocoderBounds("3103 Hickory St", near))
        assertNull(nearbyGeocoderBounds("3103 Hickory St, Indianapolis IN", near))
        assertNull(nearbyGeocoderBounds("3103 Hickory St Indianapolis IN", near))
        assertNull(nearbyGeocoderBounds("3103 Hickory St Indianapolis", near))
    }

    @Test fun aNearbyStreetCanOutrankAnExactNumberInAnotherCity() = runBlocking {
        val near = AddressPoint(41.57, -87.18)
        val otherCity = AddressSuggestion("3103 Hickory Street", "Crown Point, Indiana",
            AddressPoint(41.41, -87.36))
        val localStreet = AddressSuggestion("Hickory Street", "Portage, Indiana", near,
            houseNumber = "", countryCode = "US")
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) =
                if (query.startsWith("3103")) listOf(otherCity)
                else listOf(otherCity.copy(street = "Hickory Street", houseNumber = ""))
        }
        var streetRequests = 0
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()
            override suspend fun searchStreet(query: String, near: AddressPoint?): List<AddressSuggestion> {
                streetRequests++
                return listOf(localStreet)
            }
        }
        val suggestions = JobAddressLookup(phone, photon).search("3103 Hickory St", near)
        assertEquals(1, streetRequests)
        assertEquals("Portage, Indiana", suggestions.first().area)
        assertEquals("3103 Hickory Street", suggestions.first().street)
        assertEquals("Crown Point, Indiana", suggestions.last().area)
    }

    @Test fun aCityAfterTheStreetWorksWithoutACommaAndWrongStreetPrefixesAreRejected() {
        val point = AddressPoint(41.6, -87.2)
        val wanted = AddressSuggestion("1234 Example Street", "Portage, Indiana", point)
        val otherCity = AddressSuggestion("1234 Example Street", "Madison, Wisconsin", point)
        val missingStreetType = AddressSuggestion("1234 Example", "Streetville, Indiana", point)
        assertEquals(listOf(wanted), numberedAddressSuggestions("1234 Example St Portage IN",
            listOf(otherCity, missingStreetType, wanted)))
        assertEquals(listOf(wanted, otherCity), numberedAddressSuggestions("1234 Example St",
            listOf(wanted, otherCity, missingStreetType)))
    }

    @Test fun aTypedCityWinsEvenWithoutLocationPermission() {
        val point = AddressPoint(41.6, -87.2)
        val wrongCity = AddressSuggestion("1234 Example Street", "Portage, Wisconsin", point)
        val wanted = AddressSuggestion("1234 Example Street", "Portage, Indiana", point)
        assertEquals(listOf(wanted, wrongCity), nearbyAddressSuggestions(listOf(wrongCity, wanted), null,
            "1234 Example St, Portage IN"))
        val abbreviatedState = wanted.copy(area = "Portage, IN")
        assertEquals(listOf(abbreviatedState, wrongCity), nearbyAddressSuggestions(
            listOf(wrongCity, abbreviatedState), null, "1234 Example St, Portage Indiana"))
    }

    @Test fun aWrongCityFromThePhoneDoesNotPreventTheRightCityFromAppearing() = runBlocking {
        val point = AddressPoint(41.6, -87.2)
        val wrongCity = AddressSuggestion("1234 Example Street", "Portage, Wisconsin", point)
        val wanted = AddressSuggestion("1234 Example Street", "Portage, Indiana", point)
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(wrongCity)
        }
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(wanted)
        }
        assertEquals(listOf(wanted, wrongCity), JobAddressLookup(phone, photon).search(
            "1234 Example St, Portage IN", null))
    }

    @Test fun theRemoteLookupStartsWhileThePhoneLookupIsStillPending() = runBlocking {
        val remoteStarted = CompletableDeferred<Unit>()
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                remoteStarted.await()
                return emptyList()
            }
        }
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                remoteStarted.complete(Unit)
                return listOf(AddressSuggestion("1234 Example Street", "Portage, Indiana", AddressPoint(41.6, -87.2)))
            }
        }
        assertEquals("1234 Example Street", withTimeout(1_500) {
            JobAddressLookup(phone, photon).search("1234 Example St", null).single().street
        })
    }

    @Test fun aNumberedRemoteMatchCanFinishBeforeThePhoneLookup() = runBlocking {
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val exact = AddressSuggestion("1234 Example Street", "Portage, Indiana", AddressPoint(41.6, -87.2))
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(exact)
        }
        assertEquals(listOf(exact), withTimeout(1_500) {
            JobAddressLookup(phone, photon).search("1234 Example St", null)
        })
    }

    @Test fun aStreetSuggestionRemainsAvailableWhenTheRemoteServiceFails() = runBlocking {
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) =
                listOf(AddressSuggestion("Example Street", "Portage, Indiana", AddressPoint(41.6, -87.2), houseNumber = ""))
        }
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                throw AddressLookupException("Search is unavailable")
            }
        }
        val result = JobAddressLookup(phone, photon).search("1234 Example St", null).single()
        assertEquals("1234 Example Street", result.street)
        assertTrue(result.numberFromEntry)
    }

    @Test fun censusRangeResultsHelpOnlyForCompleteUsAddresses() = runBlocking {
        assertFalse(isCompleteUsAddress("5791 Kingman Ave"))
        assertFalse(isCompleteUsAddress("5791 Kingman Ave, Portage"))
        assertTrue(isCompleteUsAddress("5791 Kingman Ave Portage IN"))
        assertTrue(isCompleteUsAddress("5791 Kingman Ave, Portage, Indiana"))
        assertTrue(isCompleteUsAddress("5791 Kingman Ave 46368"))
        assertTrue(isCompleteUsAddress("5791 Kingman Ave, Portage, Indiana 46368, United States"))

        val json = JSONObject("""{"result":{"addressMatches":[{"matchedAddress":"5791 KINGMAN AVE, PORTAGE, IN, 46368",
          "coordinates":{"x":-87.19,"y":41.54},"addressComponents":{"streetName":"KINGMAN",
          "suffixType":"AVE","city":"PORTAGE","state":"IN","zip":"46368"}}]}}""")
        val range = parseCensusAddressSuggestions(json, "5791 Kingman Ave, Portage IN").single()
        assertEquals("Kingman Ave", range.street)
        assertEquals("Portage, IN 46368, United States", range.area)
        assertTrue(range.rangeMatch)
        assertTrue(range.numberFromEntry)
        assertTrue(parseCensusAddressSuggestions(json, "5792 Kingman Ave, Portage IN").isEmpty())

        val empty = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()
        }
        val census = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(range)
        }
        val found = JobAddressLookup(empty, empty, "US", census).search("5791 Kingman Ave, Portage IN", null).single()
        assertEquals("5791 Kingman Ave, Portage, IN 46368, United States", found.address)
        assertTrue(found.rangeMatch)
        val nonUsLookup = CensusAddressLookup().apply { restrictToCountry("CA") }
        assertTrue(nonUsLookup.search("5791 Kingman Ave, Portage IN", null).isEmpty())
    }

    @Test fun anApproximateRangeMatchRanksBelowARecordedAddressAndAboveAStreet() {
        val point = AddressPoint(41.6, -87.2)
        val exact = AddressSuggestion("1234 Example Street", "Portage, Indiana", point)
        val range = AddressSuggestion("1234 Other Street", "Portage, Indiana", point,
            houseNumber = "", numberFromEntry = true, rangeMatch = true)
        val street = AddressSuggestion("1234 Third Street", "Portage, Indiana", point,
            houseNumber = "", numberFromEntry = true)
        assertEquals(listOf(exact, range, street), nearbyAddressSuggestions(listOf(street, range, exact), null))
    }

    @Test fun nearbyStreetAndRangeOutrankAnExactNumberAcrossTheCountry() = runBlocking {
        val near = AddressPoint(41.55, -87.19)
        val far = AddressSuggestion("5791 Kingman Avenue", "Buena Park, California",
            AddressPoint(33.85, -118.0), countryCode = "US")
        val localStreet = AddressSuggestion("Kingman Avenue", "Portage, Indiana 46368, United States",
            near, houseNumber = "", countryCode = "US")
        val localRange = AddressSuggestion("Kingman Ave", "Portage, IN 46368, United States",
            near, houseNumber = "", numberFromEntry = true, countryCode = "US", rangeMatch = true)
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()
        }
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) =
                if (query.startsWith("5791")) listOf(far) else listOf(localStreet)
        }
        val census = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) =
                if (isCompleteUsAddress(query)) listOf(localRange) else emptyList()
        }
        val found = JobAddressLookup(phone, photon, "US", census).search("5791 Kingman Ave", near)
        assertEquals(listOf(true, false, false), found.map { it.rangeMatch })
        assertEquals("Portage, IN 46368, United States", found.first().area)
        assertEquals("Buena Park, California", found.last().area)
    }

    @Test fun phoneAddressCoverageIsUsedBeforeStreetOnlyResultsAndCached() = runBlocking {
        val exact = AddressSuggestion("1234 Example Avenue", "Example City", AddressPoint(43.07, -89.38))
        var phoneCalls = 0
        var photonCalls = 0
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                phoneCalls++; return listOf(exact)
            }

        }
        val photon = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                photonCalls++; return emptyList()
            }

        }
        val lookup = JobAddressLookup(phone, photon)
        assertEquals(listOf(exact), lookup.search("1234 Example Ave", null))
        assertEquals(listOf(exact), lookup.search("1234 Example Ave", null))
        assertEquals(1, phoneCalls)
        assertEquals(0, photonCalls)
    }

    @Test fun formatsRealAddressesWithoutInventingHouseNumbersOrUsingUnrelatedPlaces() {
        val response = JSONObject("""{"features":[
          {"properties":{"housenumber":"123","street":"Main Street","city":"Madison","state":"Wisconsin","postcode":"53703","country":"United States"},"geometry":{"coordinates":[-89.38,43.07]}},
          {"properties":{"housenumber":"123","street":"Main Street","city":"Madison","state":"Wisconsin","postcode":"53703","country":"United States"},"geometry":{"coordinates":[-89.38,43.07]}},
          {"properties":{"type":"street","name":"Sesame Street","city":"Example City"},"geometry":{"coordinates":[-89.4,43.1]}},
          {"properties":{"type":"city","name":"123 City"},"geometry":{"coordinates":[-89.4,43.1]}},
          {"properties":{"street":"Bad coordinate"},"geometry":{"coordinates":[0,200]}}
        ]}""")
        val rows = parseAddressSuggestions(response)
        assertEquals(2, rows.size)
        assertEquals("123 Main Street, Madison, Wisconsin 53703, United States", rows[0].address)
        assertEquals(AddressPoint(43.07, -89.38), rows[0].point)
        assertEquals("Sesame Street, Example City", rows[1].address)
    }
}
