package cc.gilmore.todoai.repository

import cc.gilmore.todoai.data.local.GoalEntity
import cc.gilmore.todoai.data.local.SyncState
import cc.gilmore.todoai.data.remote.dto.CreateGoalRequest
import cc.gilmore.todoai.data.remote.dto.GoalDto
import cc.gilmore.todoai.data.remote.dto.UpdateGoalRequest
import cc.gilmore.todoai.model.GoalStatus

/** Mirrors Mappers.kt's Todo <-> wire conversions (see that file) — for Goal instead. */
fun GoalDto.toEntity(syncState: SyncState = SyncState.SYNCED): GoalEntity = GoalEntity(
    id = id,
    title = title,
    notes = notes,
    targetDate = targetDate,
    status = GoalStatus.fromWire(status),
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncState = syncState,
)

fun GoalEntity.toCreateRequest(): CreateGoalRequest = CreateGoalRequest(
    title = title,
    notes = notes,
    targetDate = targetDate,
)

/** Full-replace PATCH, not a partial diff — same tradeoff TodoEntity.toUpdateRequest's doc comment explains. */
fun GoalEntity.toUpdateRequest(): UpdateGoalRequest = UpdateGoalRequest(
    title = title,
    notes = notes,
    targetDate = targetDate,
    status = status.wireValue,
)
