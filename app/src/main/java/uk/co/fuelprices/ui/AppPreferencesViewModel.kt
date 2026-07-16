package uk.co.fuelprices.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import uk.co.fuelprices.data.repository.UserPreferencesStore
import javax.inject.Inject

@HiltViewModel
class AppPreferencesViewModel @Inject constructor(
    store: UserPreferencesStore,
) : ViewModel() {
    val useLongFuelNames = store.preferences
        .map { it.useLongFuelNames }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
