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
import uk.co.fuelprices.util.AppAnalytics
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
    private val analytics: AppAnalytics,
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
                // A 401 here means the OkHttp interceptor (AppModule) has already cleared the
                // stored token — there's no refresh mechanism to retry with, so this is now
                // functionally a signed-out session. Route there instead of surfacing the raw
                // "HTTP 401" exception message.
                if (!repo.isLoggedIn()) {
                    _state.value = FavouritesUiState(isLoading = false, isLoggedIn = false)
                } else {
                    _state.value = _state.value.copy(isLoading = false, error = e.message)
                }
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
                analytics.trackEvent("create_alert", mapOf("fuel_type" to fuelType, "radius_miles" to radiusMiles))
                _state.value = _state.value.copy(
                    creatingAlert = false,
                    alerts = listOf(sub) + _state.value.alerts,
                    message = "Alert created — we'll notify you of nearby drops.",
                )
            } catch (e: Exception) {
                // Same reasoning as load()'s catch — a 401 mid-session means the token's already
                // been cleared, so drop straight to the signed-out screen instead of the raw error.
                if (!repo.isLoggedIn()) {
                    _state.value = FavouritesUiState(isLoading = false, isLoggedIn = false)
                } else {
                    _state.value = _state.value.copy(creatingAlert = false, error = e.message)
                }
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

    fun removeFavourite(id: Int, stationId: Int) {
        viewModelScope.launch {
            try {
                repo.removeFavourite(id)
                analytics.trackEvent("remove_from_favourites", mapOf("station_id" to stationId))
                _state.value = _state.value.copy(
                    favourites = _state.value.favourites.filter { it.id != id }
                )
            } catch (_: Exception) {
            }
        }
    }

    fun trackStationClick(stationId: Int) {
        analytics.trackEvent("select_station", mapOf("station_id" to stationId, "source" to "favourites"))
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
