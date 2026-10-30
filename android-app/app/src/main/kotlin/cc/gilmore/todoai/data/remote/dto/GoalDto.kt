package cc.gilmore.todoai.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Wire shapes for aws-backend/lambdas/goal-api — mirrors TodoDto.kt's doc
 * comment: field names match shared/types.ts's Goal / shared/goal-schema.ts
 * exactly.
 */
@Serializable
data class GoalDto(
    val id: String,
    val title: String,
    val notes: String? = null,
    val targetDate: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class GoalListResponse(val items: List<GoalDto>)

/** Request body for POST /goals — matches createGoalSchema on the backend. */
@Serializable
data class CreateGoalRequest(
    val title: String,
    val notes: String? = null,
    val targetDate: String? = null,
)

/** Request body for PATCH /goals/{id} — matches updateGoalSchema (createGoalSchema.partial() + status). */
@Serializable
data class UpdateGoalRequest(
    val title: String? = null,
    val notes: String? = null,
    val targetDate: String? = null,
    val status: String? = null,
)
