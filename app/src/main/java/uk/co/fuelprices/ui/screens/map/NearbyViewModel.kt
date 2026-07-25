package uk.co.fuelprices.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLngBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.fuelprices.data.api.PriceDto
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.LocationHelper
import uk.co.fuelprices.util.haversineMiles
import javax.inject.Inject

enum class ListMode { NEARBY, CHEAPEST }

data class NearbyUiState(
    val isLoading: Boolean = true,
    val stations: List<StationDto> = emptyList(),
    val selectedFuelType: String = "E10",
    val radiusMiles: Double = 10.0,
    val mode: ListMode = ListMode.NEARBY,
    val searchQuery: String = "",
    val userLat: Double? = null,
    val userLng: Double? = null,
    val discrepancyReportUrl: String = "",
    val error: String? = null,
    // Stations for whatever map area the user last dragged to — null until the first drag, at
    // which point map pins switch to this instead of the GPS-anchored `stations`. The bottom
    // list panel always keeps using `stations`, unaffected by dragging.
    val viewportStations: List<StationDto>? = null,
    // Bumped only when the map should jump to userLat/userLng — never on every reload, so
    // changing the radius/fuel filter/mode doesn't fight a drag by snapping the camera back.
    val cameraRecenterToken: Int = 0,
    // True once the user has dragged the map away from GPS-center — shows a recenter button.
    val isOffGpsCenter: Boolean = false,
    // True after repeated back-to-back connection failures — drives a graceful "can't reach the
    // server" banner. Clears automatically on the next successful fetch.
    val apiUnreachable: Boolean = false,
)

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val repo: FuelRepository,
    private val locationHelper: LocationHelper,
    private val preferencesStore: UserPreferencesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(NearbyUiState())
    val state: StateFlow<NearbyUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var boundsJob: Job? = null
    private var locationJob: Job? = null

    // Surface the "can't reach the server" banner once at least this many station fetches have
    // failed back-to-back — one transient blip shouldn't nag the user.
    private val failureThreshold = 2

    init {
        // Watch the repository's connection health independently of any single load, so the banner
        // appears/clears no matter which fetch (initial, refresh, drag) tripped it.
        viewModelScope.launch {
            repo.apiFailureCount.collect { count ->
                _state.value = _state.value.copy(apiUnreachable = count >= failureThreshold)
            }
        }

        viewModelScope.launch {
            // Start from the user's saved "usual fuel" preference rather than always defaulting
            // to E10.
            _state.value = _state.value.copy(selectedFuelType = preferencesStore.get().fuelType)

            // Give the permission dialog a brief window to be answered before firing the first
            // request — otherwise we load London (the fallback), render it, then immediately
            // correct to the real location once permission lands, which reads as a jarring
            // flash. If permission's already granted (the common case for returning users) this
            // returns instantly. Capped at 3s so a slow response doesn't stall the screen.
            if (!locationHelper.hasPermission()) {
                withTimeoutOrNull(3_000) { locationHelper.permissionGranted.first() }
            }
            loadNearby()
            startLocationUpdates()

            // Keep listening in case permission lands after our short wait above (e.g. the
            // dialog took longer than 3s to answer, or it's granted later via Settings).
            locationHelper.permissionGranted.collect {
                loadNearby()
                startLocationUpdates()
            }
        }
    }

    /**
     * Subscribes to continuous GPS fixes so the map tracks the user in real time. While the user
     * hasn't dragged away from GPS-center ([NearbyUiState.isOffGpsCenter] is false), each new fix
     * re-centers the camera by bumping [NearbyUiState.cameraRecenterToken]; once they've dragged,
     * we still update the stored location (for the "my location" dot and distances) but leave the
     * camera where they put it. Re-called on permission grant because [LocationHelper.locationUpdates]
     * completes immediately when permission is absent.
     */
    private fun startLocationUpdates() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationHelper.locationUpdates().collect { loc ->
                val s = _state.value
                val prevLat = s.userLat
                val prevLng = s.userLng
                // Ignore sub-30m jitter so the camera doesn't twitch while standing still.
                val moved = prevLat == null || prevLng == null ||
                    haversineMiles(prevLat, prevLng, loc.latitude, loc.longitude) > 0.02
                if (!moved) return@collect
                _state.value = s.copy(
                    userLat = loc.latitude,
                    userLng = loc.longitude,
                    cameraRecenterToken = if (!s.isOffGpsCenter) {
                        s.cameraRecenterToken + 1
                    } else {
                        s.cameraRecenterToken
                    },
                )
            }
        }
    }

    /** Manual refresh — re-acquires GPS and forces a live network reload, bypassing the cache. */
    fun refresh() {
        reload(forceRefresh = true)
    }

    fun loadNearby(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val location = locationHelper.getCurrentLocation()
                val lat = location?.latitude ?: 51.5074  // default: London
                val lng = location?.longitude ?: -0.1278

                // No fuelType here — the repository always caches full price data per station
                // now (see FuelRepository), so switching the fuel filter chip doesn't need a
                // new fetch, just a client-side re-filter for display.
                val response = repo.getNearbyStations(lat, lng, _state.value.radiusMiles, forceRefresh)

                // Only jump the camera to GPS the first time we get a real fix — subsequent
                // reloads (radius/fuel/mode changes) shouldn't yank the map back if the user has
                // since dragged it elsewhere.
                val isFirstFix = _state.value.userLat == null
                _state.value = _state.value.copy(
                    isLoading = false,
                    stations = response.stations,
                    userLat = lat,
                    userLng = lng,
                    cameraRecenterToken = if (isFirstFix) _state.value.cameraRecenterToken + 1 else _state.value.cameraRecenterToken,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /** Called when the map's drag gesture ends, with the newly visible viewport. */
    fun loadStationsInBounds(bounds: LatLngBounds) {
        boundsJob?.cancel()
        boundsJob = viewModelScope.launch {
            _state.value = _state.value.copy(isOffGpsCenter = true)
            try {
                val response = repo.getStationsInBounds(
                    minLat = bounds.southwest.latitude, maxLat = bounds.northeast.latitude,
                    minLng = bounds.southwest.longitude, maxLng = bounds.northeast.longitude,
                )
                _state.value = _state.value.copy(viewportStations = response.stations)
            } catch (e: Exception) {
                // Keep showing whatever was already on the map rather than clearing pins on a
                // transient network failure mid-drag.
            }
        }
    }

    /** Jumps the map back to the user's GPS location and reverts pins to the GPS-anchored set. */
    fun recenterOnGps() {
        boundsJob?.cancel()
        _state.value = _state.value.copy(
            viewportStations = null,
            isOffGpsCenter = false,
            cameraRecenterToken = _state.value.cameraRecenterToken + 1,
        )
    }

    fun setFuelType(type: String) {
        _state.value = _state.value.copy(selectedFuelType = type)
        // Nearby mode already has every fuel type's prices cached/loaded — just re-filter for
        // display. Cheapest mode ranks server-side per fuel type, so that genuinely needs a
        // fresh request.
        if (_state.value.mode == ListMode.CHEAPEST) {
            reload()
        }
    }

    fun setRadius(miles: Double) {
        _state.value = _state.value.copy(radiusMiles = miles)
        reload()
    }

    fun setMode(mode: ListMode) {
        _state.value = _state.value.copy(mode = mode, searchQuery = "")
        searchJob?.cancel()
        reload()
    }

    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        searchJob?.cancel()
        if (query.length < 2) {
            // Revert to normal mode results
            reload()
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = repo.searchStations(query)
                _state.value = _state.value.copy(isLoading = false, stations = response.stations)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadCheapest() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val s = _state.value
                val response = repo.getCheapest(s.selectedFuelType, s.userLat, s.userLng, s.radiusMiles)
                // /api/prices/cheapest's station objects carry no `prices` array — only a
                // top-level price_pence for the one matched fuel type — so StationRow/the map
                // markers' `station.prices.filter(...)` found nothing and rendered no price at
                // all. Synthesize the single-entry list they expect (mirrors fuel-web's page.tsx
                // fix for the same endpoint shape). Also sorted client-side by price ascending —
                // not just relying on the backend's order — so "Cheapest" always reads
                // cheapest-first.
                _state.value = s.copy(
                    isLoading = false,
                    stations = response.results
                        .sortedBy { it.pricePence }
                        .map { entry ->
                            entry.station.copy(
                                distanceMiles = entry.distanceMiles,
                                prices = listOf(
                                    PriceDto(
                                        fuelType = s.selectedFuelType,
                                        pricePence = entry.pricePence,
                                        reportedAt = "",
                                    )
                                ),
                            )
                        },
                    discrepancyReportUrl = response.discrepancyReportUrl,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private fun reload(forceRefresh: Boolean = false) {
        val s = _state.value
        if (s.searchQuery.length >= 2) {
            // Search and cheapest hit no local cache, so forceRefresh is a no-op for them.
            setSearchQuery(s.searchQuery)
        } else when (s.mode) {
            ListMode.NEARBY -> loadNearby(forceRefresh)
            ListMode.CHEAPEST -> loadCheapest()
        }
    }
}
