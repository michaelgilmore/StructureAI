package cc.gilmore.todoai.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Wire shapes for aws-backend/lambdas/routine-api — mirrors GoalDto.kt's
 * doc comment: field names match shared/types.ts's Routine /
 * shared/routine-schema.ts exactly.
 */
@Serializable
data class RoutineDto(
    val id: String,
    val title: String,
    val notes: String? = null,
    val targetCount: Int,
    val period: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class RoutineListResponse(val items: List<RoutineDto>)

/** Request body for POST /routines — matches createRoutineSchema on the backend. */
@Serializable
data class CreateRoutineRequest(
    val title: String,
    val notes: String? = null,
    val targetCount: Int,
    val period: String,
)

/** Request body for PATCH /routines/{id} — matches updateRoutineSchema (createRoutineSchema.partial() + status). */
@Serializable
data class UpdateRoutineRequest(
    val title: String? = null,
    val notes: String? = null,
    val targetCount: Int? = null,
    val period: String? = null,
    val status: String? = null,
)
