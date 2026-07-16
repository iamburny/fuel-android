package uk.co.fuelprices.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.FuelRepository
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
) : ViewModel() {

    private val _state = MutableStateFlow(NearbyUiState())
    val state: StateFlow<NearbyUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadNearby()
        // The loadNearby() above fires immediately on ViewModel creation, which is often before
        // the user has answered the runtime location permission dialog (that request is launched
        // async from MainActivity, so it races). MainActivity notifies this the moment a result
        // is known, which is deterministic — unlike lifecycle resume timing around the dialog,
        // which doesn't reliably re-fire on every device/API level.
        viewModelScope.launch {
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

                val response = repo.getNearbyStations(
                    lat, lng,
                    _state.value.radiusMiles,
                    _state.value.selectedFuelType,
                )

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
        reload()
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
