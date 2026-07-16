package uk.co.fuelprices.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.LocationHelper
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

    init {
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

            // Keep listening in case permission lands after our short wait above (e.g. the
            // dialog took longer than 3s to answer, or it's granted later via Settings).
            locationHelper.permissionGranted.collect {
                loadNearby()
            }
        }
    }

    fun loadNearby() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val location = locationHelper.getCurrentLocation()
                val lat = location?.latitude ?: 51.5074  // default: London
                val lng = location?.longitude ?: -0.1278

                // No fuelType here — the repository always caches full price data per station
                // now (see FuelRepository), so switching the fuel filter chip doesn't need a
                // new fetch, just a client-side re-filter for display.
                val response = repo.getNearbyStations(lat, lng, _state.value.radiusMiles)

                _state.value = _state.value.copy(
                    isLoading = false,
                    stations = response.stations,
                    userLat = lat,
                    userLng = lng,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
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
                _state.value = s.copy(
                    isLoading = false,
                    stations = response.results.map { it.station },
                    discrepancyReportUrl = response.discrepancyReportUrl,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private fun reload() {
        val s = _state.value
        if (s.searchQuery.length >= 2) {
            setSearchQuery(s.searchQuery)
        } else when (s.mode) {
            ListMode.NEARBY -> loadNearby()
            ListMode.CHEAPEST -> loadCheapest()
        }
    }
}
