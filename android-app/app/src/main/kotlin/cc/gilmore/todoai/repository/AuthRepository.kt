package cc.gilmore.todoai.repository

import cc.gilmore.todoai.auth.CognitoAuthManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin façade over [CognitoAuthManager] that also exposes sign-in state as
 * a [StateFlow] for the UI (NavGraph decides login vs. todo-list screen
 * off of this) and is the one thing both the UI layer and
 * data/remote/AuthInterceptor.kt depend on for a token.
 */
class AuthRepository(private val authManager: CognitoAuthManager) {

    private val _isSignedIn = MutableStateFlow<Boolean?>(null) // null = "haven't checked yet"
    val isSignedIn: StateFlow<Boolean?> = _isSignedIn.asStateFlow()

    /** For display only (e.g. SettingsScreen) — the signed-in Cognito username, or null if signed out. Not a StateFlow: it only needs to be read once a screen that already requires [isSignedIn] == true is showing. */
    val currentUsername: String? get() = authManager.currentUsername

    /** Call once at app startup to resolve the initial auth state without forcing a full re-login. */
    suspend fun checkExistingSession() {
        _isSignedIn.value = authManager.currentIdTokenOrNull() != null
    }

    suspend fun signIn(username: String, password: String) {
        authManager.signIn(username, password)
        _isSignedIn.value = true
    }

    fun signOut() {
        authManager.signOut()
        _isSignedIn.value = false
    }

    /** Used by AuthInterceptor. A null result means "not authenticated" — the request goes out without a Bearer header and the API will 401 it. */
    suspend fun currentIdTokenOrNull(): String? {
        val token = authManager.currentIdTokenOrNull()
        if (token == null && _isSignedIn.value == true) {
            // Refresh token died silently (expiry, revocation) since the
            // last successful call — reflect that in the UI state so
            // NavGraph routes back to login instead of leaving the user
            // stuck on a screen that can no longer load anything.
            _isSignedIn.value = false
        }
        return token
    }
}
