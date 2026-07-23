package uk.co.fuelprices.ui.screens.favourites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.AlertSubscriptionDto
import uk.co.fuelprices.data.api.FavouriteDto
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.util.LocationHelper
import javax.inject.Inject

data class FavouritesUiState(
    val isLoading: Boolean = true,
    val favourites: List<FavouriteDto> = emptyList(),
    val alerts: List<AlertSubscriptionDto> = emptyList(),
    val isLoggedIn: Boolean = true,
    val creatingAlert: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class FavouritesViewModel @Inject constructor(
    private val repo: FuelRepository,
    private val locationHelper: LocationHelper,
) : ViewModel() {

    private val _state = MutableStateFlow(FavouritesUiState())
    val state: StateFlow<FavouritesUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                if (!repo.isLoggedIn()) {
                    _state.value = FavouritesUiState(isLoading = false, isLoggedIn = false)
                    return@launch
                }
                val favs = repo.getFavourites()
                val alerts = repo.getAlerts()
                _state.value = FavouritesUiState(
                    isLoading = false,
                    isLoggedIn = true,
                    favourites = favs,
                    alerts = alerts,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /** Create a "drops near me" subscription anchored at the device's current location. */
    fun createAlertNearMe(radiusMiles: Double, fuelType: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(creatingAlert = true, error = null, message = null)
            val location = locationHelper.getCurrentLocation()
            if (location == null) {
                _state.value = _state.value.copy(
                    creatingAlert = false,
                    error = "Couldn't get your location. Enable location and try again.",
                )
                return@launch
            }
            try {
                val sub = repo.addAlert(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    radiusMiles = radiusMiles,
                    fuelType = fuelType,
                )
                _state.value = _state.value.copy(
                    creatingAlert = false,
                    alerts = listOf(sub) + _state.value.alerts,
                    message = "Alert created — we'll notify you of nearby drops.",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(creatingAlert = false, error = e.message)
            }
        }
    }

    fun removeAlert(id: Int) {
        viewModelScope.launch {
            try {
                repo.removeAlert(id)
                _state.value = _state.value.copy(
                    alerts = _state.value.alerts.filter { it.id != id }
                )
            } catch (_: Exception) {
            }
        }
    }

    fun removeFavourite(id: Int) {
        viewModelScope.launch {
            try {
                repo.removeFavourite(id)
                _state.value = _state.value.copy(
                    favourites = _state.value.favourites.filter { it.id != id }
                )
            } catch (_: Exception) {
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
