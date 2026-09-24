package cc.gilmore.todoai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import cc.gilmore.todoai.model.Priority
import cc.gilmore.todoai.model.TodoStatus

/**
 * The local cache AND the offline outbox, combined into one row per todo —
 * see android-app/README.md ("Offline sync model") for why this is a
 * simpler, deliberate alternative to a generic append-only operation log.
 *
 * [id] is the server's id once known. A todo created while offline gets a
 * client-generated placeholder (prefixed [LOCAL_ID_PREFIX]) until the
 * create actually reaches the server — see TodoRepository.syncPendingCreate,
 * which swaps the row's primary key for the real one on success. The
 * server always assigns its own id on POST /todos (see
 * aws-backend/lambdas/todo-api/index.ts) — it never accepts a client id.
 */
@Entity(tableName = "todos")
data class TodoEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    val status: TodoStatus,
    val priority: Priority,
    val dueDate: String?,
    val reminderAt: String?,
    val recurrenceRule: String?,
    val project: String?,
    val tags: List<String>,
    val source: String,
    val createdAt: String,
    val updatedAt: String,
    // Server-derived — see aws-backend/shared/types.ts's doc comment on
    // Todo.completedAt. Null for an open todo, or for a done todo synced
    // from a backend deployed before this field existed (AppDatabase's
    // MIGRATION_1_2 backfills the column as NULL, not a guessed date).
    val completedAt: String?,
    val syncState: SyncState,
)

enum class SyncState {
    SYNCED,
    PENDING_CREATE,
    PENDING_UPDATE,
    PENDING_DELETE,
}

fun isLocalOnlyId(id: String): Boolean = id.startsWith(LOCAL_ID_PREFIX)

const val LOCAL_ID_PREFIX = "local-"
