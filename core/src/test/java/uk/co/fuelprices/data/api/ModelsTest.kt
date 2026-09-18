package uk.co.fuelprices.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
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

    private fun parseJson(raw: String): JsonElement = Json.parseToJsonElement(raw)
}
