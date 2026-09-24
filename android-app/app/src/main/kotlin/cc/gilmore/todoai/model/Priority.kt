package cc.gilmore.todoai.model

/**
 * Mirrors the `priority` enum in aws-backend/shared/todo-schema.ts
 * (createTodoSchema). [wireValue] is exactly what the API sends/expects —
 * keep it in sync with that file, not with however Kotlin happens to name
 * the enum constant.
 */
enum class Priority(val wireValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromWire(value: String?): Priority = entries.firstOrNull { it.wireValue == value } ?: MEDIUM
    }
}
