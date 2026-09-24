package cc.gilmore.todoai

import android.content.Context
import android.util.Log
import cc.gilmore.todoai.auth.CognitoAuthManager
import cc.gilmore.todoai.auth.CredentialStore
import cc.gilmore.todoai.connectivity.ConnectivityObserver
import cc.gilmore.todoai.data.local.AiModeStore
import cc.gilmore.todoai.data.local.AppDatabase
import cc.gilmore.todoai.data.remote.NetworkModule
import cc.gilmore.todoai.repository.AiRepository
import cc.gilmore.todoai.repository.AuthRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.sync.SyncScheduler

/**
 * Manual dependency injection — deliberately not Hilt/Dagger. This app is
 * small enough that a hand-written object graph is easy to read start to
 * finish in one file, and it avoids an annotation-processor toolchain
 * (kapt/KSP version-matching, generated-code build steps) as one more
 * thing that could go wrong in an environment where I can't run a real
 * build to check (see android-app/README.md, "About verification"). Room
 * still uses KSP — that one's hard to avoid — but Hilt's own codegen is
 * the piece skipped here.
 *
 * Every screen/worker reaches this the same way: `(context.applicationContext
 * as TodoAiApplication).container`.
 *
 * Every property below goes through [step], so a construction failure logs
 * exactly which dependency failed instead of just "AppContainer failed" —
 * these run synchronously in TodoAiApplication.onCreate, so any one of them
 * throwing is a likely cause of a launch-time crash. `BuildConfig.API_BASE_URL`
 * / `COGNITO_USER_POOL_ID` / `COGNITO_CLIENT_ID` still holding their
 * `REPLACE_WITH_YOUR_*` placeholder values (see app/build.gradle.kts) is the
 * most likely first-run cause — [CognitoAuthManager]'s and
 * [cc.gilmore.todoai.data.remote.NetworkModule]'s logs below print the
 * actual values being used so that's easy to confirm or rule out.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val cognitoAuthManager = step("CognitoAuthManager") {
        CognitoAuthManager(
            appContext,
            BuildConfig.COGNITO_USER_POOL_ID,
            BuildConfig.COGNITO_CLIENT_ID,
            BuildConfig.COGNITO_REGION,
        )
    }

    val authRepository = step("AuthRepository") { AuthRepository(cognitoAuthManager) }

    // Backs the login screen's "Remember me" — see CredentialStore's own
    // doc comment for the security tradeoff. Deliberately NOT run through
    // `step()`: a failure to stand up encrypted storage shouldn't be a
    // launch-crashing condition the way a failure to construct auth/DB/
    // network would be — CredentialStore already degrades to "remember me
    // silently unavailable" on its own construction failure.
    val credentialStore = CredentialStore(appContext)

    private val todoDao = step("AppDatabase") { AppDatabase.get(appContext).todoDao() }
    private val apiService = step("NetworkModule.buildApiService") { NetworkModule.buildApiService(authRepository) }
    val connectivityObserver = step("ConnectivityObserver") { ConnectivityObserver(appContext) }
    val syncScheduler = step("SyncScheduler") { SyncScheduler(appContext) }

    val todoRepository = step("TodoRepository") { TodoRepository(todoDao, apiService, syncScheduler) }

    // Phase 2 (Bedrock plan generation + dialog) — a separate Retrofit
    // service/repository pair rather than folding onto TodoApiService/
    // TodoRepository, since AI requests have no local cache and a very
    // different failure-handling story (surfaced, not swallowed) — see
    // AiRepository's own doc comment.
    private val aiApiService = step("NetworkModule.buildAiApiService") { NetworkModule.buildAiApiService(authRepository) }

    // Groundwork for Phase 3 — see AiModeStore's and AiMode's doc comments.
    // Reads/writes real storage today, but nothing in the UI can reach the
    // write path yet, so every request still ends up built with CLOUD.
    val aiModeStore = step("AiModeStore") { AiModeStore(appContext) }
    val aiRepository = step("AiRepository") { AiRepository(aiApiService, aiModeStore) }

    private inline fun <T> step(name: String, block: () -> T): T {
        Log.d(TAG, "initializing $name…")
        return try {
            block().also { Log.d(TAG, "$name ready") }
        } catch (e: Throwable) {
            Log.e(TAG, "$name FAILED to initialize", e)
            throw e
        }
    }

    private companion object {
        const val TAG = "TodoAi.AppContainer"
    }
}
