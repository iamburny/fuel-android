package uk.co.fuelprices.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.PriceHistoryPoint
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.AppAnalytics
import uk.co.fuelprices.util.LocationHelper
import uk.co.fuelprices.util.estimateDriveCostPounds
import uk.co.fuelprices.util.haversineMiles
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean = true,
    val station: StationDto? = null,
    val priceHistory: List<PriceHistoryPoint> = emptyList(),
    val isFavourite: Boolean = false,
    val favouriteId: Int? = null,
    val nationalAverages: List<NationalAverageDto> = emptyList(),
    val distanceMiles: Double? = null,
    val driveCostPounds: Double? = null,
    val error: String? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: FuelRepository,
    private val locationHelper: LocationHelper,
    private val preferencesStore: UserPreferencesStore,
    private val analytics: AppAnalytics,
) : ViewModel() {

    private val stationId: Int = savedState.get<Int>("stationId") ?: 0

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val station = repo.getStation(stationId)
                val fuelType = station.prices.firstOrNull()?.fuelType ?: "E10"
                val history = try {
                    repo.getPriceHistory(stationId, fuelType).history
                } catch (_: Exception) { emptyList() }

                // Check if this station is already a favourite
                val existingFav = try {
                    repo.getFavourites().find { it.stationId == stationId }
                } catch (_: Exception) { null }

                // Unconditional — needed for the vs-national-average price delta regardless of
                // whether MPG/tank capacity are set (unlike the car app's conditional fetch,
                // which is only used there for savings-based sorting).
                val averages = try { repo.getNationalAverages().averages } catch (_: Exception) { emptyList() }

                val preferences = preferencesStore.get()
                var distanceMiles: Double? = null
                var driveCost: Double? = null
                if (preferences.canEstimateDriveCost) {
                    val location = locationHelper.getCurrentLocation()
                    val price = station.prices.firstOrNull { it.fuelType == preferences.fuelType }?.pricePence
                    if (location != null && price != null) {
                        distanceMiles = haversineMiles(
                            location.latitude, location.longitude, station.latitude, station.longitude,
                        )
                        driveCost = estimateDriveCostPounds(distanceMiles, preferences.mpg!!, price)
                    }
                }

                _state.value = DetailUiState(
                    isLoading = false,
                    station = station,
                    priceHistory = history,
                    isFavourite = existingFav != null,
                    favouriteId = existingFav?.id,
                    nationalAverages = averages,
                    distanceMiles = distanceMiles,
                    driveCostPounds = driveCost,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun toggleFavourite() {
        viewModelScope.launch {
            try {
                val current = _state.value
                if (current.isFavourite && current.favouriteId != null) {
                    repo.removeFavourite(current.favouriteId)
                    analytics.trackEvent("remove_from_favourites", mapOf("station_id" to stationId))
                    _state.value = current.copy(isFavourite = false, favouriteId = null)
                } else {
                    val fav = repo.addFavourite(stationId)
                    analytics.trackEvent("add_to_favourites", mapOf("station_id" to stationId))
                    _state.value = current.copy(isFavourite = true, favouriteId = fav.id)
                }
            } catch (_: Exception) { }
        }
    }
}
