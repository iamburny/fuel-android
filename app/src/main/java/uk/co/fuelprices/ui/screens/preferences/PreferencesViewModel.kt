package uk.co.fuelprices.ui.screens.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.repository.FuelRepository
import uk.co.fuelprices.data.repository.UserPreferencesStore
import javax.inject.Inject

data class PreferencesUiState(
    val fuelType: String = "E10",
    val mpgText: String = "",
    val tankCapacityText: String = "",
    val useLongFuelNames: Boolean = false,
    val themeMode: String = "SYSTEM",
    val justSaved: Boolean = false,
    val isLoggedIn: Boolean = false,
    val email: String? = null,
)

@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val store: UserPreferencesStore,
    private val repo: FuelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PreferencesUiState())
    val state: StateFlow<PreferencesUiState> = _state.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val prefs = store.get()
            _state.value = PreferencesUiState(
                fuelType = prefs.fuelType,
                mpgText = prefs.mpg?.let { formatNumber(it) } ?: "",
                tankCapacityText = prefs.tankCapacityLitres?.let { formatNumber(it) } ?: "",
                useLongFuelNames = prefs.useLongFuelNames,
                themeMode = prefs.themeMode,
            )
            refreshAccount()
        }
    }

    /** Re-read the signed-in state. The screen calls this on entry so a login/logout that happened
     *  on the Auth screen (TokenStore exposes no Flow) is reflected when returning here. */
    fun refreshAccount() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoggedIn = repo.isLoggedIn(),
                email = repo.currentEmail(),
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repo.logout()
            _state.value = _state.value.copy(isLoggedIn = false, email = null)
        }
    }

    fun setFuelType(type: String) {
        _state.value = _state.value.copy(fuelType = type)
        saveNow()
    }

    fun setMpgText(text: String) {
        _state.value = _state.value.copy(mpgText = text)
        saveDebounced()
    }

    fun setTankCapacityText(text: String) {
        _state.value = _state.value.copy(tankCapacityText = text)
        saveDebounced()
    }

    fun setUseLongFuelNames(useLong: Boolean) {
        _state.value = _state.value.copy(useLongFuelNames = useLong)
        saveNow()
    }

    fun setThemeMode(mode: String) {
        _state.value = _state.value.copy(themeMode = mode)
        saveNow()
    }

    // Discrete choices (chips, switch) persist immediately.
    private fun saveNow() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { persist() }
    }

    // Text fields debounce so we don't write to DataStore on every keystroke.
    private fun saveDebounced() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            persist()
        }
    }

    private suspend fun persist() {
        val s = _state.value
        store.save(
            fuelType = s.fuelType,
            mpg = s.mpgText.toDoubleOrNull(),
            tankCapacityLitres = s.tankCapacityText.toDoubleOrNull(),
            useLongFuelNames = s.useLongFuelNames,
            themeMode = s.themeMode,
        )
        _state.value = _state.value.copy(justSaved = true)
        delay(1_500)
        _state.value = _state.value.copy(justSaved = false)
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toInt().toDouble()) value.toInt().toString() else value.toString()
}
