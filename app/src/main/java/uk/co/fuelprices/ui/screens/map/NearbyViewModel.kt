package uk.co.fuelprices.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLngBounds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.AppAnalytics
import uk.co.fuelprices.util.DefaultLocation
import uk.co.fuelprices.util.LocationHelper
import uk.co.fuelprices.util.haversineMiles
import javax.inject.Inject

/** North-up (the original, only-ever behavior) vs. rotating the map so the direction the user is
 *  currently travelling always faces up on screen, like a car navigation app's heading-up mode. */
enum class MapOrientationMode { NORTH_UP, TRAVEL_DIRECTION_UP }

data class NearbyUiState(
    val isLoading: Boolean = true,
    val stations: List<StationDto> = emptyList(),
    val selectedFuelType: String = "E10",
    val radiusMiles: Double = 10.0,
    val searchQuery: String = "",
    val userLat: Double? = null,
    val userLng: Double? = null,
    // Gates the map's "my location" blue dot — enabling it without the permission actually
    // granted throws a SecurityException and crashes the app, so this must reflect the real
    // permission state rather than being assumed true.
    val hasLocationPermission: Boolean = false,
    val error: String? = null,
    // Stations for whatever map area the user last dragged to — null until the first drag, at
    // which point map pins switch to this instead of the GPS-anchored `stations`. The search
    // panel's default (non-search) list also switches to this via cheapestSortedStations(), so it
    // always tracks whatever's currently pinned on the map.
    val viewportStations: List<StationDto>? = null,
    // Bumped only when the map should jump to userLat/userLng — never on every reload, so
    // changing the radius/fuel filter/mode doesn't fight a drag by snapping the camera back.
    val cameraRecenterToken: Int = 0,
    // True once the user has dragged the map away from GPS-center — shows a recenter button.
    val isOffGpsCenter: Boolean = false,
    // True after repeated back-to-back connection failures — drives a graceful "can't reach the
    // server" banner. Clears automatically on the next successful fetch.
    val apiUnreachable: Boolean = false,
    // True while a drag-triggered viewport fetch is in flight. Drives a thin top-of-map progress
    // bar — the old pins stay on screen throughout (viewportStations is only replaced once the
    // new response lands), so this is purely a "something's happening" signal, not a data swap.
    val isLoadingViewport: Boolean = false,
    // Where the user last panned/zoomed the map to — kept here (rather than only in the map
    // composable's own `rememberCameraPositionState`) so it survives navigating to Detail and
    // back: Compose disposes that remembered state when NearbyScreen leaves composition, but this
    // ViewModel is scoped to the Nearby nav-graph entry and outlives that. Null means "not dragged
    // (yet)" — NearbyScreen falls back to userLat/userLng, i.e. the GPS position.
    val cameraLat: Double? = null,
    val cameraLng: Double? = null,
    val cameraZoom: Float = 12f,
    // True once, on first appearance, until the one-time "Cheapest prices" toggle tooltip has been
    // shown and dismissed — see NearbyViewModel.markCheapestTooltipSeen(). Lives here (rather than
    // local Composable state) so it survives rotation, same as cameraLat/cameraLng.
    val showCheapestTooltip: Boolean = false,
    // True once the fuel-type pill's one-time tooltip should be shown — eligible only once the
    // cheapest-toggle tooltip above has actually been marked seen (see
    // NearbyViewModel.markCheapestTooltipSeen() and its init-time equivalent for returning users).
    // Unlike showCheapestTooltip this isn't gated on showPanel — the pill sits on the map itself
    // and is visible regardless of the search panel's open/closed state.
    val showFuelTypePillTooltip: Boolean = false,
    // stationId -> favouriteId (removeFavourite takes the favourite row's own id, not the station
    // id, so this map is what makes an O(1) toggle possible). Null means "not loaded yet" — kept
    // distinct from an empty map so a row never flashes an incorrect unfavourited state for a
    // station that's actually already favourited before the real list has arrived.
    val favouriteStationIds: Map<Int, Int>? = null,
    // Stations with a favourite toggle currently in flight — guards against a rapid double-tap
    // firing two overlapping add/remove requests for the same station.
    val pendingFavouriteToggles: Set<Int> = emptySet(),
    // One-shot event for the screen to react to (show a snackbar) and then clear via
    // consumeFavouriteEvent() — not persisted state, so it doesn't re-fire on recomposition.
    val favouriteEvent: NearbyFavouriteEvent? = null,
    // GPS course-over-ground from the most recent fix that actually reported one — only ever
    // overwritten by a genuinely valid bearing (loc.hasBearing()), never reset to 0/garbage on an
    // invalid reading. Keeps updating even while off-center, so it can't be stale once the user
    // recenters or toggles orientation mode.
    val lastKnownBearing: Float = 0f,
    val mapOrientationMode: MapOrientationMode = MapOrientationMode.NORTH_UP,
    // Camera-committed bearing — 0 in north-up, else lastKnownBearing in travel-direction-up.
    // Stored (not computed) to match cameraLat/cameraLng/cameraZoom's existing pattern.
    val mapBearing: Float = 0f,
)

/** One-shot outcomes of [NearbyViewModel.toggleFavourite] that the screen surfaces as a snackbar.
 *  Deliberately not a silent no-op on [SignInRequired] — unlike the Detail screen's existing
 *  favourite heart, this list's hearts prompt sign-in instead of failing invisibly. */
sealed interface NearbyFavouriteEvent {
    data object SignInRequired : NearbyFavouriteEvent
    data class ActionFailed(val message: String) : NearbyFavouriteEvent
}

/** Client-side derived view of whatever's currently pinned on the map (viewportStations after a
 *  drag, else the GPS-anchored `stations`), sorted ascending by price for `selectedFuelType` and
 *  filtered to stations that report one. No network call — this is a pure function over state
 *  already held, so it can't drift from what's actually pinned on the map, and re-evaluates live
 *  (fuel-type change / a drag while the panel is open) since it's called fresh on every
 *  recomposition rather than cached. Backs the search panel's default (non-search) list. */
fun NearbyUiState.cheapestSortedStations(): List<StationDto> =
    (viewportStations ?: stations)
        .mapNotNull { station ->
            station.prices.filter { it.fuelType == selectedFuelType }.minByOrNull { it.pricePence }
                ?.let { station to it.pricePence }
        }
        .sortedBy { it.second }
        .map { it.first }

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val repo: FuelRepository,
    private val locationHelper: LocationHelper,
    private val preferencesStore: UserPreferencesStore,
    private val analytics: AppAnalytics,
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
            // to E10, and show the one-time toggle tooltip only if the user hasn't seen it yet.
            val prefs = preferencesStore.get()
            _state.value = _state.value.copy(
                selectedFuelType = prefs.fuelType,
                showCheapestTooltip = !prefs.hasSeenNearbyCheapestTooltip,
                // Chained: for a returning user who already dismissed the cheapest-toggle tooltip
                // in a prior session but hasn't yet seen this one, it becomes eligible immediately.
                // A user still on their first-ever cheapest-toggle tooltip gets this later, when
                // markCheapestTooltipSeen() flips it in the same state update.
                showFuelTypePillTooltip = prefs.hasSeenNearbyCheapestTooltip && !prefs.hasSeenFuelTypePillTooltip,
            )

            // Give the permission dialog a brief window to be answered before firing the first
            // request — otherwise we load the fallback location, render it, then immediately
            // correct to the real location once permission lands, which reads as a jarring
            // flash. If permission's already granted (the common case for returning users) this
            // returns instantly. Capped at 3s so a slow response doesn't stall the screen.
            if (!locationHelper.hasPermission()) {
                withTimeoutOrNull(3_000) { locationHelper.permissionGranted.first() }
            }
            _state.value = _state.value.copy(hasLocationPermission = locationHelper.hasPermission())
            loadNearby()
            startLocationUpdates()

            // Keep listening in case permission lands after our short wait above (e.g. the
            // dialog took longer than 3s to answer, or it's granted later via Settings).
            locationHelper.permissionGranted.collect {
                _state.value = _state.value.copy(hasLocationPermission = true)
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
                // Only overwrite on a genuinely valid bearing reading — an invalid one (e.g.
                // stopped at a light) keeps whatever was last known, so travel-direction-up mode
                // holds its last heading instead of flickering back to north. Captured
                // unconditionally (not gated on `moved`) since a bearing update is meaningful
                // even on a sub-30m tick, e.g. turning in place at a junction.
                val bearing = if (loc.hasBearing()) loc.bearing else s.lastKnownBearing
                _state.value = s.copy(
                    userLat = if (moved) loc.latitude else s.userLat,
                    userLng = if (moved) loc.longitude else s.userLng,
                    lastKnownBearing = bearing,
                    mapBearing = when (s.mapOrientationMode) {
                        MapOrientationMode.NORTH_UP -> 0f
                        MapOrientationMode.TRAVEL_DIRECTION_UP -> bearing
                    },
                    cameraRecenterToken = if (moved && !s.isOffGpsCenter) {
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
                val lat = location?.latitude ?: DefaultLocation.LAT
                val lng = location?.longitude ?: DefaultLocation.LNG

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

    /** Called when the map's drag gesture ends, with its new camera position and the newly
     *  visible viewport — the former is stashed so a later return from Detail restores the map
     *  here instead of snapping back to the GPS position. */
    fun loadStationsInBounds(position: CameraPosition, bounds: LatLngBounds) {
        boundsJob?.cancel()
        boundsJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isOffGpsCenter = true,
                isLoadingViewport = true,
                cameraLat = position.target.latitude,
                cameraLng = position.target.longitude,
                cameraZoom = position.zoom,
            )
            try {
                val response = repo.getStationsInBounds(
                    minLat = bounds.southwest.latitude, maxLat = bounds.northeast.latitude,
                    minLng = bounds.southwest.longitude, maxLng = bounds.northeast.longitude,
                )
                _state.value = _state.value.copy(viewportStations = response.stations)
            } catch (e: Exception) {
                // Keep showing whatever was already on the map rather than clearing pins on a
                // transient network failure mid-drag.
            } finally {
                _state.value = _state.value.copy(isLoadingViewport = false)
            }
        }
    }

    /** Jumps the map back to the user's GPS location and reverts pins to the GPS-anchored set.
     *  Also reapplies whatever the current orientation mode dictates — recentering position and
     *  "recommitting" to the current rotation happen together through the same mechanism. */
    fun recenterOnGps() {
        boundsJob?.cancel()
        val s = _state.value
        _state.value = s.copy(
            viewportStations = null,
            isOffGpsCenter = false,
            isLoadingViewport = false,
            cameraRecenterToken = s.cameraRecenterToken + 1,
            // Clears the stashed drag position so the map falls back to userLat/userLng again.
            cameraLat = null,
            cameraLng = null,
            cameraZoom = 12f,
            mapBearing = when (s.mapOrientationMode) {
                MapOrientationMode.NORTH_UP -> 0f
                MapOrientationMode.TRAVEL_DIRECTION_UP -> s.lastKnownBearing
            },
        )
    }

    /** Flips the map's rotation mode between north-up and travel-direction-up. Always updates the
     *  stored mode/bearing, but only forces an immediate camera update (bumping
     *  [NearbyUiState.cameraRecenterToken]) when the map is currently GPS-centered
     *  ([NearbyUiState.isOffGpsCenter] is false) — if the user has dragged away, the new
     *  orientation is picked up silently and takes effect next time they recenter. */
    fun toggleMapOrientation() {
        val s = _state.value
        val newMode = when (s.mapOrientationMode) {
            MapOrientationMode.NORTH_UP -> MapOrientationMode.TRAVEL_DIRECTION_UP
            MapOrientationMode.TRAVEL_DIRECTION_UP -> MapOrientationMode.NORTH_UP
        }
        val newBearing = when (newMode) {
            MapOrientationMode.NORTH_UP -> 0f
            MapOrientationMode.TRAVEL_DIRECTION_UP -> s.lastKnownBearing
        }
        _state.value = s.copy(
            mapOrientationMode = newMode,
            mapBearing = newBearing,
            cameraRecenterToken = if (!s.isOffGpsCenter) s.cameraRecenterToken + 1 else s.cameraRecenterToken,
        )
    }

    fun setFuelType(type: String) {
        analytics.trackEvent("select_fuel_type", mapOf("fuel_type" to type))
        // Every fuel type's prices are already cached/loaded — this is a pure property set, no
        // async work needed. The panel's default list re-sorts for free since
        // cheapestSortedStations() is derived from selectedFuelType.
        _state.value = _state.value.copy(selectedFuelType = type)
    }

    fun setRadius(miles: Double) {
        _state.value = _state.value.copy(radiusMiles = miles)
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
            // Only reached once the query has settled (a newer keystroke cancels this job before
            // getting here), so this fires once per search rather than once per character typed.
            analytics.trackEvent("search", mapOf("search_term" to query))
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = repo.searchStations(query)
                _state.value = _state.value.copy(isLoading = false, stations = response.stations)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /** Called by NearbyScreen at each station-click site (map marker / list row) — kept in the
     *  ViewModel (rather than firing analytics straight from the Composable) so it stays testable
     *  and consistent with how every other tracked interaction here goes through analytics. */
    fun trackStationClick(stationId: Int, source: String) {
        analytics.trackEvent(
            "select_station",
            mapOf("station_id" to stationId, "fuel_type" to _state.value.selectedFuelType, "source" to source),
        )
    }

    /** Called once the one-time toggle tooltip has actually been shown (not at trigger time) —
     *  hides it and persists the seen-flag so it never reappears, even after this ViewModel is
     *  recreated. */
    fun markCheapestTooltipSeen() {
        // Chains straight into the fuel-type pill tooltip in the same state update: this method
        // only ever runs once, on the actual first-ever dismissal of the cheapest tooltip (it's
        // only shown while hasSeenNearbyCheapestTooltip is false), so hasSeenFuelTypePillTooltip
        // can't already be true here — no need to re-check the store first.
        _state.value = _state.value.copy(showCheapestTooltip = false, showFuelTypePillTooltip = true)
        viewModelScope.launch {
            preferencesStore.markNearbyCheapestTooltipSeen()
        }
    }

    /** Called once the fuel-type pill's one-time tooltip has actually been shown (not at trigger
     *  time) — hides it and persists the seen-flag so it never reappears, even after this
     *  ViewModel is recreated. Mirrors [markCheapestTooltipSeen]. */
    fun markFuelTypePillTooltipSeen() {
        _state.value = _state.value.copy(showFuelTypePillTooltip = false)
        viewModelScope.launch {
            preferencesStore.markFuelTypePillTooltipSeen()
        }
    }

    /** Loads (or reloads) the favourite lookup map — called from NearbyScreen on every appearance
     *  (initial load, and again after popping back from Detail where a station could have been
     *  favourited/unfavourited there) since this ViewModel has no other signal that Detail's own
     *  favourite state may have changed underneath it. */
    fun refreshFavourites() {
        viewModelScope.launch {
            try {
                if (repo.isLoggedIn()) {
                    val favourites = repo.getFavourites()
                    // Applied via update {} rather than a plain _state.value = _state.value.copy(...)
                    // computed from a pre-suspend snapshot: repo.getFavourites() above is a suspension
                    // point, so by the time it returns, _state.value may have moved on (e.g. a
                    // concurrent toggleFavourite() write) — update {} re-reads the live state at write
                    // time instead of clobbering it with whatever else changed while this suspended.
                    _state.update { it.copy(favouriteStationIds = favourites.associate { fav -> fav.stationId to fav.id }) }
                } else {
                    _state.update { it.copy(favouriteStationIds = emptyMap()) }
                }
            } catch (_: Exception) {
                // Leave whatever was there before (possibly still null) — a transient failure here
                // shouldn't wipe a previously loaded map; affected hearts just stay disabled/unknown
                // until a later refresh succeeds.
            }
        }
    }

    /** Adds/removes [station] from favourites, mirroring DetailViewModel.toggleFavourite's
     *  add/remove semantics, but — per this list's own design — checking sign-in proactively via
     *  [FuelRepository.isLoggedIn] rather than silently swallowing a 401, and surfacing any other
     *  failure instead of no-op'ing. */
    fun toggleFavourite(station: StationDto) {
        // Guarded synchronously (before the coroutine is even launched) so a second rapid tap —
        // handled on the same main-thread dispatch as the first — sees this station already
        // pending and returns immediately, rather than firing a second overlapping request.
        if (station.id in _state.value.pendingFavouriteToggles) return
        _state.value = _state.value.copy(
            pendingFavouriteToggles = _state.value.pendingFavouriteToggles + station.id,
        )
        viewModelScope.launch {
            try {
                if (!repo.isLoggedIn()) {
                    _state.value = _state.value.copy(favouriteEvent = NearbyFavouriteEvent.SignInRequired)
                    return@launch
                }
                // Only used to decide which branch to take (add vs remove) before the suspending
                // network call below — the actual state write in each branch goes through
                // update {} against the live map at write time, not this pre-suspend snapshot, so a
                // second toggle (a different station, or a concurrent refreshFavourites()) landing
                // while this one is in flight can't silently revert it.
                val existingFavouriteId = _state.value.favouriteStationIds?.get(station.id)
                if (existingFavouriteId != null) {
                    repo.removeFavourite(existingFavouriteId)
                    analytics.trackEvent("remove_from_favourites", mapOf("station_id" to station.id))
                    _state.update { it.copy(favouriteStationIds = (it.favouriteStationIds ?: emptyMap()) - station.id) }
                } else {
                    val fav = repo.addFavourite(station.id)
                    analytics.trackEvent("add_to_favourites", mapOf("station_id" to station.id))
                    _state.update { it.copy(favouriteStationIds = (it.favouriteStationIds ?: emptyMap()) + (station.id to fav.id)) }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        favouriteEvent = NearbyFavouriteEvent.ActionFailed(
                            e.message ?: "Couldn't update favourite. Please try again.",
                        ),
                    )
                }
            } finally {
                _state.update { it.copy(pendingFavouriteToggles = it.pendingFavouriteToggles - station.id) }
            }
        }
    }

    /** Clears the one-shot favourite event once the screen has shown its snackbar for it. */
    fun consumeFavouriteEvent() {
        _state.value = _state.value.copy(favouriteEvent = null)
    }

    private fun reload(forceRefresh: Boolean = false) {
        val s = _state.value
        if (s.searchQuery.length >= 2) {
            // Search hits no local cache, so forceRefresh is a no-op for it.
            setSearchQuery(s.searchQuery)
        } else {
            loadNearby(forceRefresh)
        }
    }
}
