package uk.co.fuelprices.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.repository.UserPreferencesStore
import uk.co.fuelprices.util.FeatureFlags
import javax.inject.Inject

/**
 * App-root preferences observed above the navigation graph: the short/long fuel-name toggle, the
 * appearance (theme) selector, and the "buy me a coffee" support prompt cadence. Held at the
 * Activity scope so [MainActivity] (which triggers [onAppOpened]) and `FuelApp` (which renders the
 * prompt) share one instance.
 */
@HiltViewModel
class AppPreferencesViewModel @Inject constructor(
    private val store: UserPreferencesStore,
    private val featureFlags: FeatureFlags,
) : ViewModel() {

    val useLongFuelNames = store.preferences
        .map { it.useLongFuelNames }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val themeMode = store.preferences
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "SYSTEM")

    private val _showCoffeePrompt = MutableStateFlow(false)
    val showCoffeePrompt: StateFlow<Boolean> = _showCoffeePrompt.asStateFlow()

    // The open count at which the prompt is currently showing — used to compute the pause target.
    private var currentOpenCount = 0

    /**
     * Record a cold app launch and decide whether to show the support prompt. Called once per
     * launch from MainActivity (guarded by savedInstanceState == null). Shows on the first launch
     * and then every [PROMPT_EVERY] opens (opens 1, 6, 11, …), unless suppressed until a later open
     * by a previous CTA tap.
     */
    fun onAppOpened() {
        viewModelScope.launch {
            val count = store.incrementAppOpenCount()
            currentOpenCount = count
            val pausedUntil = store.get().coffeePromptPausedUntilOpen
            val cadenceDue = (count - 1) % PROMPT_EVERY == 0 && count >= pausedUntil
            // shared.buy-me-a-coffee (default true — preserves existing behaviour if Unleash is
            // unreachable/unconfigured) gates the whole prompt, not just its cadence.
            if (cadenceDue && featureFlags.isEnabled("shared.buy-me-a-coffee", default = true)) {
                _showCoffeePrompt.value = true
            }
        }
    }

    /** CTA tapped: hide and pause the prompt for [PAUSE_OPENS] launches (caller opens the URL). */
    fun onCoffeeClicked() {
        _showCoffeePrompt.value = false
        viewModelScope.launch { store.pauseCoffeePrompt(currentOpenCount + PAUSE_OPENS) }
    }

    /** Dismissed without tapping the CTA: just hide; it returns at the next [PROMPT_EVERY] opens. */
    fun onDismissCoffee() {
        _showCoffeePrompt.value = false
    }

    private companion object {
        const val PROMPT_EVERY = 5
        const val PAUSE_OPENS = 20
    }
}
