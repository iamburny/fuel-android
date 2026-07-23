package uk.co.fuelprices.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import uk.co.fuelprices.data.repository.AuthException
import uk.co.fuelprices.data.repository.FuelRepository
import javax.inject.Inject
import kotlin.coroutines.resume

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val isRegister: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repo: FuelRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun setEmail(value: String) { _state.value = _state.value.copy(email = value, error = null) }
    fun setPassword(value: String) { _state.value = _state.value.copy(password = value, error = null) }
    fun toggleMode() {
        _state.value = _state.value.copy(isRegister = !_state.value.isRegister, error = null)
    }

    fun submit(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.email.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(error = "Email and password are required")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                if (s.isRegister) {
                    repo.register(s.email.trim(), s.password)
                }
                repo.login(s.email.trim(), s.password)
                registerFcmToken()
                _state.value = _state.value.copy(loading = false)
                onSuccess()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = friendlyError(e))
            }
        }
    }

    // Best-effort: push the device token so the just-logged-in account can receive alerts. A
    // failure here must not block sign-in, so it's swallowed (the token also re-registers on
    // Firebase rotation via FcmService).
    private suspend fun registerFcmToken() {
        try {
            val token = suspendCancellableCoroutine<String?> { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
            if (token != null) repo.registerFcmToken(token)
        } catch (_: Exception) {
        }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is AuthException -> e.message ?: "Sign-in failed. Please try again."
        else -> "Couldn't connect. Check your connection and try again."
    }
}
