package cc.gilmore.todoai.model

/**
 * Mirrors `ChatSubjectType` in aws-backend/shared/types.ts / the
 * `subjectType` enum in shared/ai-schema.ts — what a dialog conversation
 * is grounded in. [wireValue] is exactly what the API sends/expects.
 */
enum class ChatSubjectType(val wireValue: String) {
    PLAN("plan"),
    TODO("todo"),
    GENERAL("general"),
    /** A conversational planning session grounded in ALL open todos, not one stored Plan record — see aws-backend/shared/ai-schema.ts's chatRequestSchema doc comment and PlannerScreen.kt. */
    PLANNING("planning");

    companion object {
        fun fromWire(value: String?): ChatSubjectType = entries.firstOrNull { it.wireValue == value } ?: GENERAL
    }
}
