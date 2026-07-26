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
import uk.co.fuelprices.util.FeatureFlags
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
    // Default true: these flags gate pre-existing UI, so if Unleash is unreachable/unconfigured
    // the app falls back to its prior (visible) behaviour rather than silently hiding it.
    val showBuyMeCoffee: Boolean = true,
    val showAlsoAvailableOnWeb: Boolean = true,
)

@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val store: UserPreferencesStore,
    private val repo: FuelRepository,
    private val featureFlags: FeatureFlags,
) : ViewModel() {

    private val _state = MutableStateFlow(PreferencesUiState())
    val state: StateFlow<PreferencesUiState> = _state.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            val prefs = store.get()
            _state.value = _state.value.copy(
                fuelType = prefs.fuelType,
                mpgText = prefs.mpg?.let { formatNumber(it) } ?: "",
                tankCapacityText = prefs.tankCapacityLitres?.let { formatNumber(it) } ?: "",
                useLongFuelNames = prefs.useLongFuelNames,
                themeMode = prefs.themeMode,
            )
            refreshAccount()
        }
        // featureFlags.version is a StateFlow, so this also runs once immediately with the
        // current flag state — no separate initial check needed. Re-runs on every poll/refresh
        // so a flag toggled via fuel-admin's /flags page takes effect without an app restart.
        viewModelScope.launch {
            featureFlags.version.collect { refreshFlags() }
        }
    }

    private fun refreshFlags() {
        _state.value = _state.value.copy(
            showBuyMeCoffee = featureFlags.isEnabled("shared.buy-me-a-coffee", default = true),
            showAlsoAvailableOnWeb = featureFlags.isEnabled("fuel-android.also-available-on-web", default = true),
        )
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
