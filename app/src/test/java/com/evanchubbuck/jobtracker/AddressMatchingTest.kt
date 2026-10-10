package com.evanchubbuck.jobtracker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressMatchingTest {
    private val point = AddressPoint(42.1, -72.5)
    private fun address(street: String, area: String, country: String = "US") =
        AddressSuggestion(street, area, point, countryCode = country)

    @Test fun streetWordsThatBeginWithUnitAbbreviationsStayInTheSearch() {
        listOf(
            "1234 North Stewart St, Chicago IL",
            "1234 West Florence Ave, Los Angeles CA",
            "1234 South Sterling St, Madison WI",
            "1234 West Aptos St, Santa Cruz CA",
        ).forEach { query -> assertEquals(AddressSearchEntry(query), addressSearchEntry(query)) }
    }

    @Test fun delimitedApartmentAndSuiteMarkersStillPreserveTheUnit() {
        listOf("Apt 2", "Apt. 2", "Apartment 2", "Unit B", "Suite B", "Ste. #B-12",
            "Floor 2", "Fl. 2", "Room 3", "Rm. 3", "#2", "# 2", "Apt#2").forEach { unit ->
            assertEquals(AddressSearchEntry("1234 Example St, Chicago IL", unit),
                addressSearchEntry("1234 Example St $unit, Chicago IL"))
        }
    }

    @Test fun aStewartStreetLookupUsesTheEnteredStreetAndDoesNotAppendABogusUnit() = runBlocking {
        val query = "1234 North Stewart St, Chicago IL"
        val match = address("1234 North Stewart Street", "Chicago, Illinois")
        val phoneQueries = mutableListOf<String>()
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                phoneQueries += query
                return listOf(match)
            }
        }
        val remote = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = emptyList<AddressSuggestion>()
        }
        assertEquals(listOf(match), JobAddressLookup(phone, remote).search(query, null))
        assertEquals(listOf(query), phoneQueries)
    }

    @Test fun aMarylandPhoneResultDoesNotCancelAMassachusettsFallback() = runBlocking {
        val wrong = address("123 Main Street", "Springfield, Maryland")
        val wanted = address("123 Main Street", "Springfield, Massachusetts")
        var remoteCalls = 0
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(wrong)
        }
        val remote = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                remoteCalls++
                return listOf(wanted)
            }
        }
        assertEquals(listOf(wanted, wrong),
            JobAddressLookup(phone, remote).search("123 Main St, Springfield MA", null))
        assertEquals(1, remoteCalls)
    }

    @Test fun postalCodesMatchTheirStateNamesInCommaFreeAddresses() {
        listOf("MD" to "Maryland", "MA" to "Massachusetts", "CT" to "Connecticut",
            "MN" to "Minnesota", "NH" to "New Hampshire", "NY" to "New York",
            "NC" to "North Carolina", "ND" to "North Dakota", "SC" to "South Carolina",
            "SD" to "South Dakota", "WV" to "West Virginia", "DC" to "District of Columbia",
            "PR" to "Puerto Rico").forEach { (code, state) ->
            val row = address("123 Main Street", "Example City, $state")
            assertEquals("Postal code $code", listOf(row),
                numberedAddressSuggestions("123 Main St Example City $code", listOf(row)))
        }
        val maryland = address("123 Main Street", "Springfield, Maryland")
        val massachusetts = address("123 Main Street", "Springfield, Massachusetts")
        assertEquals(listOf(maryland), numberedAddressSuggestions("123 Main St Springfield MD",
            listOf(massachusetts, maryland)))
        assertEquals(listOf(massachusetts), numberedAddressSuggestions("123 Main St Springfield MA",
            listOf(maryland, massachusetts)))
    }

    @Test fun fullStateNamesMatchPostalCodesBeforeZipAndCountry() {
        val wrong = address("123 Main Street", "Example City, NJ 12345-6789, United States")
        val wanted = address("123 Main Street", "Example City, NY 12345-6789, United States")
        assertEquals(listOf(wanted, wrong), nearbyAddressSuggestions(listOf(wrong, wanted), null,
            "123 Main St, Example City New York 12345-6789, United States"))
        assertEquals(listOf(wanted, wrong), nearbyAddressSuggestions(listOf(wrong, wanted), null,
            "123 Main St, Example City NY 12345-6789, United States"))
    }

    @Test fun explicitStateOnlyPostalCodesMatchTheCorrectState() {
        val maryland = address("123 Main Street", "Springfield, Maryland")
        val massachusetts = address("123 Main Street", "Springfield, Massachusetts")
        assertEquals(listOf(massachusetts, maryland), nearbyAddressSuggestions(listOf(maryland, massachusetts), null,
            "123 Main St, MA"))
        assertEquals(listOf(maryland, massachusetts), nearbyAddressSuggestions(listOf(massachusetts, maryland), null,
            "123 Main St, MD"))
        assertEquals(listOf(massachusetts), numberedAddressSuggestions("123 Main St MA", listOf(maryland, massachusetts)))
        assertEquals(listOf(maryland), numberedAddressSuggestions("123 Main St MD", listOf(massachusetts, maryland)))
        val connecticut = address("123 Main Street", "Hartford, Connecticut")
        assertEquals(listOf(connecticut), numberedAddressSuggestions("123 Main St CT", listOf(connecticut)))
    }

    @Test fun neRemainsAStreetDirectionAndMatchesNebraskaAsARegion() {
        val row = address("123 Northeast Main Street", "Omaha, Nebraska")
        assertEquals(listOf(row), numberedAddressSuggestions("123 NE Main St Omaha NE", listOf(row)))
        assertEquals(listOf(row), numberedAddressSuggestions("123 NE Main St", listOf(row)))
        val suffix = address("123 Main Street Northeast", "Omaha, Nebraska")
        assertEquals(listOf(suffix), numberedAddressSuggestions("123 Main St NE Omaha NE", listOf(suffix)))
        val missingDirection = address("123 Main Street", "Omaha, Nebraska")
        assertEquals(listOf(suffix), numberedAddressSuggestions("123 Main St NE", listOf(missingDirection, suffix)))
        assertEquals(listOf(missingDirection), numberedAddressSuggestions("123 Main St, NE", listOf(missingDirection)))
    }

    @Test fun highwayNormalizationDoesNotMoveTheBeginningOfTheRegion() {
        val row = address("123 US-6", "Omaha, Nebraska")
        listOf("US-6", "U.S. Highway 6", "US Hwy 6").forEach { highway ->
            assertEquals(listOf(row), numberedAddressSuggestions("123 $highway Omaha NE", listOf(row)))
        }
        val court = address("123 Main Court", "Hartford, Connecticut")
        assertEquals(listOf(court), numberedAddressSuggestions("123 Main Ct Hartford CT", listOf(court)))
        val highway = address("123 US-6", "Hartford, Connecticut")
        assertEquals(listOf(highway), numberedAddressSuggestions("123 US-6 CT", listOf(highway)))
    }

    @Test fun anUntypedStreetDoesNotCancelTheRequestedCourtFallback() = runBlocking {
        val wrong = address("123 Example", "Hartford, Connecticut")
        val wanted = address("123 Example Court", "Hartford, Connecticut")
        var remoteCalls = 0
        val phone = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?) = listOf(wrong)
        }
        val remote = object : AddressLookup {
            override suspend fun search(query: String, near: AddressPoint?): List<AddressSuggestion> {
                remoteCalls++
                return listOf(wanted)
            }
        }
        assertEquals(listOf(wanted), numberedAddressSuggestions("123 Example CT", listOf(wrong, wanted)))
        assertEquals(listOf(wanted), JobAddressLookup(phone, remote).search("123 Example CT", null))
        assertEquals(1, remoteCalls)
    }

    @Test fun partialCityNamesRemainSearchable() {
        val wanted = address("123 Main Street", "Madison, Wisconsin")
        val wrong = address("123 Main Street", "Milwaukee, Wisconsin")
        assertEquals(listOf(wanted, wrong), nearbyAddressSuggestions(listOf(wrong, wanted), null,
            "123 Main St, Ma"))
        assertEquals(listOf(wanted), numberedAddressSuggestions("123 Main St Mad", listOf(wrong, wanted)))
        assertEquals(listOf(wanted, wrong), nearbyAddressSuggestions(listOf(wrong, wanted), null,
            "123 Main St, Mad Wi"))
        val multiword = address("123 Main Street", "San Marcos, California")
        assertEquals(listOf(multiword), numberedAddressSuggestions("123 Main St San Ma", listOf(multiword)))
    }

    @Test fun nonUsDirectionsAreNotInterpretedAsStateCodes() {
        val foreign = address("123 Main Street", "Example City, Northeast", country = "GB")
        assertEquals(listOf(foreign), numberedAddressSuggestions("123 Main St Example City NE", listOf(foreign)))
        val us = foreign.copy(countryCode = "US")
        assertTrue(numberedAddressSuggestions("123 Main St Example City NE", listOf(us)).isEmpty())
    }
}
