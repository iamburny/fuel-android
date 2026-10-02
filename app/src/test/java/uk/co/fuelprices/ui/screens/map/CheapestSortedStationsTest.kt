package uk.co.fuelprices.ui.screens.map

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.fuelprices.testutil.testPriceDto
import uk.co.fuelprices.testutil.testStationDto

class CheapestSortedStationsTest {

    @Test
    fun `sorts by the selected fuel's price and ranks each station by its unflagged price only`() {
        val state = NearbyUiState(
            selectedFuelType = "E10",
            stations = listOf(
                testStationDto(id = 1, prices = listOf(testPriceDto("E10", 140.0))),
                // Its flagged 90p must not make it look cheapest.
                testStationDto(
                    id = 2,
                    prices = listOf(testPriceDto("E10", 90.0, warning = "unusually_low"), testPriceDto("E10", 145.0)),
                ),
                testStationDto(id = 3, prices = listOf(testPriceDto("E10", 130.0))),
            ),
        )

        assertEquals(listOf(3, 1, 2), state.cheapestSortedStations().map { it.id })
    }

    @Test
    fun `drops stations whose only price for the selected fuel is flagged`() {
        val state = NearbyUiState(
            selectedFuelType = "E10",
            stations = listOf(
                testStationDto(id = 1, prices = listOf(testPriceDto("E10", 140.0))),
                testStationDto(id = 2, prices = listOf(testPriceDto("E10", 299.9, warning = "unusually_high"))),
                testStationDto(id = 3, prices = listOf(testPriceDto("E10", 120.0, warning = "stale"))),
            ),
        )

        assertEquals(listOf(1), state.cheapestSortedStations().map { it.id })
    }

    @Test
    fun `an unknown warning code does not exclude a price`() {
        val state = NearbyUiState(
            selectedFuelType = "E10",
            stations = listOf(
                testStationDto(id = 1, prices = listOf(testPriceDto("E10", 140.0))),
                testStationDto(id = 2, prices = listOf(testPriceDto("E10", 130.0, warning = "some_future_code"))),
            ),
        )

        assertEquals(listOf(2, 1), state.cheapestSortedStations().map { it.id })
    }
}
