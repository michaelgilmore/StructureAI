package cc.gilmore.todoai.ui.login

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.gilmore.todoai.auth.CredentialStore
import cc.gilmore.todoai.auth.SignInException
import cc.gilmore.todoai.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val credentialStore: CredentialStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        // Pre-fill from a previous "remember me", if any — this only ever
        // reads what's already encrypted on disk (see CredentialStore's
        // doc comment); it doesn't sign in automatically, since a wrong or
        // revoked saved password should surface as a normal failed
        // sign-in, not a silent, confusing failure before the user even
        // sees the form.
        val savedUsername = credentialStore.loadUsername()
        val savedPassword = credentialStore.loadPassword()
        if (savedUsername != null && savedPassword != null) {
            Log.d(TAG, "init: pre-filling remembered credentials for '$savedUsername'")
            _uiState.value = _uiState.value.copy(username = savedUsername, password = savedPassword, rememberMe = true)
        }
    }

    fun onUsernameChange(value: String) { _uiState.value = _uiState.value.copy(username = value, error = null) }
    fun onPasswordChange(value: String) { _uiState.value = _uiState.value.copy(password = value, error = null) }
    fun onRememberMeChange(value: Boolean) { _uiState.value = _uiState.value.copy(rememberMe = value) }

    fun signIn() {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(error = "Enter your username and password")
            return
        }
        _uiState.value = state.copy(isLoading = true, error = null)
        val username = state.username.trim()
        viewModelScope.launch {
            try {
                authRepository.signIn(username, state.password)
                Log.d(TAG, "signIn: success, rememberMe=${state.rememberMe}")
                if (state.rememberMe) {
                    credentialStore.save(username, state.password)
                } else {
                    // Explicit un-check on a device that had a previous
                    // "remember me" should actually forget it, not just
                    // stop re-saving it going forward.
                    credentialStore.clear()
                }
            } catch (e: SignInException.InvalidCredentials) {
                Log.w(TAG, "signIn: invalid credentials for '$username'")
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Wrong username or password")
            } catch (e: Exception) {
                Log.e(TAG, "signIn: failed for '$username'", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Sign-in failed")
            }
        }
    }

    private companion object {
        const val TAG = "TodoAi.LoginViewModel"
    }
}
