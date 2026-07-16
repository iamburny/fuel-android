package uk.co.fuelprices.data.repository

import uk.co.fuelprices.data.api.*
import uk.co.fuelprices.data.db.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L // 24 hours
private const val MILES_PER_DEGREE_LAT = 69.0
private const val EARTH_RADIUS_MILES = 3958.8

@Singleton
class FuelRepository @Inject constructor(
    private val api: FuelPricesApi,
    private val db: FuelDatabase,
    private val tokenStore: TokenStore,
) {
    private val dao = db.stationDao()

    // ── Stations ─────────────────────────────────────────

    /**
     * Prices don't change often, so this checks the local cache before hitting the network: a
     * cache hit (fresh — within 24h — and covering this area) is returned directly, no network
     * call. Falls through to the network if the cache has nothing fresh for this area, and falls
     * back to (possibly stale) cache if the network call itself fails.
     */
    suspend fun getNearbyStations(
        lat: Double, lng: Double,
        radiusMiles: Double = 10.0,
    ): StationListResponse {
        val cached = getFreshCachedStationsNear(lat, lng, radiusMiles)
        if (cached.isNotEmpty()) {
            return StationListResponse(count = cached.size, stations = cached)
        }

        return try {
            // No fuel_type filter — the backend restricts the prices array to just that fuel
            // type when one's given, which would make the cache incomplete for every other
            // fuel type filter. Fetching everything once lets the cache serve all of them.
            val response = api.getNearbyStations(lat, lng, radiusMiles)
            cacheStations(response.stations)
            response
        } catch (e: Exception) {
            // Network failed and the cache had nothing fresh for this area — fall back to
            // whatever's cached regardless of age, better than nothing.
            val stale = dao.getAllStations().map { it.toDto(lat, lng) }
            StationListResponse(count = stale.size, stations = stale)
        }
    }

    suspend fun getStation(id: Int): StationDto {
        return try {
            val station = api.getStation(id)
            cacheStations(listOf(station))
            station
        } catch (e: Exception) {
            dao.getStationById(id)?.toDto(originLat = null, originLng = null) ?: throw e
        }
    }

    suspend fun searchStations(query: String): StationListResponse {
        return try {
            api.searchStations(query)
        } catch (e: Exception) {
            val cached = dao.searchStations(query)
            StationListResponse(cached.size, cached.map { it.toDto(originLat = null, originLng = null) })
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

    private suspend fun getFreshCachedStationsNear(
        lat: Double, lng: Double, radiusMiles: Double,
    ): List<StationDto> {
        val latDelta = radiusMiles / MILES_PER_DEGREE_LAT
        val lngDelta = radiusMiles / (MILES_PER_DEGREE_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val freshAfter = System.currentTimeMillis() - CACHE_TTL_MILLIS
        return dao.getFreshStationsNear(
            minLat = lat - latDelta, maxLat = lat + latDelta,
            minLng = lng - lngDelta, maxLng = lng + lngDelta,
            freshAfter = freshAfter,
        ).map { it.toDto(lat, lng) }
    }

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

    /** [originLat]/[originLng] recompute distanceMiles client-side — it's relative to the query
     * point, not an intrinsic station property, so it isn't stored on the entity. Pass null when
     * there's no meaningful origin (e.g. a lookup by id or text search). */
    private fun StationWithPrices.toDto(originLat: Double?, originLng: Double?) = StationDto(
        id = station.id, govId = station.govId, name = station.name,
        brand = station.brand, operator = station.operator,
        addressLine1 = station.addressLine1, town = station.town,
        postcode = station.postcode, latitude = station.latitude,
        longitude = station.longitude,
        distanceMiles = if (originLat != null && originLng != null) {
            haversineMiles(originLat, originLng, station.latitude, station.longitude)
        } else null,
        prices = prices.map {
            PriceDto(fuelType = it.fuelType, pricePence = it.pricePence, reportedAt = it.reportedAt)
        },
    )
}

private fun haversineMiles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_MILES * c
}
