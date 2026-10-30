package cc.gilmore.todoai.model

/**
 * Mirrors aws-backend/shared/types.ts's GoalStatus — 'active' goals are the
 * ones shown by default and the ones fed into the planning AI's context
 * (see aws-backend/lambdas/ai-orchestrator/index.ts's buildSubjectContext
 * 'planning' case); 'archived' is a soft-delete-ish state the user can
 * still browse, same relationship TodoStatus.DONE has to OPEN but without
 * ever feeding back into active-goal logic.
 */
enum class GoalStatus(val wireValue: String) {
    ACTIVE("active"),
    ARCHIVED("archived");

    companion object {
        fun fromWire(value: String?): GoalStatus = entries.firstOrNull { it.wireValue == value } ?: ACTIVE
    }
}
