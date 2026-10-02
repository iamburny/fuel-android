package uk.co.fuelprices.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import uk.co.fuelprices.data.api.*
import uk.co.fuelprices.data.db.*
import uk.co.fuelprices.util.haversineMiles
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos

private const val CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L // 24 hours
private const val MILES_PER_DEGREE_LAT = 69.0

@Singleton
class FuelRepository @Inject constructor(
    private val api: FuelPricesApi,
    private val db: FuelDatabase,
    private val tokenStore: TokenStore,
) {
    private val dao = db.stationDao()

    // Mirrors the DI Retrofit Json config — used to round-trip the complex station fields
    // (amenities, opening hours) through their JSON-string cache columns.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // Consecutive network failures on the station data path — bumped when a fetch falls back to
    // cache because the network was unreachable, reset to 0 on any successful call. The UI watches
    // this to surface a graceful "can't reach the server" banner once it crosses a threshold,
    // rather than silently showing an empty/stale list.
    private val _apiFailureCount = MutableStateFlow(0)
    val apiFailureCount: StateFlow<Int> = _apiFailureCount.asStateFlow()

    private fun recordApiSuccess() { _apiFailureCount.value = 0 }
    private fun recordApiFailure() { _apiFailureCount.value += 1 }

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
        forceRefresh: Boolean = false,
    ): StationListResponse {
        // forceRefresh (the manual pull-to-refresh) skips the cache entirely and goes straight to
        // the network, so the user always gets live prices on demand — automatic loads stay
        // cache-first below.
        if (!forceRefresh) {
            val cached = getFreshCachedStationsNear(lat, lng, radiusMiles)
            if (cached.isNotEmpty()) {
                return StationListResponse(count = cached.size, stations = cached)
            }
        }

        return try {
            // No fuel_type filter — the backend restricts the prices array to just that fuel
            // type when one's given, which would make the cache incomplete for every other
            // fuel type filter. Fetching everything once lets the cache serve all of them.
            val response = api.getNearbyStations(lat, lng, radiusMiles)
            recordApiSuccess()
            cacheStations(response.stations)
            response
        } catch (e: Exception) {
            recordApiFailure()
            // Network failed and the cache had nothing fresh for this area — fall back to
            // whatever's cached regardless of age, better than nothing.
            val stale = dao.getAllStations().map { it.toDto(lat, lng) }
            StationListResponse(count = stale.size, stations = stale)
        }
    }

    /**
     * Stations within an exact lat/lng box (a map viewport) — always hits the network: every call
     * here comes from a genuine drag to a genuinely new box, so unlike [getNearbyStations] there's
     * no repeat-request case worth short-circuiting. (An earlier cache-first version checked for
     * *any* fresh cached station inside the new box before hitting the network — since a drag's
     * new box nearly always overlaps stations already cached from an earlier load elsewhere, that
     * almost always found a hit and skipped the network call entirely, silently hiding whatever
     * was newly visible at the box's edges.) Network failure still falls back to cache, scoped to
     * the box (via freshAfter = 0) rather than [FuelDatabase]'s `getAllStations()` — that would
     * return arbitrary stations unrelated to the dragged viewport.
     */
    suspend fun getStationsInBounds(
        minLat: Double, maxLat: Double, minLng: Double, maxLng: Double,
    ): StationListResponse {
        return try {
            val response = api.getStationsInBounds(minLat, maxLat, minLng, maxLng)
            recordApiSuccess()
            cacheStations(response.stations)
            response
        } catch (e: Exception) {
            recordApiFailure()
            val stale = dao.getFreshStationsNear(minLat, maxLat, minLng, maxLng, freshAfter = 0L)
                .map { it.toDto(originLat = null, originLng = null) }
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

    suspend fun getHeatmap(fuelType: String = "E10"): HeatmapResponse = api.getHeatmap(fuelType)

    suspend fun getPriceHistory(stationId: Int, fuelType: String = "E10", days: Int = 30) =
        api.getPriceHistory(stationId, fuelType, days)

    suspend fun getNationalTrends(fuelType: String = "E10", days: Int = 30) =
        api.getNationalTrends(fuelType, days)

    // ── Auth ─────────────────────────────────────────────

    suspend fun login(email: String, password: String): TokenResponse {
        try {
            val response = api.login(email, password)
            tokenStore.saveToken(response.accessToken, email)
            return response
        } catch (e: retrofit2.HttpException) {
            throw AuthException.from(e)
        }
    }

    suspend fun register(email: String, password: String): UserResponse {
        try {
            return api.register(RegisterRequest(email, password))
        } catch (e: retrofit2.HttpException) {
            throw AuthException.from(e)
        }
    }

    /** Exchange a Google ID token for the app JWT; [email] is stored for display (the backend
     *  response carries only the token). */
    suspend fun loginWithGoogle(idToken: String, email: String): TokenResponse {
        try {
            val response = api.googleLogin(GoogleLoginRequest(idToken))
            tokenStore.saveToken(response.accessToken, email)
            return response
        } catch (e: retrofit2.HttpException) {
            throw AuthException.from(e)
        }
    }

    /** Ask the backend to email a password-reset link. The endpoint always succeeds (it never
     *  reveals whether the address is registered); the actual reset happens on the web page the
     *  email links to. */
    suspend fun forgotPassword(email: String) {
        try {
            api.forgotPassword(ForgotPasswordRequest(email))
        } catch (e: retrofit2.HttpException) {
            throw AuthException.from(e)
        }
    }

    /** Register this device's FCM token against the logged-in user (call after login). */
    suspend fun registerFcmToken(token: String) = api.updateFcmToken(token)

    suspend fun logout() = tokenStore.clear()

    suspend fun isLoggedIn() = tokenStore.isLoggedIn()

    /** The signed-in user's email for display (null when logged out). */
    suspend fun currentEmail() = tokenStore.getEmail()

    // ── Favourites ───────────────────────────────────────

    suspend fun getFavourites() = api.getFavourites()

    suspend fun addFavourite(stationId: Int, fuelType: String = "E10") =
        api.addFavourite(FavouriteCreateRequest(stationId, fuelType))

    suspend fun removeFavourite(id: Int) = api.removeFavourite(id)

    // ── Area alerts ───────────────────────────────────────

    suspend fun getAlerts() = api.getAlerts()

    suspend fun addAlert(
        latitude: Double,
        longitude: Double,
        radiusMiles: Double = 10.0,
        fuelType: String = "E10",
        label: String? = null,
    ) = api.addAlert(AlertCreateRequest(latitude, longitude, radiusMiles, fuelType, label))

    suspend fun removeAlert(id: Int) = api.removeAlert(id)

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
                addressLine1 = it.addressLine1, addressLine2 = it.addressLine2,
                town = it.town, county = it.county,
                postcode = it.postcode, phone = it.phone,
                latitude = it.latitude, longitude = it.longitude,
                temporaryClosure = it.temporaryClosure,
                isMotorway = it.isMotorway, isSupermarket = it.isSupermarket,
                amenitiesJson = it.amenities?.let { a -> json.encodeToString(JsonElement.serializer(), a) },
                openingHoursJson = it.openingHours?.let { oh -> json.encodeToString(OpeningHoursDto.serializer(), oh) },
                lastFetchedAt = now,
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
        addressLine1 = station.addressLine1, addressLine2 = station.addressLine2,
        town = station.town, county = station.county,
        postcode = station.postcode, phone = station.phone,
        latitude = station.latitude, longitude = station.longitude,
        temporaryClosure = station.temporaryClosure,
        isMotorway = station.isMotorway, isSupermarket = station.isSupermarket,
        amenities = station.amenitiesJson?.let { json.decodeFromString(JsonElement.serializer(), it) },
        openingHours = station.openingHoursJson?.let { json.decodeFromString(OpeningHoursDto.serializer(), it) },
        distanceMiles = if (originLat != null && originLng != null) {
            haversineMiles(originLat, originLng, station.latitude, station.longitude)
        } else null,
        prices = prices.map {
            PriceDto(fuelType = it.fuelType, pricePence = it.pricePence, reportedAt = it.reportedAt)
        },
    )
}
