package cc.gilmore.todoai.data.remote

import cc.gilmore.todoai.BuildConfig
import cc.gilmore.todoai.repository.AuthRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val TAG = "TodoAi.NetworkModule"

    /**
     * Shared kotlinx.serialization instance — also used directly by
     * AiRepository to decode `/ai/request`'s polymorphic `result` field
     * (its shape depends on `kind`; see AiDto.kt), not just internally by
     * Retrofit's converter factory below.
     */
    val json = Json {
        ignoreUnknownKeys = true // server can add fields (later phases) without breaking old app builds
        explicitNulls = false // omit null fields from outgoing request bodies instead of sending `"field":null`

        // Without this, kotlinx.serialization's own default (encodeDefaults
        // = false) silently drops any property whose current value equals
        // its declared Kotlin default — e.g. AiDto.kt's PlanRequestBody/
        // ChatRequestBody `kind: String = "plan"` / `= "chat"` was being
        // omitted from every outgoing request, since it's always
        // constructed at that default. The backend's zod
        // discriminatedUnion('kind', ...) (shared/ai-schema.ts) has no
        // default for `kind` — it's the discriminator — so a request
        // missing it entirely fails validation before either branch of the
        // union is even considered, which is what surfaced client-side as
        // "the app is saying validation required" on every Planner/Dialog
        // request. Safe to flip globally: explicitNulls=false above still
        // independently omits any field whose *value* is null (confirmed
        // against kotlinx.serialization's docs — explicitNulls governs
        // that regardless of encodeDefaults), so CreateTodoRequest/
        // UpdateTodoRequest's all-nullable-default fields keep being
        // omitted exactly as before; only non-null-default fields like
        // AiDto's `kind`/`mode`/`subjectType` newly get encoded.
        encodeDefaults = true
    }

    fun buildApiService(authRepository: AuthRepository): TodoApiService =
        buildService(authRepository, "buildApiService", TodoApiService::class.java)

    /** Phase 2 — Bedrock plan/dialog. Same Retrofit setup (base URL, auth, logging) as [buildApiService]; just a different generated interface — see AiApiService.kt. */
    fun buildAiApiService(authRepository: AuthRepository): AiApiService =
        buildService(authRepository, "buildAiApiService", AiApiService::class.java)

    private fun <T> buildService(authRepository: AuthRepository, callerTag: String, service: Class<T>): T {
        // Printed because Retrofit.Builder().baseUrl(...) throws
        // IllegalArgumentException for a malformed URL, and a leftover
        // REPLACE_WITH_YOUR_API_URL placeholder (see app/build.gradle.kts)
        // is a likely first-run cause of exactly that — this line makes it
        // obvious which case you're in before chasing anything else.
        Log.d(TAG, "$callerTag: baseUrl=${BuildConfig.API_BASE_URL}")

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(authRepository))
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return try {
            val retrofit = Retrofit.Builder()
                .baseUrl(BuildConfig.API_BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()

            retrofit.create(service).also { Log.d(TAG, "$callerTag: Retrofit service ready") }
        } catch (e: Throwable) {
            Log.e(TAG, "$callerTag: failed — check baseUrl above for a leftover placeholder or malformed URL", e)
            throw e
        }
    }
}
