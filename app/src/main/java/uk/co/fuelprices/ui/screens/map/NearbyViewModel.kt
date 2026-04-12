package uk.co.fuelprices.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.util.LocationHelper
import javax.inject.Inject

data class NearbyUiState(
    val isLoading: Boolean = true,
    val stations: List<StationDto> = emptyList(),
    val selectedFuelType: String = "E10",
    val radiusMiles: Double = 10.0,
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

    init {
        loadNearby()
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
        loadNearby()
    }

    fun setRadius(miles: Double) {
        _state.value = _state.value.copy(radiusMiles = miles)
        loadNearby()
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
}
