package uk.co.fuelprices.ui.screens.favourites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.fuelprices.data.api.FavouriteDto
import uk.co.fuelprices.data.repository.FuelRepository
import javax.inject.Inject

data class FavouritesUiState(
    val isLoading: Boolean = true,
    val favourites: List<FavouriteDto> = emptyList(),
    val isLoggedIn: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class FavouritesViewModel @Inject constructor(
    private val repo: FuelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FavouritesUiState())
    val state: StateFlow<FavouritesUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val loggedIn = repo.isLoggedIn()
                if (!loggedIn) {
                    _state.value = FavouritesUiState(isLoading = false, isLoggedIn = false)
                    return@launch
                }
                val favs = repo.getFavourites()
                _state.value = FavouritesUiState(isLoading = false, favourites = favs)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun removeFavourite(id: Int) {
        viewModelScope.launch {
            try {
                repo.removeFavourite(id)
                _state.value = _state.value.copy(
                    favourites = _state.value.favourites.filter { it.id != id }
                )
            } catch (_: Exception) { }
        }
    }
}
