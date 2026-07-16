package uk.co.fuelprices.ui.screens.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.repository.UserPreferencesStore
import javax.inject.Inject

data class PreferencesUiState(
    val fuelType: String = "E10",
    val mpgText: String = "",
    val tankCapacityText: String = "",
    val useLongFuelNames: Boolean = false,
    val justSaved: Boolean = false,
)

@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val store: UserPreferencesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PreferencesUiState())
    val state: StateFlow<PreferencesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = store.get()
            _state.value = PreferencesUiState(
                fuelType = prefs.fuelType,
                mpgText = prefs.mpg?.let { formatNumber(it) } ?: "",
                tankCapacityText = prefs.tankCapacityLitres?.let { formatNumber(it) } ?: "",
                useLongFuelNames = prefs.useLongFuelNames,
            )
        }
    }

    fun setFuelType(type: String) {
        _state.value = _state.value.copy(fuelType = type, justSaved = false)
    }

    fun setMpgText(text: String) {
        _state.value = _state.value.copy(mpgText = text, justSaved = false)
    }

    fun setTankCapacityText(text: String) {
        _state.value = _state.value.copy(tankCapacityText = text, justSaved = false)
    }

    fun setUseLongFuelNames(useLong: Boolean) {
        _state.value = _state.value.copy(useLongFuelNames = useLong, justSaved = false)
    }

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            store.save(
                fuelType = s.fuelType,
                mpg = s.mpgText.toDoubleOrNull(),
                tankCapacityLitres = s.tankCapacityText.toDoubleOrNull(),
                useLongFuelNames = s.useLongFuelNames,
            )
            _state.value = s.copy(justSaved = true)
        }
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toInt().toDouble()) value.toInt().toString() else value.toString()
}
