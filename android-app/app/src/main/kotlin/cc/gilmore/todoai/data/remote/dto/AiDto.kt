package cc.gilmore.todoai.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire shapes for aws-backend/lambdas/ai-orchestrator — `POST /ai/request`
 * and `GET /ai/request/{reqId}`. See ../../../../../../../../docs/api/README.md
 * for the full contract this mirrors, and aws-backend/shared/ai-schema.ts /
 * shared/types.ts on the backend side. Field names match the JSON exactly,
 * same convention as TodoDto.kt.
 *
 * The request body's shape depends on `kind` ("plan" vs "chat") — modeled
 * here as two separate @Serializable classes (PlanRequestBody,
 * ChatRequestBody) rather than one sealed/polymorphic type, since these
 * are only ever *sent*, never decoded back — there's no need for
 * kotlinx.serialization's polymorphic-decoding machinery for that.
 * AiApiService has one suspend function per kind, both POSTing to the
 * same path; Retrofit has no issue with that as long as the Kotlin
 * signatures differ.
 *
 * The response is the mirror problem: `result`'s shape depends on `kind`
 * too. Modeled here as a raw [JsonElement], decoded into a typed
 * [PlanResultDto] / [ChatSendResultDto] / [ConversationResultDto] by
 * AiRepository, which is the one place that already knows which kind of
 * request it just made.
 *
 * `mode` is populated from AiRepository's [cc.gilmore.todoai.data.local.AiModeStore]
 * rather than hardcoded here — but see that store's (and model/AiMode.kt's)
 * doc comments for why it's still effectively always "cloud" today:
 * docs/architecture.md §11 covers why the app's mode toggle stays disabled
 * until Phase 3's Telegram bridge exists. The `"cloud"` default below is
 * just a defensive fallback for a hypothetical caller that forgets to set
 * it — every real call site sets it explicitly.
 */

@Serializable
data class PlanRequestBody(
    val kind: String = "plan",
    val mode: String = "cloud",
    val scope: String,
    /** YYYY-MM-DD. Omit to let the server default to today (UTC) — see shared/plan-period.ts. */
    val periodStart: String? = null,
    val focus: String? = null,
)

@Serializable
data class ChatRequestBody(
    val kind: String = "chat",
    val mode: String = "cloud",
    val subjectType: String = "general",
    /** A todo id (subjectType="todo") or a Plan's "{scope}#{periodStart}" (subjectType="plan"). */
    val subjectId: String? = null,
    /** Omit to start a new conversation; pass an existing one's id to continue it. */
    val conversationId: String? = null,
    val message: String,
)

@Serializable
data class AiRequestResponseDto(
    val reqId: String,
    val status: String, // "sent" | "done" | "failed"
    val kind: String? = null,
    val mode: String? = null,
    val result: JsonElement? = null,
    val error: String? = null,
)

@Serializable
data class PlanItemDto(val todoId: String, val rationale: String)

/**
 * Decoded from [AiRequestResponseDto.result] when kind == "plan". Same
 * shape for both POST's synchronous result and a GET poll's result (the
 * server's stripKeys()'d PlanRecord) — GET's copy additionally carries
 * `userId`, which NetworkModule.json's `ignoreUnknownKeys` drops
 * harmlessly since it isn't a field here.
 */
@Serializable
data class PlanResultDto(
    val scope: String,
    /** YYYY-MM-DD, inclusive. */
    val periodStart: String,
    /** YYYY-MM-DD, inclusive. */
    val periodEnd: String,
    val summary: String,
    val items: List<PlanItemDto> = emptyList(),
    val engine: String,
    val generatedAt: String,
)

/**
 * Decoded from [AiRequestResponseDto.result] when kind == "chat" on the
 * synchronous POST path — the condensed shape, not the full Conversation
 * record GET returns (see [ConversationResultDto]).
 *
 * `createdTodos` mirrors aws-backend/shared/ai-finalize.ts's
 * `createRequestedTodos` return value — full [TodoDto]s (server's
 * stripKeys()'d TodoItem, same shape `GET /todos` returns), not just ids,
 * so DialogViewModel can upsert them into Room without a round trip. Only
 * ever populated on the Cloud path (see ai-orchestrator/index.ts's runChat)
 * — Home mode's telegram-webhook has no synchronous response channel to
 * put this in, so a Home-mode reply always decodes this as empty and the
 * created todo(s) just show up on the next `GET /todos` sync instead.
 * Absent (not just empty) on any backend deployed before this field
 * existed, hence the default.
 */
@Serializable
data class ChatSendResultDto(
    val conversationId: String,
    val subjectType: String,
    val subjectId: String? = null,
    val reply: String,
    val createdTodos: List<TodoDto> = emptyList(),
)

@Serializable
data class ConversationMessageDto(
    val role: String, // "user" | "assistant"
    val text: String,
    /** Set on assistant messages only — which engine actually answered. */
    val engine: String? = null,
    val ts: String,
)

/**
 * Decoded from [AiRequestResponseDto.result] when kind == "chat" on a GET
 * poll — the full stored Conversation, not the condensed POST shape. Not
 * wired into DialogScreen today — see AiRepository.pollRequest's doc
 * comment — kept so that plumbing doesn't need a new DTO added later.
 */
@Serializable
data class ConversationResultDto(
    val id: String,
    val subjectType: String,
    val subjectId: String? = null,
    val messages: List<ConversationMessageDto> = emptyList(),
    val updatedAt: String,
)
