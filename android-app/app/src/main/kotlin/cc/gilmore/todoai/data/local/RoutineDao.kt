package cc.gilmore.todoai.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Mirrors GoalDao's shape exactly (see that file's doc comments) — just over the "routines" table. */
@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines WHERE syncState != 'PENDING_DELETE' ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun getById(id: String): RoutineEntity?

    @Query("SELECT * FROM routines WHERE syncState = :state")
    suspend fun getBySyncState(state: SyncState): List<RoutineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: RoutineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(routines: List<RoutineEntity>)

    @Update
    suspend fun update(routine: RoutineEntity)

    @Delete
    suspend fun delete(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Swaps a locally-created row's placeholder id for the server-assigned one. Room's PK can't be updated in place. */
    @Transaction
    suspend fun replaceId(oldId: String, replacement: RoutineEntity) {
        deleteById(oldId)
        upsert(replacement)
    }

    /** Used after a full GET /routines pull — see GoalDao.replaceAllSynced's doc comment for the same pattern. */
    @Transaction
    suspend fun replaceAllSynced(serverRoutines: List<RoutineEntity>) {
        val pendingIds = getBySyncState(SyncState.PENDING_CREATE).map { it.id }.toSet() +
            getBySyncState(SyncState.PENDING_UPDATE).map { it.id }.toSet() +
            getBySyncState(SyncState.PENDING_DELETE).map { it.id }.toSet()
        val toKeep = serverRoutines.filterNot { it.id in pendingIds }
        deleteAllSynced()
        upsertAll(toKeep)
    }

    @Query("DELETE FROM routines WHERE syncState = 'SYNCED'")
    suspend fun deleteAllSynced()
}
