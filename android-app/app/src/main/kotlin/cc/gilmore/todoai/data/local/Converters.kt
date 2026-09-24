package cc.gilmore.todoai.data.local

import androidx.room.TypeConverter
import cc.gilmore.todoai.model.Priority
import cc.gilmore.todoai.model.TodoStatus

/** Room can only persist primitives directly — these bridge our enums and List<String>. */
class Converters {
    @TypeConverter
    fun priorityToWire(value: Priority): String = value.wireValue

    @TypeConverter
    fun priorityFromWire(value: String): Priority = Priority.fromWire(value)

    @TypeConverter
    fun statusToWire(value: TodoStatus): String = value.wireValue

    @TypeConverter
    fun statusFromWire(value: String): TodoStatus = TodoStatus.fromWire(value)

    @TypeConverter
    fun syncStateToString(value: SyncState): String = value.name

    @TypeConverter
    fun syncStateFromString(value: String): SyncState = SyncState.valueOf(value)

    @TypeConverter
    fun tagsToString(tags: List<String>): String = tags.joinToString("") // unit separator; tags themselves can contain commas

    @TypeConverter
    fun tagsFromString(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("")
}
