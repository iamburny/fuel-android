package uk.co.fuelprices.testutil

import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.PriceDto
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.db.FuelPriceEntity
import uk.co.fuelprices.data.db.StationEntity
import uk.co.fuelprices.data.db.StationWithPrices

/** Shared station fixtures for :core's tests — lives here rather than duplicated per test file so
 *  a future required-field addition to StationDto/StationEntity only needs updating once. Not
 *  shared with :app's tests (that would need the java-test-fixtures plugin to cross the module
 *  boundary); see app/src/test/.../testutil/StationFixtures.kt for that module's equivalent. */

fun testStationDto(
    id: Int = 1,
    name: String = "Test Station",
    distanceMiles: Double? = null,
    prices: List<PriceDto> = emptyList(),
): StationDto = StationDto(
    id = id,
    govId = "gov-$id",
    name = name,
    latitude = 51.5,
    longitude = -0.1,
    distanceMiles = distanceMiles,
    prices = prices,
)

fun testPriceDto(fuelType: String, pricePence: Double) =
    PriceDto(fuelType = fuelType, pricePence = pricePence, reportedAt = "2026-01-01T00:00:00Z")

fun testNationalAverageDto(fuelType: String, avgPricePence: Double, stationCount: Int = 1) =
    NationalAverageDto(fuelType, avgPricePence, avgPricePence, avgPricePence, stationCount, "2026-01-01T00:00:00Z")

fun testStationWithPrices(
    id: Int = 1,
    name: String = "Test Station",
    prices: List<FuelPriceEntity> = listOf(testFuelPriceEntity(id)),
) = StationWithPrices(
    station = StationEntity(
        id = id,
        govId = "gov-$id",
        name = name,
        brand = null,
        operator = null,
        addressLine1 = null,
        addressLine2 = null,
        town = null,
        county = null,
        postcode = null,
        phone = null,
        latitude = 51.5,
        longitude = -0.1,
        amenitiesJson = null,
        openingHoursJson = null,
    ),
    prices = prices,
)

fun testFuelPriceEntity(stationId: Int, fuelType: String = "E10", pricePence: Double = 140.0) =
    FuelPriceEntity(stationId = stationId, fuelType = fuelType, pricePence = pricePence, reportedAt = "2026-01-01T00:00:00Z")
