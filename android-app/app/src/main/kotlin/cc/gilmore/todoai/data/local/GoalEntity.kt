package cc.gilmore.todoai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import cc.gilmore.todoai.model.GoalStatus

/**
 * The local cache AND the offline outbox for goals, same combined-row
 * design as TodoEntity (see that class's doc comment and android-app/
 * README.md's "Offline sync model") — just a much smaller field set, since
 * a goal has no priority/tags/project/recurrence/reminder.
 *
 * [id] is the server's id once known; a goal created while offline gets a
 * client-generated placeholder (prefixed [LOCAL_ID_PREFIX]) until the
 * create reaches the server — see GoalRepository.syncCreate.
 */
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    val targetDate: String?,
    val status: GoalStatus,
    val createdAt: String,
    val updatedAt: String,
    val syncState: SyncState,
)
