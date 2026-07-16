package uk.co.fuelprices.ui.screens.prices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.NationalAverageDto
import uk.co.fuelprices.data.api.TrendPoint
import uk.co.fuelprices.data.repository.FuelRepository
import javax.inject.Inject

data class PricesUiState(
    val isLoading: Boolean = true,
    val averages: List<NationalAverageDto> = emptyList(),
    val trend: List<TrendPoint> = emptyList(),
    val selectedFuelType: String = "E10",
    val selectedDays: Int = 30,
    val discrepancyReportUrl: String = "",
    val dataNotice: String = "",
    val error: String? = null,
)

@HiltViewModel
class PricesViewModel @Inject constructor(
    private val repo: FuelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PricesUiState())
    val state: StateFlow<PricesUiState> = _state.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val avg = repo.getNationalAverages()
                val trend = repo.getNationalTrends(_state.value.selectedFuelType, _state.value.selectedDays)
                _state.value = _state.value.copy(
                    isLoading = false,
                    averages = avg.averages,
                    trend = trend.trend,
                    discrepancyReportUrl = avg.discrepancyReportUrl,
                    dataNotice = avg.dataNotice,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun setFuelType(type: String) {
        _state.value = _state.value.copy(selectedFuelType = type)
        loadTrend()
    }

    fun setDays(days: Int) {
        _state.value = _state.value.copy(selectedDays = days)
        loadTrend()
    }

    private fun loadTrend() {
        viewModelScope.launch {
            try {
                val trend = repo.getNationalTrends(_state.value.selectedFuelType, _state.value.selectedDays)
                _state.value = _state.value.copy(trend = trend.trend)
            } catch (_: Exception) { }
        }
    }
}
