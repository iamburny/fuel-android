package uk.co.fuelprices.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Station & Price DTOs ─────────────────────────────────

@Serializable
data class StationDto(
    val id: Int,
    @SerialName("gov_id") val govId: String,
    val name: String,
    val brand: String? = null,
    val operator: String? = null,
    @SerialName("address_line1") val addressLine1: String? = null,
    val town: String? = null,
    val postcode: String? = null,
    val latitude: Double,
    val longitude: Double,
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
