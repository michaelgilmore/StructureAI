package cc.gilmore.todoai.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    /** PENDING_DELETE rows are hidden — they're gone from the user's point of view, just not yet confirmed with the server. */
    @Query("SELECT * FROM todos WHERE syncState != 'PENDING_DELETE' ORDER BY (dueDate IS NULL), dueDate ASC, createdAt DESC")
    fun observeAll(): Flow<List<TodoEntity>>

    /** One-shot equivalent of [observeAll] — for the report feature, which needs a snapshot to filter and render, not a live stream. */
    @Query("SELECT * FROM todos WHERE syncState != 'PENDING_DELETE' ORDER BY (dueDate IS NULL), dueDate ASC, createdAt DESC")
    suspend fun getAllOnce(): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE syncState != 'PENDING_DELETE' AND status = :status ORDER BY (dueDate IS NULL), dueDate ASC, createdAt DESC")
    fun observeByStatus(status: String): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getById(id: String): TodoEntity?

    @Query("SELECT * FROM todos WHERE syncState = :state")
    suspend fun getBySyncState(state: SyncState): List<TodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(todo: TodoEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(todos: List<TodoEntity>)

    @Update
    suspend fun update(todo: TodoEntity)

    @Delete
    suspend fun delete(todo: TodoEntity)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Swaps a locally-created row's placeholder id for the server-assigned one. Room's PK can't be updated in place. */
    @Transaction
    suspend fun replaceId(oldId: String, replacement: TodoEntity) {
        deleteById(oldId)
        upsert(replacement)
    }

    /**
     * Used after a full GET /todos pull: overwrite every SYNCED row with the
     * server's copy, but never touch a row that has local changes still
     * pending — those get resolved by the push half of sync instead, and
     * would otherwise be clobbered by a concurrent pull. See
     * repository/TodoRepository.kt (pullFromServer).
     */
    @Transaction
    suspend fun replaceAllSynced(serverTodos: List<TodoEntity>) {
        val pendingIds = getBySyncState(SyncState.PENDING_CREATE).map { it.id }.toSet() +
            getBySyncState(SyncState.PENDING_UPDATE).map { it.id }.toSet() +
            getBySyncState(SyncState.PENDING_DELETE).map { it.id }.toSet()
        val toKeep = serverTodos.filterNot { it.id in pendingIds }
        deleteAllSynced()
        upsertAll(toKeep)
    }

    @Query("DELETE FROM todos WHERE syncState = 'SYNCED'")
    suspend fun deleteAllSynced()
}
