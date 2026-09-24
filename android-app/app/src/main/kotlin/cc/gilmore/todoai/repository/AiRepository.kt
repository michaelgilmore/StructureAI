package cc.gilmore.todoai.repository

import android.util.Log
import cc.gilmore.todoai.data.local.AiModeStore
import cc.gilmore.todoai.data.remote.AiApiService
import cc.gilmore.todoai.data.remote.NetworkModule
import cc.gilmore.todoai.data.remote.dto.AiRequestResponseDto
import cc.gilmore.todoai.data.remote.dto.ApiErrorBody
import cc.gilmore.todoai.data.remote.dto.ChatRequestBody
import cc.gilmore.todoai.data.remote.dto.ChatSendResultDto
import cc.gilmore.todoai.data.remote.dto.PlanRequestBody
import cc.gilmore.todoai.data.remote.dto.PlanResultDto
import cc.gilmore.todoai.model.ChatSubjectType
import cc.gilmore.todoai.model.PlanScope
import kotlinx.serialization.json.decodeFromJsonElement
import retrofit2.Response

/**
 * Phase 2 (Bedrock) — plan generation and dialog. Deliberately unlike
 * TodoRepository: there's no Room cache here (a plan or a chat reply is a
 * live Bedrock call, not offline-first data — see docs/architecture.md
 * §07, "AI screens have no local cache"), so failures are surfaced to the
 * caller as a [Result] instead of swallowed the way TodoRepository.refresh
 * swallows a network failure — "the AI didn't answer" is something the
 * Planner/Dialog screens need to show, not something to retry silently
 * later.
 *
 * Every request is built with [aiModeStore]'s currently-persisted [cc.gilmore.todoai.model.AiMode]
 * rather than a hardcoded "cloud" literal — see AiModeStore's doc comment
 * for why that's still effectively always "cloud" in practice today.
 */
class AiRepository(private val api: AiApiService, private val aiModeStore: AiModeStore) {

    suspend fun generatePlan(scope: PlanScope, periodStart: String?, focus: String?): Result<PlanResultDto> = runCatching {
        val response = api.submitPlanRequest(
            PlanRequestBody(
                mode = aiModeStore.currentMode().wireValue,
                scope = scope.wireValue,
                periodStart = periodStart,
                focus = focus?.takeIf { it.isNotBlank() },
            ),
        )
        val dto = unwrap(response, "generatePlan")
        val result = dto.result ?: error("generatePlan: status=${dto.status} but no result")
        NetworkModule.json.decodeFromJsonElement<PlanResultDto>(result)
    }.onFailure { e -> Log.w(TAG, "generatePlan: failed", e) }

    suspend fun sendChatMessage(
        subjectType: ChatSubjectType,
        subjectId: String?,
        conversationId: String?,
        message: String,
    ): Result<ChatSendResultDto> = runCatching {
        val response = api.submitChatRequest(
            ChatRequestBody(
                mode = aiModeStore.currentMode().wireValue,
                subjectType = subjectType.wireValue,
                subjectId = subjectId,
                conversationId = conversationId,
                message = message,
            ),
        )
        val dto = unwrap(response, "sendChatMessage")
        val result = dto.result ?: error("sendChatMessage: status=${dto.status} but no result")
        NetworkModule.json.decodeFromJsonElement<ChatSendResultDto>(result)
    }.onFailure { e -> Log.w(TAG, "sendChatMessage: failed", e) }

    /**
     * `GET /ai/request/{reqId}` — a fallback for the rare case a create
     * call comes back `status: "sent"` instead of resolving synchronously
     * (see docs/api/README.md's note on this). Not called from either
     * screen today — both request kinds are fast enough in practice per
     * docs/architecture.md §03 — kept small so a future retry-on-"sent"
     * doesn't need new plumbing added under time pressure later. Callers
     * decode `.result` themselves (into PlanResultDto or
     * ConversationResultDto depending on `.kind`), since GET's chat shape
     * differs from POST's condensed one.
     */
    suspend fun pollRequest(reqId: String): Result<AiRequestResponseDto> = runCatching {
        unwrap(api.getRequest(reqId), "pollRequest")
    }.onFailure { e -> Log.w(TAG, "pollRequest: failed", e) }

    /**
     * Throws with the server's `error` message on a non-2xx response,
     * parsed via [ApiErrorBody] — the same shape ai-orchestrator's `json()`
     * helper uses for every error response it sends (validation failures,
     * a request that fails after being recorded, a 404). ignoreUnknownKeys
     * means the extra `reqId`/`status` fields on a failed-request body
     * don't need their own DTO.
     */
    private fun unwrap(response: Response<AiRequestResponseDto>, caller: String): AiRequestResponseDto {
        if (response.isSuccessful) {
            return response.body() ?: error("$caller: empty response body")
        }
        val serverMessage = response.errorBody()?.string()?.let { text ->
            runCatching { NetworkModule.json.decodeFromString(ApiErrorBody.serializer(), text).error }.getOrNull()
        }
        Log.w(TAG, "$caller: non-2xx response ${response.code()}${serverMessage?.let { ": $it" } ?: ""}")
        throw AiRequestException(serverMessage ?: "Request failed (${response.code()})")
    }

    private companion object {
        const val TAG = "TodoAi.AiRepository"
    }
}

class AiRequestException(message: String) : Exception(message)
