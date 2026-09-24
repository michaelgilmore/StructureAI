package cc.gilmore.todoai.model

/**
 * Mirrors the two statuses actually used server-side (see
 * aws-backend/lambdas/todo-api/index.ts — completion is checked as
 * `status === 'done'`, everything else defaults to 'open'). If the backend
 * grows an "in progress" state later, add it here and in the server's
 * zod schema together.
 */
enum class TodoStatus(val wireValue: String) {
    OPEN("open"),
    DONE("done");

    companion object {
        fun fromWire(value: String?): TodoStatus = entries.firstOrNull { it.wireValue == value } ?: OPEN
    }
}
