package cc.gilmore.todoai.repository

import android.util.Log
import cc.gilmore.todoai.data.local.LOCAL_ID_PREFIX
import cc.gilmore.todoai.data.local.RoutineDao
import cc.gilmore.todoai.data.local.RoutineEntity
import cc.gilmore.todoai.data.local.SyncState
import cc.gilmore.todoai.data.local.isLocalOnlyId
import cc.gilmore.todoai.data.remote.RoutineApiService
import cc.gilmore.todoai.data.remote.dto.RoutineDto
import cc.gilmore.todoai.model.RoutinePeriod
import cc.gilmore.todoai.model.RoutineStatus
import cc.gilmore.todoai.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Single source of truth for routines — same offline-first design as
 * TodoRepository/GoalRepository (see TodoRepository's doc comment).
 * [syncNow] is called from the same SyncWorker.doWork() run as the other
 * two repositories' syncNow(), not a separate schedule — see SyncWorker.kt.
 *
 * Deliberately has no "progress" or "doneCount" concept of its own — that
 * is always derived fresh from TodoRepository's data (every todo whose
 * routineId matches, filtered to status DONE and completedAt within the
 * current period), computed where it's displayed (ui/goals' RoutineRow)
 * rather than cached here, mirroring exactly how
 * aws-backend/lambdas/ai-orchestrator/index.ts's routineProgress works
 * server-side — one formula, not two copies that could drift.
 */
class RoutineRepository(
    private val dao: RoutineDao,
    private val api: RoutineApiService,
    private val syncScheduler: SyncScheduler,
) {
    fun observeRoutines(): Flow<List<RoutineEntity>> = dao.observeAll()

    suspend fun getRoutine(id: String): RoutineEntity? = dao.getById(id)

    /** Best-effort pull from the server. Swallows network failures — offline is an expected, normal state, not an error to surface. */
    suspend fun refresh() {
        runCatching {
            val response = api.listRoutines()
            if (response.isSuccessful) {
                val items = response.body()?.items.orEmpty()
                dao.replaceAllSynced(items.map { it.toEntity() })
                Log.d(TAG, "refresh: pulled ${items.size} routines")
            } else {
                Log.w(TAG, "refresh: non-2xx response ${response.code()}")
            }
        }.onFailure { e -> Log.w(TAG, "refresh: failed, leaving local data as-is", e) }
    }

    suspend fun createRoutine(title: String, notes: String?, targetCount: Int, period: RoutinePeriod) {
        val now = java.time.Instant.now().toString()
        val entity = RoutineEntity(
            id = LOCAL_ID_PREFIX + UUID.randomUUID(),
            title = title,
            notes = notes,
            targetCount = targetCount,
            period = period,
            status = RoutineStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
            syncState = SyncState.PENDING_CREATE,
        )
        dao.upsert(entity)
        syncScheduler.syncSoon()
    }

    suspend fun updateRoutine(id: String, title: String, notes: String?, targetCount: Int, period: RoutinePeriod, status: RoutineStatus) {
        val existing = dao.getById(id) ?: return
        val now = java.time.Instant.now().toString()
        val updated = existing.copy(
            title = title,
            notes = notes,
            targetCount = targetCount,
            period = period,
            status = status,
            updatedAt = now,
            syncState = if (existing.syncState == SyncState.PENDING_CREATE) SyncState.PENDING_CREATE else SyncState.PENDING_UPDATE,
        )
        dao.update(updated)
        syncScheduler.syncSoon()
    }

    suspend fun setArchived(id: String, archived: Boolean) {
        val existing = dao.getById(id) ?: return
        updateRoutine(
            id = id,
            title = existing.title,
            notes = existing.notes,
            targetCount = existing.targetCount,
            period = existing.period,
            status = if (archived) RoutineStatus.ARCHIVED else RoutineStatus.ACTIVE,
        )
    }

    suspend fun deleteRoutine(id: String) {
        val existing = dao.getById(id) ?: return
        if (existing.syncState == SyncState.PENDING_CREATE) {
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

    private suspend fun syncDelete(entity: RoutineEntity) {
        if (isLocalOnlyId(entity.id)) {
            dao.delete(entity)
            return
        }
        val response = api.deleteRoutine(entity.id)
        if (response.isSuccessful || response.code() == 404) {
            dao.delete(entity)
            Log.d(TAG, "syncDelete: ${entity.id} deleted (server response ${response.code()})")
        } else {
            Log.w(TAG, "syncDelete: ${entity.id} failed with ${response.code()}, leaving PENDING_DELETE for next sync")
        }
    }

    private suspend fun syncCreate(entity: RoutineEntity) {
        val response = api.createRoutine(entity.toCreateRequest())
        val body = response.body()
        if (response.isSuccessful && body != null) {
            dao.replaceId(entity.id, body.toEntity(SyncState.SYNCED))
            Log.d(TAG, "syncCreate: ${entity.id} -> ${body.id}")
        } else {
            Log.w(TAG, "syncCreate: ${entity.id} failed with ${response.code()}, leaving PENDING_CREATE for next sync")
        }
    }

    private suspend fun syncUpdate(entity: RoutineEntity) {
        val response = api.updateRoutine(entity.id, entity.toUpdateRequest())
        val body: RoutineDto? = response.body()
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
        const val TAG = "TodoAi.RoutineRepository"
    }
}
