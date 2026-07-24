package uk.co.fuelprices.ui.screens.heatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.HeatmapCell
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferencesStore
import javax.inject.Inject
import kotlin.math.abs

data class HeatmapUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val fuelType: String = "E10",
    val nationalAvg: Double = 0.0,
    val cells: List<HeatmapCell> = emptyList(),
    // Pence deviation that saturates the colour scale (90th-percentile of |delta|, floored at 3p).
    val maxAbs: Double = 3.0,
)

@HiltViewModel
class HeatmapViewModel @Inject constructor(
    private val repo: FuelRepository,
    private val prefsStore: UserPreferencesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HeatmapUiState())
    val state: StateFlow<HeatmapUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val preferred = try { prefsStore.get().fuelType } catch (_: Exception) { "E10" }
            _state.value = _state.value.copy(fuelType = preferred)
            load()
        }
    }

    fun setFuelType(fuelType: String) {
        if (fuelType == _state.value.fuelType) return
        _state.value = _state.value.copy(fuelType = fuelType)
        load()
    }

    private fun load() {
        val fuelType = _state.value.fuelType
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val res = repo.getHeatmap(fuelType)
                _state.value = _state.value.copy(
                    loading = false,
                    nationalAvg = res.nationalAvgPricePence,
                    cells = res.cells,
                    maxAbs = computeMaxAbs(res.cells),
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Couldn't load the heat map. Check your connection and try again.",
                )
            }
        }
    }

    /** Saturate the scale at the 90th-percentile deviation so a couple of outliers don't wash out
     *  the rest; floor at 3p so a flat market still shows contrast. */
    private fun computeMaxAbs(cells: List<HeatmapCell>): Double {
        if (cells.isEmpty()) return 3.0
        val sorted = cells.map { abs(it.deltaPence) }.sorted()
        val p90 = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        return maxOf(3.0, Math.round(p90 * 10.0) / 10.0)
    }
}
