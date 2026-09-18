package uk.co.fuelprices.ui.screens.favourites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    // Favourite ids with a toggleNotify/updateFuelType PATCH currently in flight — guards
    // against a rapid double-tap (or overlapping notify+fuel-type edits on the same row) firing
    // two overlapping requests, matching NearbyViewModel.pendingFavouriteToggles's pattern.
    val pendingUpdateIds: Set<Int> = emptySet(),
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
                // A 401 that reaches here means TokenAuthenticator already tried a silent
                // refresh and it failed (the refresh token itself is invalid/expired/revoked),
                // clearing the stored token — that's now functionally a signed-out session.
                // Route there instead of surfacing the raw "HTTP 401" exception message.
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
                // Same reasoning as load()'s catch — a 401 mid-session means TokenAuthenticator's
                // silent refresh attempt already failed and cleared the token, so drop straight
                // to the signed-out screen instead of the raw error.
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

    fun toggleNotify(favourite: FavouriteDto) {
        if (favourite.id in _state.value.pendingUpdateIds) return
        _state.value = _state.value.copy(pendingUpdateIds = _state.value.pendingUpdateIds + favourite.id)
        viewModelScope.launch {
            val newValue = !favourite.notifyOnDrop
            try {
                val updated = repo.updateFavourite(favourite.id, newValue)
                analytics.trackEvent(
                    if (newValue) "favourite_notify_enabled" else "favourite_notify_disabled",
                    mapOf("station_id" to favourite.stationId),
                )
                replaceFavourite(favourite, updated)
            } catch (_: Exception) {
            } finally {
                _state.update { it.copy(pendingUpdateIds = it.pendingUpdateIds - favourite.id) }
            }
        }
    }

    fun updateFuelType(favourite: FavouriteDto, newFuelType: String) {
        if (favourite.id in _state.value.pendingUpdateIds || newFuelType == favourite.fuelType) return
        _state.value = _state.value.copy(pendingUpdateIds = _state.value.pendingUpdateIds + favourite.id)
        viewModelScope.launch {
            try {
                val updated = repo.updateFavouriteFuelType(favourite.id, newFuelType)
                analytics.trackEvent(
                    "favourite_fuel_type_changed",
                    mapOf("station_id" to favourite.stationId, "fuel_type" to newFuelType),
                )
                replaceFavourite(favourite, updated)
            } catch (_: Exception) {
            } finally {
                _state.update { it.copy(pendingUpdateIds = it.pendingUpdateIds - favourite.id) }
            }
        }
    }

    // Both PATCH responses omit `station` (a fresh favourite already has no need for it beyond
    // the id/station_id already known locally, same as POST's response) — keep the one already
    // loaded from GET.
    private fun replaceFavourite(original: FavouriteDto, updated: FavouriteDto) {
        _state.update { current ->
            current.copy(
                favourites = current.favourites.map {
                    if (it.id == original.id) updated.copy(station = it.station) else it
                },
            )
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
