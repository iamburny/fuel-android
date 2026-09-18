package uk.co.fuelprices.testutil

import uk.co.fuelprices.data.api.PriceDto
import uk.co.fuelprices.data.api.StationDto

/** Shared station fixtures for :app's tests — lives here rather than duplicated per test file so
 *  a future required-field addition to StationDto only needs updating once. Not shared with
 *  :core's tests (that would need the java-test-fixtures plugin to cross the module boundary);
 *  see core/src/test/.../testutil/StationFixtures.kt for that module's equivalent. */

fun testStationDto(
    id: Int = 1,
    name: String = "Test Station",
    prices: List<PriceDto> = emptyList(),
): StationDto = StationDto(
    id = id,
    govId = "gov-$id",
    name = name,
    latitude = 51.5,
    longitude = -0.1,
    prices = prices,
)

fun testPriceDto(fuelType: String, pricePence: Double) =
    PriceDto(fuelType = fuelType, pricePence = pricePence, reportedAt = "2026-01-01T00:00:00Z")
