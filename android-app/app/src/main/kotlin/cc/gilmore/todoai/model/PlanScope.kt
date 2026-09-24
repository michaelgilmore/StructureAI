package cc.gilmore.todoai.model

/**
 * Mirrors `PlanScope` in aws-backend/shared/types.ts / the `scope` enum in
 * shared/ai-schema.ts. [wireValue] is exactly what the API sends/expects —
 * keep it in sync with those files, not with however Kotlin happens to
 * name the enum constant. [label] is UI-only.
 */
enum class PlanScope(val wireValue: String, val label: String) {
    DAY("day", "Day"),
    WEEK("week", "Week"),
    MONTH("month", "Month"),
    YEAR("year", "Year");

    companion object {
        fun fromWire(value: String?): PlanScope = entries.firstOrNull { it.wireValue == value } ?: DAY
    }
}
