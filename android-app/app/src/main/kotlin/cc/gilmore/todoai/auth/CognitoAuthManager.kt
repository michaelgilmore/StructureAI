package cc.gilmore.todoai.auth

import android.content.Context
import android.util.Log
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoDevice
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUser
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserSession
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.exceptions.CognitoNotAuthorizedException
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler
import com.amazonaws.regions.Regions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps the AWS Mobile SDK's Cognito Identity Provider client (real
 * USER_SRP_AUTH — see app/build.gradle.kts for why this SDK over full
 * Amplify) and bridges its callback API to coroutines.
 *
 * Deliberately does NOT keep a separate token cache of its own: the SDK's
 * CognitoUserPool already persists tokens (and the last-signed-in
 * username) across process restarts on its own, and [CognitoUserPool]'s
 * `currentUser` plus [CognitoUser.getSessionInBackground] transparently
 * uses the cached refresh token to renew an expired id/access token
 * without a password, only falling back to [AuthenticationHandler]'s
 * `getAuthenticationDetails` callback (i.e. actually needing SRP again)
 * when the refresh token itself is gone or revoked. Building a second,
 * parallel token store on top would just be two sources of truth that can
 * drift.
 *
 * Still mostly unverified against the real SDK (see android-app/README.md,
 * "About verification"), but two real problems this produced have been
 * checked against the actual javadoc and fixed: `AuthenticationDetails`
 * lives under `.continuations`, not the top-level `cognitoidentityprovider`
 * package like the other model classes imported above (a compile error);
 * and [AuthenticationHandler.getAuthenticationDetails]'s `userIdFromServer`
 * parameter is nullable ("This will be null if the user ID is not
 * available," per the javadoc) — declaring it as non-null `String` compiled
 * fine (Java has no compile-time nullness, so Kotlin can't catch the
 * mismatch), but crashed at runtime with a Kotlin null-check
 * NullPointerException the first time the SDK actually called back with
 * null, which turned out to be immediately (a background token-refresh
 * kept no username around to report). Whatever `CognitoUserPool`'s
 * constructor and the remaining callback shapes turn out to need is still
 * the next thing to check if this gets further than these two.
 */
class CognitoAuthManager(
    context: Context,
    userPoolId: String,
    clientId: String,
    region: String,
) {
    private val userPool = run {
        // userPoolId/clientId/region are not secrets (they're public
        // identifiers baked into any app that talks to this pool), so
        // logging them is safe and is the fastest way to confirm whether
        // app/build.gradle.kts's REPLACE_WITH_YOUR_* placeholders got
        // swapped for real values before this crashes trying to use them.
        Log.d(TAG, "constructing CognitoUserPool — userPoolId=$userPoolId, clientId=$clientId, region=$region")
        try {
            CognitoUserPool(
                context.applicationContext,
                userPoolId,
                clientId,
                /* clientSecret = */ null,
                Regions.fromName(region),
            ).also { Log.d(TAG, "CognitoUserPool constructed") }
        } catch (e: Throwable) {
            Log.e(TAG, "CognitoUserPool construction failed — check userPoolId/clientId/region above for leftover placeholders or a typo", e)
            throw e
        }
    }

    val currentUsername: String? get() = userPool.currentUser?.userId

    /** Full SRP sign-in. Throws on failure — see [SignInException] for the cases the UI should handle distinctly. */
    suspend fun signIn(username: String, password: String): Unit = suspendCancellableCoroutine { cont ->
        Log.d(TAG, "signIn: starting SRP flow for '$username'")
        val user = userPool.getUser(username)
        user.getSessionInBackground(object : AuthenticationHandler {
            override fun onSuccess(userSession: CognitoUserSession, newDevice: CognitoDevice?) {
                Log.d(TAG, "signIn: success")
                if (cont.isActive) cont.resume(Unit)
            }

            override fun getAuthenticationDetails(continuation: AuthenticationContinuation, userIdFromServer: String?) {
                continuation.setAuthenticationDetails(AuthenticationDetails(username, password, null))
                continuation.continueTask()
            }

            override fun getMFACode(continuation: MultiFactorAuthenticationContinuation?) {
                // Not configured on the user pool (see auth-stack.ts) — if this
                // ever fires, MFA got turned on server-side without updating
                // this client.
                Log.e(TAG, "signIn: received an MFA challenge, which this client doesn't support")
                if (cont.isActive) cont.resumeWithException(IllegalStateException("MFA challenge received but this client doesn't support it yet"))
            }

            override fun authenticationChallenge(continuation: com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation?) {
                Log.e(TAG, "signIn: received an unsupported custom-auth challenge")
                if (cont.isActive) cont.resumeWithException(IllegalStateException("Unsupported Cognito custom-auth challenge"))
            }

            override fun onFailure(exception: Exception) {
                Log.e(TAG, "signIn: failed for '$username'", exception)
                if (cont.isActive) cont.resumeWithException(mapSignInFailure(exception))
            }
        })
    }

    /**
     * Returns a valid ID token, silently refreshing via the cached refresh
     * token if needed, or null if that's not possible (never signed in, or
     * the refresh token itself expired/was revoked — the UI should treat
     * null here as "show the login screen").
     */
    suspend fun currentIdTokenOrNull(): String? {
        val user = userPool.currentUser ?: return null
        return suspendCancellableCoroutine { cont ->
            user.getSessionInBackground(object : AuthenticationHandler {
                override fun onSuccess(userSession: CognitoUserSession, newDevice: CognitoDevice?) {
                    if (cont.isActive) cont.resume(userSession.idToken?.jwtToken)
                }

                override fun getAuthenticationDetails(continuation: AuthenticationContinuation, userIdFromServer: String?) {
                    // Refresh token is gone/expired — the SDK wants a fresh
                    // SRP round trip, which needs a password we don't have
                    // here. Treat as "not signed in"; leave the continuation
                    // unresolved rather than guessing credentials.
                    if (cont.isActive) cont.resume(null)
                }

                override fun getMFACode(continuation: MultiFactorAuthenticationContinuation?) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun authenticationChallenge(continuation: com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation?) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onFailure(exception: Exception) {
                    // Deliberately swallowed to null (see the doc comment
                    // above — "not signed in" is a normal outcome here, not
                    // an error), but logged so a *surprising* refresh
                    // failure is still visible in Logcat instead of just
                    // silently bouncing the user to the login screen.
                    Log.w(TAG, "currentIdTokenOrNull: refresh failed, treating as signed-out", exception)
                    if (cont.isActive) cont.resume(null)
                }
            })
        }
    }

    fun signOut() {
        Log.d(TAG, "signOut")
        userPool.currentUser?.signOut()
    }

    private fun mapSignInFailure(exception: Exception): Exception = when (exception) {
        is CognitoNotAuthorizedException -> SignInException.InvalidCredentials(exception)
        else -> SignInException.Other(exception)
    }

    private companion object {
        const val TAG = "TodoAi.CognitoAuth"
    }
}

sealed class SignInException(cause: Throwable) : Exception(cause) {
    class InvalidCredentials(cause: Throwable) : SignInException(cause)
    class Other(cause: Throwable) : SignInException(cause)
}
