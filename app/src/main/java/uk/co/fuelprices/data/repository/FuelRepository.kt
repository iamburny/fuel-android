package uk.co.fuelprices.data.repository

import uk.co.fuelprices.data.api.*
import uk.co.fuelprices.data.db.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FuelRepository @Inject constructor(
    private val api: FuelPricesApi,
    private val db: FuelDatabase,
    private val tokenStore: TokenStore,
) {
    private val dao = db.stationDao()

    // ── Stations ─────────────────────────────────────────

    suspend fun getNearbyStations(
        lat: Double, lng: Double,
        radiusMiles: Double = 10.0,
        fuelType: String? = null,
    ): StationListResponse {
        return try {
            val response = api.getNearbyStations(lat, lng, radiusMiles, fuelType)
            cacheStations(response.stations)
            response
        } catch (e: Exception) {
            // Fallback to cached data
            val cached = dao.getAllStations()
            StationListResponse(
                count = cached.size,
                stations = cached.map { it.toDto() },
            )
        }
    }

    suspend fun getStation(id: Int): StationDto {
        return try {
            val station = api.getStation(id)
            cacheStations(listOf(station))
            station
        } catch (e: Exception) {
            dao.getStationById(id)?.toDto() ?: throw e
        }
    }

    suspend fun searchStations(query: String): StationListResponse {
        return try {
            api.searchStations(query)
        } catch (e: Exception) {
            val cached = dao.searchStations(query)
            StationListResponse(cached.size, cached.map { it.toDto() })
        }
    }

    // ── Prices ───────────────────────────────────────────

    suspend fun getCheapest(
        fuelType: String = "E10",
        lat: Double? = null, lng: Double? = null,
        radiusMiles: Double = 10.0,
    ): CheapestResponse = api.getCheapest(fuelType, lat, lng, radiusMiles)

    suspend fun getNationalAverages(): AveragesResponse = api.getNationalAverages()

    suspend fun getPriceHistory(stationId: Int, fuelType: String = "E10", days: Int = 30) =
        api.getPriceHistory(stationId, fuelType, days)

    suspend fun getNationalTrends(fuelType: String = "E10", days: Int = 30) =
        api.getNationalTrends(fuelType, days)

    // ── Auth ─────────────────────────────────────────────

    suspend fun login(email: String, password: String): TokenResponse {
        val response = api.login(email, password)
        tokenStore.saveToken(response.accessToken, email)
        return response
    }

    suspend fun register(email: String, password: String): UserResponse =
        api.register(RegisterRequest(email, password))

    suspend fun logout() = tokenStore.clear()

    suspend fun isLoggedIn() = tokenStore.isLoggedIn()

    // ── Favourites ───────────────────────────────────────

    suspend fun getFavourites() = api.getFavourites()

    suspend fun addFavourite(stationId: Int, fuelType: String = "E10") =
        api.addFavourite(FavouriteCreateRequest(stationId, fuelType))

    suspend fun removeFavourite(id: Int) = api.removeFavourite(id)

    // ── Discrepancy ──────────────────────────────────────

    suspend fun reportDiscrepancy(request: DiscrepancyReportRequest) =
        api.reportDiscrepancy(request)

    // ── Cache helpers ────────────────────────────────────

    private suspend fun cacheStations(stations: List<StationDto>) {
        val now = System.currentTimeMillis()
        dao.upsertStations(stations.map {
            StationEntity(
                id = it.id, govId = it.govId, name = it.name,
                brand = it.brand, operator = it.operator,
                addressLine1 = it.addressLine1, town = it.town,
                postcode = it.postcode, latitude = it.latitude,
                longitude = it.longitude, lastFetchedAt = now,
            )
        })
        val prices = stations.flatMap { station ->
            station.prices.map { price ->
                FuelPriceEntity(
                    stationId = station.id,
                    fuelType = price.fuelType,
                    pricePence = price.pricePence,
                    reportedAt = price.reportedAt,
                )
            }
        }
        if (prices.isNotEmpty()) dao.upsertPrices(prices)
    }

    private fun StationWithPrices.toDto() = StationDto(
        id = station.id, govId = station.govId, name = station.name,
        brand = station.brand, operator = station.operator,
        addressLine1 = station.addressLine1, town = station.town,
        postcode = station.postcode, latitude = station.latitude,
        longitude = station.longitude,
        prices = prices.map {
            PriceDto(fuelType = it.fuelType, pricePence = it.pricePence, reportedAt = it.reportedAt)
        },
    )
}
