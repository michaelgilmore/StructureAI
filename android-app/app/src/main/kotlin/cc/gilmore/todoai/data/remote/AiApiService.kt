package cc.gilmore.todoai.data.remote

import cc.gilmore.todoai.data.remote.dto.AiRequestResponseDto
import cc.gilmore.todoai.data.remote.dto.ChatRequestBody
import cc.gilmore.todoai.data.remote.dto.PlanRequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Matches aws-backend/lambdas/ai-orchestrator's routes exactly (Phase 2 —
 * Bedrock plan generation + dialog). Paths have no leading slash, same
 * convention as TodoApiService — Retrofit resolves them against
 * BuildConfig.API_BASE_URL.
 *
 * Two distinct suspend functions POST to the same "ai/request" path (see
 * AiDto.kt's doc comment on why the request body isn't one polymorphic
 * type) — Retrofit has no issue with two methods sharing a path/verb as
 * long as the Kotlin function signatures themselves differ.
 */
interface AiApiService {
    @POST("ai/request")
    suspend fun submitPlanRequest(@Body body: PlanRequestBody): Response<AiRequestResponseDto>

    @POST("ai/request")
    suspend fun submitChatRequest(@Body body: ChatRequestBody): Response<AiRequestResponseDto>

    @GET("ai/request/{reqId}")
    suspend fun getRequest(@Path("reqId") reqId: String): Response<AiRequestResponseDto>
}
