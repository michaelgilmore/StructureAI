package cc.gilmore.todoai.ui.components

/**
 * Kotlin side of the RRULE subset in aws-backend/shared/recurrence.ts —
 * `FREQ=DAILY|WEEKLY|MONTHLY|YEARLY` with an optional `;INTERVAL=n`.
 * Keep [Freq] and [toWireString]/[fromWireString] in sync with that file,
 * not the other way around — the server is the source of truth for what's
 * a valid rule.
 */
enum class Freq(val wireValue: String, val label: String) {
    DAILY("DAILY", "Day"),
    WEEKLY("WEEKLY", "Week"),
    MONTHLY("MONTHLY", "Month"),
    YEARLY("YEARLY", "Year"),
}

data class RecurrenceRule(val freq: Freq, val interval: Int = 1) {
    fun toWireString(): String = if (interval <= 1) "FREQ=${freq.wireValue}" else "FREQ=${freq.wireValue};INTERVAL=$interval"

    companion object {
        fun fromWireString(value: String?): RecurrenceRule? {
            if (value.isNullOrBlank()) return null
            val parts = value.split(";").associate {
                val (key, v) = it.split("=", limit = 2).let { kv -> kv[0] to kv.getOrElse(1) { "" } }
                key to v
            }
            val freq = Freq.entries.firstOrNull { it.wireValue == parts["FREQ"] } ?: return null
            val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            return RecurrenceRule(freq, interval)
        }
    }
}
