package cc.gilmore.todoai.repository

import cc.gilmore.todoai.data.local.RoutineEntity
import cc.gilmore.todoai.data.local.SyncState
import cc.gilmore.todoai.data.remote.dto.CreateRoutineRequest
import cc.gilmore.todoai.data.remote.dto.RoutineDto
import cc.gilmore.todoai.data.remote.dto.UpdateRoutineRequest
import cc.gilmore.todoai.model.RoutinePeriod
import cc.gilmore.todoai.model.RoutineStatus

/** Mirrors GoalMappers.kt's Goal <-> wire conversions (see that file) — for Routine instead. */
fun RoutineDto.toEntity(syncState: SyncState = SyncState.SYNCED): RoutineEntity = RoutineEntity(
    id = id,
    title = title,
    notes = notes,
    targetCount = targetCount,
    period = RoutinePeriod.fromWire(period),
    status = RoutineStatus.fromWire(status),
    createdAt = createdAt,
    updatedAt = updatedAt,
    syncState = syncState,
)

fun RoutineEntity.toCreateRequest(): CreateRoutineRequest = CreateRoutineRequest(
    title = title,
    notes = notes,
    targetCount = targetCount,
    period = period.wireValue,
)

/** Full-replace PATCH, not a partial diff — same tradeoff TodoEntity.toUpdateRequest's doc comment explains. */
fun RoutineEntity.toUpdateRequest(): UpdateRoutineRequest = UpdateRoutineRequest(
    title = title,
    notes = notes,
    targetCount = targetCount,
    period = period.wireValue,
    status = status.wireValue,
)
