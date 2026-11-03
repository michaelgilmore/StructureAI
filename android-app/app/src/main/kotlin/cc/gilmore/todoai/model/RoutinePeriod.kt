package cc.gilmore.todoai.model

/** Mirrors aws-backend/shared/types.ts's RoutinePeriod — how often a Routine's targetCount is meant to happen. */
enum class RoutinePeriod(val wireValue: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    companion object {
        fun fromWire(value: String?): RoutinePeriod = entries.firstOrNull { it.wireValue == value } ?: WEEK
    }
}
