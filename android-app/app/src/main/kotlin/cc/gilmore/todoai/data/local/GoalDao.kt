package cc.gilmore.todoai.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Mirrors TodoDao's shape exactly (see that file's doc comments for the reasoning) — just over the "goals" table. */
@Dao
interface GoalDao {
    @Query("SELECT * FROM goals WHERE syncState != 'PENDING_DELETE' ORDER BY (targetDate IS NULL), targetDate ASC, createdAt DESC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE syncState != 'PENDING_DELETE' AND status = :status ORDER BY (targetDate IS NULL), targetDate ASC, createdAt DESC")
    fun observeByStatus(status: String): Flow<List<GoalEntity>>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun getById(id: String): GoalEntity?

    @Query("SELECT * FROM goals WHERE syncState = :state")
    suspend fun getBySyncState(state: SyncState): List<GoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(goal: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(goals: List<GoalEntity>)

    @Update
    suspend fun update(goal: GoalEntity)

    @Delete
    suspend fun delete(goal: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Swaps a locally-created row's placeholder id for the server-assigned one. Room's PK can't be updated in place. */
    @Transaction
    suspend fun replaceId(oldId: String, replacement: GoalEntity) {
        deleteById(oldId)
        upsert(replacement)
    }

    /** Used after a full GET /goals pull — see GoalRepository.refresh and TodoDao.replaceAllSynced's doc comment for the same pattern. */
    @Transaction
    suspend fun replaceAllSynced(serverGoals: List<GoalEntity>) {
        val pendingIds = getBySyncState(SyncState.PENDING_CREATE).map { it.id }.toSet() +
            getBySyncState(SyncState.PENDING_UPDATE).map { it.id }.toSet() +
            getBySyncState(SyncState.PENDING_DELETE).map { it.id }.toSet()
        val toKeep = serverGoals.filterNot { it.id in pendingIds }
        deleteAllSynced()
        upsertAll(toKeep)
    }

    @Query("DELETE FROM goals WHERE syncState = 'SYNCED'")
    suspend fun deleteAllSynced()
}
