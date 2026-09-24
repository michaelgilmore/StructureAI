package cc.gilmore.todoai.repository

import cc.gilmore.todoai.data.local.SyncState
import cc.gilmore.todoai.data.local.TodoEntity
import cc.gilmore.todoai.data.remote.dto.CreateTodoRequest
import cc.gilmore.todoai.data.remote.dto.TodoDto
import cc.gilmore.todoai.data.remote.dto.UpdateTodoRequest
import cc.gilmore.todoai.model.Priority
import cc.gilmore.todoai.model.TodoStatus

fun TodoDto.toEntity(syncState: SyncState = SyncState.SYNCED): TodoEntity = TodoEntity(
    id = id,
    title = title,
    notes = notes,
    status = TodoStatus.fromWire(status),
    priority = Priority.fromWire(priority),
    dueDate = dueDate,
    reminderAt = reminderAt,
    recurrenceRule = recurrenceRule,
    project = project,
    tags = tags,
    source = source,
    createdAt = createdAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    syncState = syncState,
)

fun TodoEntity.toCreateRequest(): CreateTodoRequest = CreateTodoRequest(
    title = title,
    notes = notes,
    priority = priority.wireValue,
    dueDate = dueDate,
    reminderAt = reminderAt,
    recurrenceRule = recurrenceRule,
    project = project,
    tags = tags.ifEmpty { null },
)

/** Full-replace PATCH, not a partial diff — simpler to reason about for a single-device v1, at the cost of a wasted no-op field or two on the wire. */
fun TodoEntity.toUpdateRequest(): UpdateTodoRequest = UpdateTodoRequest(
    title = title,
    notes = notes,
    status = status.wireValue,
    priority = priority.wireValue,
    dueDate = dueDate,
    reminderAt = reminderAt,
    recurrenceRule = recurrenceRule,
    project = project,
    tags = tags,
)
