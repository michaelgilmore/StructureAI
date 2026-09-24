package cc.gilmore.todoai.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for aws-backend/lambdas/todo-api. Field names match the JSON
 * exactly (see shared/types.ts and shared/todo-schema.ts on the backend) —
 * kotlinx.serialization matches by property name by default, so keep these
 * in lockstep with the backend rather than renaming for Kotlin taste.
 */
@Serializable
data class TodoDto(
    val id: String,
    val title: String,
    val notes: String? = null,
    val status: String,
    val priority: String,
    val dueDate: String? = null,
    val reminderAt: String? = null,
    val recurrenceRule: String? = null,
    val project: String? = null,
    val tags: List<String> = emptyList(),
    val source: String,
    val createdAt: String,
    val updatedAt: String,
    // Server-derived — see shared/types.ts's doc comment on Todo.completedAt.
    // Absent (not just null) on responses from any backend deployed before
    // this field existed, hence the default rather than a required field.
    val completedAt: String? = null,
)

@Serializable
data class TodoListResponse(val items: List<TodoDto>)

/** Response body for PATCH /todos/{id} when completing a recurring todo — see materializeNextOccurrence server-side. */
@Serializable
data class UpdateTodoResponse(
    val id: String,
    val title: String,
    val notes: String? = null,
    val status: String,
    val priority: String,
    val dueDate: String? = null,
    val reminderAt: String? = null,
    val recurrenceRule: String? = null,
    val project: String? = null,
    val tags: List<String> = emptyList(),
    val source: String,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String? = null,
    val nextOccurrence: TodoDto? = null,
) {
    fun asTodoDto() = TodoDto(id, title, notes, status, priority, dueDate, reminderAt, recurrenceRule, project, tags, source, createdAt, updatedAt, completedAt)
}

/** Request body for POST /todos — matches createTodoSchema on the backend. Omitted fields are left out of the JSON, not sent as null, via NetworkModule's `explicitNulls = false`. */
@Serializable
data class CreateTodoRequest(
    val title: String,
    val notes: String? = null,
    val priority: String? = null,
    val dueDate: String? = null,
    val reminderAt: String? = null,
    val recurrenceRule: String? = null,
    val project: String? = null,
    val tags: List<String>? = null,
)

/** Request body for PATCH /todos/{id} — matches updateTodoSchema (createTodoSchema.partial() + status). */
@Serializable
data class UpdateTodoRequest(
    val title: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val dueDate: String? = null,
    val reminderAt: String? = null,
    val recurrenceRule: String? = null,
    val project: String? = null,
    val tags: List<String>? = null,
)

@Serializable
data class ApiErrorBody(val error: String, val details: kotlinx.serialization.json.JsonElement? = null)

/**
 * Now implemented server-side too — aws-backend's POST /device/token (see
 * lambdas/device-token/ and infra/lib/notifications-stack.ts) is the
 * push-notification follow-up that closes the gap flagged in
 * android-app/README.md ("What doesn't work end-to-end yet"): it creates
 * or reuses an SNS platform endpoint for this token and subscribes it to
 * the reminders topic, so reminder-scan's publishes actually reach the
 * phone now. Requires a one-time manual step on the backend (a Firebase
 * service-account secret in Secrets Manager) before it'll deploy — see
 * aws-backend/README.md's "Push notifications" section — but nothing on
 * the Android side changes; this DTO and TodoApiService.registerDeviceToken
 * were already written for exactly this shape.
 */
@Serializable
data class RegisterDeviceTokenRequest(val fcmToken: String)
