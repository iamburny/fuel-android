package uk.co.fuelprices.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.PriceDto
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.UserPreferences
import kotlin.math.abs

/**
 * Fixtures deliberately mirror fuel-ios's FuelTrackerTests/FuelCostCalculatorTests.swift
 * (same coordinates/prices/tolerances) so the two platforms are provably testing the same
 * behaviour, not just superficially similar behaviour.
 */
class FuelCostCalculatorTest {

    @Test
    fun `haversineMiles returns zero for the same point`() {
        assertEquals(0.0, haversineMiles(51.5, -0.1, 51.5, -0.1), 0.0)
    }

    @Test
    fun `haversineMiles returns known distance London to Manchester`() {
        val distance = haversineMiles(51.5074, -0.1278, 53.4808, -2.2426)
        assertTrue("expected ~163mi, got $distance", distance in 155.0..175.0)
    }

    @Test
    fun `estimateDriveCostPounds scales with distance and price`() {
        val expected = (140.0 / 100.0 * 4.546) / 40.0 * 10.0
        val actual = estimateDriveCostPounds(distanceMiles = 10.0, mpg = 40.0, pricePence = 140.0)
        assertTrue(abs(actual - expected) < 0.0001)
    }

    @Test
    fun `estimateNetSavingsPounds returns null without MPG`() {
        val station = stationFixture(distanceMiles = 1.0, priceE10 = 130.0)
        val averages = averagesFixture(e10 = 140.0, e5 = 120.0, b7Standard = 150.0)
        val prefs = UserPreferences(fuelType = "E10", mpg = null, tankCapacityLitres = 50.0)

        assertNull(estimateNetSavingsPounds(station, averages, prefs))
    }

    @Test
    fun `estimateNetSavingsPounds is positive when station is cheaper than average`() {
        val station = stationFixture(distanceMiles = 1.0, priceE10 = 100.0)
        val averages = averagesFixture(e10 = 200.0, e5 = 90.0, b7Standard = 210.0, stationCount = 100)
        val prefs = UserPreferences(fuelType = "E10", mpg = 60.0, tankCapacityLitres = 50.0)

        val savings = estimateNetSavingsPounds(station, averages, prefs)
        assertTrue(savings != null && savings > 0)
    }

    @Test
    fun `estimateNetSavingsPounds returns null when station has no distance`() {
        val station = stationFixture(distanceMiles = null, priceE10 = 100.0)
        val averages = averagesFixture(e10 = 200.0, e5 = 90.0, b7Standard = 210.0)
        val prefs = UserPreferences(fuelType = "E10", mpg = 60.0, tankCapacityLitres = 50.0)

        assertNull(estimateNetSavingsPounds(station, averages, prefs))
    }

    private fun stationFixture(distanceMiles: Double?, priceE10: Double) = StationDto(
        id = 1,
        govId = "gov-1",
        name = "Test Station",
        latitude = 51.5,
        longitude = -0.1,
        distanceMiles = distanceMiles,
        prices = listOf(PriceDto(fuelType = "E10", pricePence = priceE10, reportedAt = "2026-01-01T00:00:00Z")),
    )

    private fun averagesFixture(
        e10: Double,
        e5: Double,
        b7Standard: Double,
        stationCount: Int = 1,
    ) = listOf(
        NationalAverageDto("E10", e10, e10, e10, stationCount, "2026-01-01T00:00:00Z"),
        NationalAverageDto("E5", e5, e5, e5, stationCount, "2026-01-01T00:00:00Z"),
        NationalAverageDto("B7_STANDARD", b7Standard, b7Standard, b7Standard, stationCount, "2026-01-01T00:00:00Z"),
    )
}
