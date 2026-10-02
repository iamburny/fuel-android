package uk.co.fuelprices.data.api

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// ── Fuel type constants ─────────────────────────────────

object FuelTypes {
    val ALL = listOf("E10", "E5", "B7_STANDARD", "B7_PREMIUM", "B10", "HVO")

    val SHORT_LABELS = mapOf(
        "E10" to "E10",
        "E5" to "E5",
        "B7_STANDARD" to "Diesel",
        "B7_PREMIUM" to "Super Diesel",
        "B10" to "B10",
        "HVO" to "HVO",
    )

    val LONG_LABELS = mapOf(
        "E10" to "Unleaded (E10)",
        "E5" to "Super Unleaded (E5)",
        "B7_STANDARD" to "Diesel (B7)",
        "B7_PREMIUM" to "Premium Diesel (B7)",
        "B10" to "Biodiesel (B10)",
        "HVO" to "HVO Diesel",
    )

    // Loosely follows real UK fuel pump nozzle colour conventions (green = unleaded,
    // black = diesel) so the in-app colour coding matches what's printed on the pump.
    val COLORS = mapOf(
        "E10" to Color(0xFF22C55E),         // green — unleaded
        "E5" to Color(0xFF3B82F6),          // blue — super unleaded
        "B7_STANDARD" to Color(0xFF111827), // near-black — diesel
        "B7_PREMIUM" to Color(0xFF4B5563),  // dark grey — premium/super diesel
        "B10" to Color(0xFFA855F7),         // purple — biodiesel
        "HVO" to Color(0xFF14B8A6),         // teal — HVO
    )

    fun shortLabel(code: String) = SHORT_LABELS[code] ?: code
    fun longLabel(code: String) = LONG_LABELS[code] ?: code
    fun color(code: String) = COLORS[code] ?: Color(0xFF9CA3AF)
}

// ── Station & Price DTOs ─────────────────────────────────

// The backend stores `amenities` as whatever JSON the Gov Fuel Finder ingestion produced —
// sometimes a flat array of enabled amenity keys (`["adblue_packaged", "car_wash"]`), sometimes
// an object of key -> boolean. It's never guaranteed to match a fixed schema, so this is read as
// a raw JsonElement rather than a typed object (a strict object type throws on the array shape).
private val AMENITY_LABELS = mapOf(
    "adblue_pumps" to "AdBlue Pumps",
    "adblue_packaged" to "AdBlue Packaged",
    "lpg_pumps" to "LPG",
    "car_wash" to "Car Wash",
    "air_pump_or_screenwash" to "Air / Screenwash",
    "water_filling" to "Water",
    "twenty_four_hour_fuel" to "24-Hour Fuel",
    "customer_toilets" to "Toilets",
)

private fun prettifyAmenityKey(key: String): String =
    AMENITY_LABELS[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }

fun JsonElement?.toAmenitiesDisplayList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(::prettifyAmenityKey)
    is JsonObject -> entries
        .filter { (_, v) -> (v as? JsonPrimitive)?.booleanOrNull == true }
        .map { (k, _) -> prettifyAmenityKey(k) }
    else -> emptyList()
}

@Serializable
data class DayHoursDto(
    val open: String? = null,
    val close: String? = null,
    @SerialName("is_24_hours") val is24Hours: Boolean? = null,
)

@Serializable
data class BankHolidayDto(
    val type: String? = null,
    @SerialName("open_time") val openTime: String? = null,
    @SerialName("close_time") val closeTime: String? = null,
    @SerialName("is_24_hours") val is24Hours: Boolean? = null,
)

@Serializable
data class UsualDaysDto(
    val monday: DayHoursDto? = null,
    val tuesday: DayHoursDto? = null,
    val wednesday: DayHoursDto? = null,
    val thursday: DayHoursDto? = null,
    val friday: DayHoursDto? = null,
    val saturday: DayHoursDto? = null,
    val sunday: DayHoursDto? = null,
) {
    fun asList(): List<Pair<String, DayHoursDto?>> = listOf(
        "Monday" to monday, "Tuesday" to tuesday, "Wednesday" to wednesday,
        "Thursday" to thursday, "Friday" to friday,
        "Saturday" to saturday, "Sunday" to sunday,
    )
}

@Serializable
data class OpeningHoursDto(
    @SerialName("usual_days") val usualDays: UsualDaysDto? = null,
    @SerialName("bank_holidays") val bankHolidays: List<BankHolidayDto>? = null,
)

@Serializable
data class StationDto(
    val id: Int,
    @SerialName("gov_id") val govId: String,
    val name: String,
    val brand: String? = null,
    val operator: String? = null,
    val phone: String? = null,
    @SerialName("address_line1") val addressLine1: String? = null,
    @SerialName("address_line2") val addressLine2: String? = null,
    val town: String? = null,
    val county: String? = null,
    val postcode: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("temporary_closure") val temporaryClosure: Boolean = false,
    @SerialName("is_motorway") val isMotorway: Boolean = false,
    @SerialName("is_supermarket") val isSupermarket: Boolean = false,
    val amenities: JsonElement? = null,
    @SerialName("opening_hours") val openingHours: OpeningHoursDto? = null,
    @SerialName("distance_miles") val distanceMiles: Double? = null,
    val prices: List<PriceDto> = emptyList(),
)

@Serializable
data class PriceDto(
    @SerialName("fuel_type") val fuelType: String,
    @SerialName("price_pence") val pricePence: Double,
    @SerialName("reported_at") val reportedAt: String,
)

// ── Response wrappers ────────────────────────────────────

@Serializable
data class StationListResponse(
    val count: Int,
    val stations: List<StationDto>,
)

@Serializable
data class CheapestResponse(
    val results: List<CheapestEntry>,
    @SerialName("discrepancy_report_url") val discrepancyReportUrl: String,
    @SerialName("data_notice") val dataNotice: String,
)

@Serializable
data class CheapestEntry(
    val station: StationDto,
    @SerialName("price_pence") val pricePence: Double,
    @SerialName("distance_miles") val distanceMiles: Double? = null,
)

@Serializable
data class AveragesResponse(
    val averages: List<NationalAverageDto>,
    @SerialName("discrepancy_report_url") val discrepancyReportUrl: String,
    @SerialName("data_notice") val dataNotice: String,
)

@Serializable
data class NationalAverageDto(
    @SerialName("fuel_type") val fuelType: String,
    @SerialName("avg_price_pence") val avgPricePence: Double,
    @SerialName("min_price_pence") val minPricePence: Double,
    @SerialName("max_price_pence") val maxPricePence: Double,
    @SerialName("station_count") val stationCount: Int,
    @SerialName("as_of") val asOf: String,
)

@Serializable
data class TrendsResponse(
    val trend: List<TrendPoint>,
    @SerialName("discrepancy_report_url") val discrepancyReportUrl: String,
    @SerialName("data_notice") val dataNotice: String,
)

@Serializable
data class TrendPoint(
    val date: String,
    @SerialName("avg_price_pence") val avgPricePence: Double,
    @SerialName("min_price_pence") val minPricePence: Double,
    @SerialName("max_price_pence") val maxPricePence: Double,
    val observations: Int,
)

@Serializable
data class HeatmapResponse(
    @SerialName("fuel_type") val fuelType: String,
    @SerialName("national_avg_price_pence") val nationalAvgPricePence: Double,
    @SerialName("cell_size_degrees") val cellSizeDegrees: Double = 0.4,
    val cells: List<HeatmapCell>,
    @SerialName("discrepancy_report_url") val discrepancyReportUrl: String = "",
    @SerialName("data_notice") val dataNotice: String = "",
)

@Serializable
data class HeatmapCell(
    val latitude: Double,
    val longitude: Double,
    @SerialName("avg_price_pence") val avgPricePence: Double,
    @SerialName("delta_pence") val deltaPence: Double,
    @SerialName("delta_percent") val deltaPercent: Double,
    @SerialName("station_count") val stationCount: Int,
)

@Serializable
data class PriceHistoryResponse(
    @SerialName("station_id") val stationId: Int,
    @SerialName("station_name") val stationName: String,
    @SerialName("fuel_type") val fuelType: String,
    val history: List<PriceHistoryPoint>,
)

@Serializable
data class PriceHistoryPoint(
    @SerialName("price_pence") val pricePence: Double,
    @SerialName("reported_at") val reportedAt: String,
)

// ── Auth ─────────────────────────────────────────────────

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class RegisterRequest(val email: String, val password: String)

@Serializable
data class GoogleLoginRequest(@SerialName("id_token") val idToken: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
)

@Serializable
data class UserResponse(val id: Int, val email: String)

// ── Favourites ───────────────────────────────────────────

@Serializable
data class FavouriteDto(
    val id: Int,
    @SerialName("station_id") val stationId: Int,
    @SerialName("fuel_type") val fuelType: String,
    @SerialName("notify_on_drop") val notifyOnDrop: Boolean,
    @SerialName("price_threshold_pence") val priceThresholdPence: Double? = null,
)

@Serializable
data class FavouriteCreateRequest(
    @SerialName("station_id") val stationId: Int,
    @SerialName("fuel_type") val fuelType: String = "E10",
    @SerialName("notify_on_drop") val notifyOnDrop: Boolean = true,
    @SerialName("price_threshold_pence") val priceThresholdPence: Double? = null,
)

// ── Area alerts ───────────────────────────────────────────

@Serializable
data class AlertSubscriptionDto(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    @SerialName("radius_miles") val radiusMiles: Double,
    @SerialName("fuel_type") val fuelType: String,
    val notify: Boolean = true,
    val label: String? = null,
)

@Serializable
data class AlertCreateRequest(
    val latitude: Double,
    val longitude: Double,
    @SerialName("radius_miles") val radiusMiles: Double = 10.0,
    @SerialName("fuel_type") val fuelType: String = "E10",
    val label: String? = null,
)

// ── Discrepancy Report ───────────────────────────────────

@Serializable
data class DiscrepancyReportRequest(
    @SerialName("station_id") val stationId: Int? = null,
    @SerialName("fuel_type") val fuelType: String? = null,
    @SerialName("reported_price_pence") val reportedPricePence: Double? = null,
    @SerialName("expected_price_pence") val expectedPricePence: Double? = null,
    val description: String,
    @SerialName("reporter_email") val reporterEmail: String? = null,
)
