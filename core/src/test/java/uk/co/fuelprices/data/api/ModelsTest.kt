package uk.co.fuelprices.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun `FuelTypes ALL has the exact expected order`() {
        assertEquals(
            listOf("E10", "E5", "B7_STANDARD", "B7_PREMIUM", "B10", "HVO"),
            FuelTypes.ALL,
        )
    }

    @Test
    fun `toAmenitiesDisplayList handles the array shape`() {
        val amenities = parseJson("""["adblue_packaged", "car_wash", "unknown_key"]""")

        val display = amenities.toAmenitiesDisplayList()

        assertEquals(listOf("AdBlue Packaged", "Car Wash", "Unknown key"), display)
    }

    @Test
    fun `toAmenitiesDisplayList handles the object shape and only includes true values`() {
        val amenities = parseJson(
            """{"adblue_pumps": true, "car_wash": false, "lpg_pumps": true}""",
        )

        val display = amenities.toAmenitiesDisplayList()

        assertEquals(listOf("AdBlue Pumps", "LPG"), display)
    }

    @Test
    fun `toAmenitiesDisplayList returns empty list for null`() {
        assertEquals(emptyList<String>(), (null as JsonElement?).toAmenitiesDisplayList())
    }

    @Test
    fun `PriceDto without a warning field parses as unflagged`() {
        val price = decodePrice("""{"fuel_type": "E10", "price_pence": 139.9, "reported_at": "2026-01-01T00:00:00Z"}""")

        assertNull(price.warning)
        assertNull(price.priceWarning)
        assertFalse(price.isFlagged)
    }

    @Test
    fun `PriceDto with a null warning parses as unflagged`() {
        val price = decodePrice(priceJson("null"))

        assertNull(price.priceWarning)
        assertFalse(price.isFlagged)
    }

    @Test
    fun `PriceDto maps each known warning code`() {
        assertEquals(PriceWarning.STALE, decodePrice(priceJson("\"stale\"")).priceWarning)
        assertEquals(PriceWarning.UNUSUALLY_LOW, decodePrice(priceJson("\"unusually_low\"")).priceWarning)
        assertEquals(PriceWarning.UNUSUALLY_HIGH, decodePrice(priceJson("\"unusually_high\"")).priceWarning)
        assertTrue(decodePrice(priceJson("\"stale\"")).isFlagged)
    }

    @Test
    fun `PriceDto with an unknown warning code parses and is treated as unflagged`() {
        val price = decodePrice(priceJson("\"some_future_code\""))

        assertEquals("some_future_code", price.warning)
        assertNull(price.priceWarning)
        assertFalse(price.isFlagged)
    }

    @Test
    fun `cheapestUnflaggedPrice skips flagged prices and other fuel types`() {
        val station = StationDto(
            id = 1, govId = "gov-1", name = "Test", latitude = 51.5, longitude = -0.1,
            prices = listOf(
                PriceDto("E10", 99.9, "2026-01-01T00:00:00Z", warning = "unusually_low"),
                PriceDto("E10", 139.9, "2026-01-01T00:00:00Z"),
                PriceDto("E5", 120.0, "2026-01-01T00:00:00Z"),
            ),
        )

        assertEquals(139.9, station.cheapestUnflaggedPrice("E10")!!.pricePence, 0.0)
        assertNull(station.copy(prices = station.prices.take(1)).cheapestUnflaggedPrice("E10"))
    }

    private fun priceJson(warning: String) =
        """{"fuel_type": "E10", "price_pence": 139.9, "reported_at": "2026-01-01T00:00:00Z", "warning": $warning}"""

    // Mirrors the app's API Json configuration.
    private fun decodePrice(raw: String): PriceDto =
        Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromString(PriceDto.serializer(), raw)

    private fun parseJson(raw: String): JsonElement = Json.parseToJsonElement(raw)
}
