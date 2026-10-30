package cc.gilmore.todoai.repository

import android.util.Log
import cc.gilmore.todoai.data.local.GoalDao
import cc.gilmore.todoai.data.local.GoalEntity
import cc.gilmore.todoai.data.local.LOCAL_ID_PREFIX
import cc.gilmore.todoai.data.local.SyncState
import cc.gilmore.todoai.data.local.isLocalOnlyId
import cc.gilmore.todoai.data.remote.GoalApiService
import cc.gilmore.todoai.data.remote.dto.GoalDto
import cc.gilmore.todoai.model.GoalStatus
import cc.gilmore.todoai.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Single source of truth for goals — same offline-first design as
 * TodoRepository (see that class's doc comment): the UI only ever reads
 * from Room, every mutation writes to Room first, then pushes to the API
 * right away or leaves the row pending for SyncWorker. [syncNow] is called
 * from the same SyncWorker.doWork() run as TodoRepository.syncNow(), not a
 * separate schedule — see SyncWorker.kt.
 */
class GoalRepository(
    private val dao: GoalDao,
    private val api: GoalApiService,
    private val syncScheduler: SyncScheduler,
) {
    fun observeGoals(): Flow<List<GoalEntity>> = dao.observeAll()

    suspend fun getGoal(id: String): GoalEntity? = dao.getById(id)

    /** Best-effort pull from the server. Swallows network failures — offline is an expected, normal state, not an error to surface. */
    suspend fun refresh() {
        runCatching {
            val response = api.listGoals()
            if (response.isSuccessful) {
                val items = response.body()?.items.orEmpty()
                dao.replaceAllSynced(items.map { it.toEntity() })
                Log.d(TAG, "refresh: pulled ${items.size} goals")
            } else {
                Log.w(TAG, "refresh: non-2xx response ${response.code()}")
            }
        }.onFailure { e -> Log.w(TAG, "refresh: failed, leaving local data as-is", e) }
    }

    suspend fun createGoal(title: String, notes: String?, targetDate: String?) {
        val now = java.time.Instant.now().toString()
        val entity = GoalEntity(
            id = LOCAL_ID_PREFIX + UUID.randomUUID(),
            title = title,
            notes = notes,
            targetDate = targetDate,
            status = GoalStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            syncState = SyncState.PENDING_CREATE,
        )
        dao.upsert(entity)
        syncScheduler.syncSoon()
    }

    suspend fun updateGoal(id: String, title: String, notes: String?, targetDate: String?, status: GoalStatus) {
        val existing = dao.getById(id) ?: return
        val now = java.time.Instant.now().toString()
        val updated = existing.copy(
            title = title,
            notes = notes,
            targetDate = targetDate,
            status = status,
            updatedAt = now,
            syncState = if (existing.syncState == SyncState.PENDING_CREATE) SyncState.PENDING_CREATE else SyncState.PENDING_UPDATE,
        )
        dao.update(updated)
        syncScheduler.syncSoon()
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        val existing = dao.getById(id) ?: return
        updateGoal(
            id = id,
            title = existing.title,
            notes = existing.notes,
            targetDate = existing.targetDate,
            status = if (archived) GoalStatus.ARCHIVED else GoalStatus.ACTIVE,
        )
    }

    suspend fun deleteGoal(id: String) {
        val existing = dao.getById(id) ?: return
        if (existing.syncState == SyncState.PENDING_CREATE) {
            // Never made it to the server — nothing to tell it about.
            dao.delete(existing)
            return
        }
        dao.update(existing.copy(syncState = SyncState.PENDING_DELETE))
        syncScheduler.syncSoon()
    }

    /** Pushes every pending local change, then pulls — mirrors TodoRepository.syncNow exactly; see that function's doc comment. */
    suspend fun syncNow(): Result<Unit> = runCatching {
        val pendingDeletes = dao.getBySyncState(SyncState.PENDING_DELETE)
        val pendingCreates = dao.getBySyncState(SyncState.PENDING_CREATE)
        val pendingUpdates = dao.getBySyncState(SyncState.PENDING_UPDATE)
        Log.d(TAG, "syncNow: starting — ${pendingDeletes.size} delete(s), ${pendingCreates.size} create(s), ${pendingUpdates.size} update(s) pending")
        pendingDeletes.forEach { syncDelete(it) }
        pendingCreates.forEach { syncCreate(it) }
        pendingUpdates.forEach { syncUpdate(it) }
        refresh()
        Log.d(TAG, "syncNow: finished")
        Unit
    }.onFailure { e -> Log.e(TAG, "syncNow: failed", e) }

    private suspend fun syncDelete(entity: GoalEntity) {
        if (isLocalOnlyId(entity.id)) {
            dao.delete(entity)
            return
        }
        val response = api.deleteGoal(entity.id)
        if (response.isSuccessful || response.code() == 404) {
            dao.delete(entity)
            Log.d(TAG, "syncDelete: ${entity.id} deleted (server response ${response.code()})")
        } else {
            Log.w(TAG, "syncDelete: ${entity.id} failed with ${response.code()}, leaving PENDING_DELETE for next sync")
        }
    }

    private suspend fun syncCreate(entity: GoalEntity) {
        val response = api.createGoal(entity.toCreateRequest())
        val body = response.body()
        if (response.isSuccessful && body != null) {
            dao.replaceId(entity.id, body.toEntity(SyncState.SYNCED))
            Log.d(TAG, "syncCreate: ${entity.id} -> ${body.id}")
        } else {
            Log.w(TAG, "syncCreate: ${entity.id} failed with ${response.code()}, leaving PENDING_CREATE for next sync")
        }
    }

    private suspend fun syncUpdate(entity: GoalEntity) {
        val response = api.updateGoal(entity.id, entity.toUpdateRequest())
        val body: GoalDto? = response.body()
        if (response.isSuccessful && body != null) {
            dao.update(body.toEntity(SyncState.SYNCED))
            Log.d(TAG, "syncUpdate: ${entity.id} synced")
        } else if (response.code() == 404) {
            dao.delete(entity)
            Log.d(TAG, "syncUpdate: ${entity.id} was deleted server-side, dropping local copy")
        } else {
            Log.w(TAG, "syncUpdate: ${entity.id} failed with ${response.code()}, leaving PENDING_UPDATE for next sync")
        }
    }

    private companion object {
        const val TAG = "TodoAi.GoalRepository"
    }
}
