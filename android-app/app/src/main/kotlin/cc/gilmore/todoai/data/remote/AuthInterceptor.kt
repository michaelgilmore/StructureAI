package cc.gilmore.todoai.data.remote

import cc.gilmore.todoai.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches `Authorization: Bearer <idToken>` to every request, refreshing
 * first if the cached token is stale (see AuthRepository.currentIdTokenOrNull
 * / CognitoAuthManager — the refresh is transparent, uses the cached
 * refresh token, no password needed in the common case).
 *
 * `runBlocking` here is intentional, not an oversight: OkHttp interceptors
 * run on OkHttp's own dispatcher thread, never the main thread, so
 * blocking here doesn't freeze the UI — it just makes this one request
 * wait on the token refresh, which is what we want anyway.
 */
class AuthInterceptor(private val authRepository: AuthRepository) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { authRepository.currentIdTokenOrNull() }
        val request = chain.request().newBuilder().apply {
            if (token != null) addHeader("Authorization", "Bearer $token")
        }.build()
        return chain.proceed(request)
    }
}
