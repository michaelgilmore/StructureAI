package cc.gilmore.todoai.model

/** Mirrors aws-backend/shared/types.ts's RoutineStatus — same two-state shape as GoalStatus (see that file's doc comment), kept as its own enum rather than reused since a Routine's and a Goal's statuses are conceptually different things that happen to look alike today. */
enum class RoutineStatus(val wireValue: String) {
    ACTIVE("active"),
    ARCHIVED("archived");

    companion object {
        fun fromWire(value: String?): RoutineStatus = entries.firstOrNull { it.wireValue == value } ?: ACTIVE
    }
}
