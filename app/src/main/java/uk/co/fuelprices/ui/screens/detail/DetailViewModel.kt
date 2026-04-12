package uk.co.fuelprices.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.PriceHistoryPoint
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.data.repository.FuelRepository
import javax.inject.Inject

data class DetailUiState(
    val isLoading: Boolean = true,
    val station: StationDto? = null,
    val priceHistory: List<PriceHistoryPoint> = emptyList(),
    val isFavourite: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: FuelRepository,
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

                _state.value = DetailUiState(
                    isLoading = false,
                    station = station,
                    priceHistory = history,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun toggleFavourite() {
        viewModelScope.launch {
            try {
                if (_state.value.isFavourite) {
                    // Would need the favourite ID — simplified here
                } else {
                    repo.addFavourite(stationId)
                    _state.value = _state.value.copy(isFavourite = true)
                }
            } catch (_: Exception) { }
        }
    }
}
