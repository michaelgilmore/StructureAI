package cc.gilmore.todoai.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The API works in plain strings — `dueDate` as `YYYY-MM-DD`, `reminderAt`
 * as ISO-8601 UTC (see aws-backend/shared/todo-schema.ts) — these convert
 * between that wire format and java.time for the date/time pickers.
 * minSdk 26 (see app/build.gradle.kts) means java.time is available
 * without desugaring.
 */
object DateUtils {
    private val dueDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parseDueDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it, dueDateFormatter) }.getOrNull() }

    fun formatDueDate(date: LocalDate): String = date.format(dueDateFormatter)

    fun displayDueDate(value: String?): String {
        val date = parseDueDate(value) ?: return "No due date"
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

    /** reminderAt is stored/sent as UTC instant text; pickers work in local time. */
    fun parseReminderAt(value: String?): LocalDateTime? = value?.let {
        runCatching { java.time.Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
    }

    fun formatReminderAt(localDateTime: LocalDateTime): String =
        localDateTime.atZone(ZoneId.systemDefault()).toInstant().toString()

    fun displayReminderAt(value: String?): String {
        val dateTime = parseReminderAt(value) ?: return "No reminder"
        return displayLocalDateTime(dateTime)
    }

    fun displayLocalDateTime(dateTime: LocalDateTime): String =
        dateTime.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

    /** completedAt is a UTC instant (see aws-backend/shared/types.ts's doc comment) — converted to a local calendar date for display and for the report's date-range filter. */
    fun parseCompletedAt(value: String?): LocalDate? = value?.let {
        runCatching { java.time.Instant.parse(it).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
    }

    fun displayCompletedAt(value: String?): String {
        val date = parseCompletedAt(value) ?: return "Completed"
        return "Completed " + date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}
