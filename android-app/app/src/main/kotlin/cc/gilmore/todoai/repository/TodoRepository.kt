package cc.gilmore.todoai.repository

import android.util.Log
import cc.gilmore.todoai.data.local.LOCAL_ID_PREFIX
import cc.gilmore.todoai.data.local.SyncState
import cc.gilmore.todoai.data.local.TodoDao
import cc.gilmore.todoai.data.local.TodoEntity
import cc.gilmore.todoai.data.local.isLocalOnlyId
import cc.gilmore.todoai.data.remote.TodoApiService
import cc.gilmore.todoai.data.remote.dto.RegisterDeviceTokenRequest
import cc.gilmore.todoai.data.remote.dto.TodoDto
import cc.gilmore.todoai.model.Priority
import cc.gilmore.todoai.model.TodoStatus
import cc.gilmore.todoai.sync.SyncScheduler
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Single source of truth for todos: the UI only ever reads from Room
 * (dao.observeAll), and every mutation writes to Room first, immediately,
 * then either pushes to the API right away (if online) or leaves the row
 * marked pending for SyncWorker to pick up later — see "Offline sync
 * model" in android-app/README.md for the reasoning behind using a
 * per-row [SyncState] instead of a generic operation log, and why that
 * makes create-then-edit-while-still-offline and
 * create-then-delete-while-still-offline collapse to simple cases instead
 * of needing operation-log compaction.
 */
class TodoRepository(
    private val dao: TodoDao,
    private val api: TodoApiService,
    private val syncScheduler: SyncScheduler,
) {
    fun observeTodos(): Flow<List<TodoEntity>> = dao.observeAll()

    suspend fun getTodo(id: String): TodoEntity? = dao.getById(id)

    /** Snapshot of every non-pending-delete local todo — used by the report feature to filter by completedAt over a date range. */
    suspend fun getAllTodosOnce(): List<TodoEntity> = dao.getAllOnce()

    /** Best-effort pull from the server. Swallows network failures — offline is an expected, normal state, not an error to surface. */
    suspend fun refresh() {
        runCatching {
            val response = api.listTodos()
            if (response.isSuccessful) {
                val items = response.body()?.items.orEmpty()
                dao.replaceAllSynced(items.map { it.toEntity() })
                Log.d(TAG, "refresh: pulled ${items.size} todos")
            } else {
                Log.w(TAG, "refresh: non-2xx response ${response.code()}")
            }
        }.onFailure { e ->
            // Swallowed on purpose (offline is normal), but logged so a
            // *surprising* failure — a real bug, not just "no network" —
            // doesn't disappear silently.
            Log.w(TAG, "refresh: failed, leaving local data as-is", e)
        }
    }

    suspend fun createTodo(
        title: String,
        notes: String?,
        priority: Priority,
        dueDate: String?,
        reminderAt: String?,
        recurrenceRule: String?,
        project: String?,
        tags: List<String>,
    ) {
        val now = java.time.Instant.now().toString()
        val entity = TodoEntity(
            id = LOCAL_ID_PREFIX + UUID.randomUUID(),
            title = title,
            notes = notes,
            status = TodoStatus.OPEN,
            priority = priority,
            dueDate = dueDate,
            reminderAt = reminderAt,
            recurrenceRule = recurrenceRule,
            project = project,
            tags = tags,
            source = "app",
            createdAt = now,
            updatedAt = now,
            // A brand-new local todo is always open — see TodoEntity's doc
            // comment on completedAt.
            completedAt = null,
            syncState = SyncState.PENDING_CREATE,
        )
        dao.upsert(entity)
        syncScheduler.syncSoon()
    }

    suspend fun updateTodo(
        id: String,
        title: String,
        notes: String?,
        status: TodoStatus,
        priority: Priority,
        dueDate: String?,
        reminderAt: String?,
        recurrenceRule: String?,
        project: String?,
        tags: List<String>,
    ) {
        val existing = dao.getById(id) ?: return
        val now = java.time.Instant.now().toString()
        // Mirrors aws-backend/lambdas/todo-api/index.ts's updateTodo: stamp
        // completedAt the moment status transitions to DONE, clear it the
        // moment it transitions away, leave it alone otherwise. Done
        // optimistically here (not just left for sync to fill in) so the
        // "Completed <date>" row and the report feature both see it right
        // away, even before this edit has synced.
        val completingNow = existing.status != TodoStatus.DONE && status == TodoStatus.DONE
        val reopening = existing.status == TodoStatus.DONE && status != TodoStatus.DONE
        val completedAt = if (completingNow) now else if (reopening) null else existing.completedAt
        val updated = existing.copy(
            title = title,
            notes = notes,
            status = status,
            priority = priority,
            dueDate = dueDate,
            reminderAt = reminderAt,
            recurrenceRule = recurrenceRule,
            project = project,
            tags = tags,
            updatedAt = now,
            completedAt = completedAt,
            // Still-unsynced creation: stays PENDING_CREATE, no PATCH is
            // ever needed — the eventual POST just carries the latest data.
            syncState = if (existing.syncState == SyncState.PENDING_CREATE) SyncState.PENDING_CREATE else SyncState.PENDING_UPDATE,
        )
        dao.update(updated)
        syncScheduler.syncSoon()
    }

    suspend fun completeTodo(id: String) {
        val existing = dao.getById(id) ?: return
        updateTodo(
            id = id,
            title = existing.title,
            notes = existing.notes,
            status = TodoStatus.DONE,
            priority = existing.priority,
            dueDate = existing.dueDate,
            reminderAt = existing.reminderAt,
            recurrenceRule = existing.recurrenceRule,
            project = existing.project,
            tags = existing.tags,
        )
    }

    suspend fun deleteTodo(id: String) {
        val existing = dao.getById(id) ?: return
        if (existing.syncState == SyncState.PENDING_CREATE) {
            // Never made it to the server — nothing to tell it about.
            dao.delete(existing)
            return
        }
        dao.update(existing.copy(syncState = SyncState.PENDING_DELETE))
        syncScheduler.syncSoon()
    }

    /**
     * Folds a [TodoDto] the server handed back outside the normal
     * refresh/sync flow — today, only DialogViewModel's `createdTodos`
     * (see AiDto.kt's ChatSendResultDto doc comment) — straight into Room
     * as already-SYNCED, so a todo the AI just created shows up in
     * TodoListScreen/PodScreen immediately rather than waiting for the
     * next [refresh]. `dao.upsert` (not `insert`) so this is also safe to
     * call for a todo id that's somehow already present locally.
     */
    suspend fun upsertFromServer(dto: TodoDto) {
        dao.upsert(dto.toEntity(SyncState.SYNCED))
    }

    suspend fun registerDeviceTokenIfNeeded(fcmToken: String) {
        // Best-effort and silent by design (see the doc comment on
        // TodoApiService.registerDeviceToken for the server-side history
        // here), but logged either way so you can tell "server rejected
        // it" from "never even got called" from Logcat alone.
        runCatching { api.registerDeviceToken(RegisterDeviceTokenRequest(fcmToken)) }
            .onSuccess { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "registerDeviceTokenIfNeeded: registered")
                } else {
                    Log.w(TAG, "registerDeviceTokenIfNeeded: non-2xx response ${response.code()}")
                }
            }
            .onFailure { e -> Log.w(TAG, "registerDeviceTokenIfNeeded: failed", e) }
    }

    /**
     * Fetches the current FCM token and registers it — a second call site
     * for [registerDeviceTokenIfNeeded] beyond [cc.gilmore.todoai.push.TodoFcmService.onNewToken].
     * That callback only fires when FCM actually generates or rotates a
     * token, which normally happens once, at first app install — and
     * Firebase Messaging auto-initializes independently of Cognito auth,
     * so that first (and often only) call typically lands *before* the
     * user has signed in. `POST /device/token` requires a signed-in
     * Cognito JWT (see `infra/lib/api-stack.ts`'s authorizer on that
     * route), so a pre-login attempt 401s — and since the token itself
     * doesn't change again after that, `onNewToken` would never fire a
     * second time to retry it. Without this, a real install could go
     * through onboarding/sign-in and never end up registered at all.
     *
     * NavGraph.kt calls this once per sign-in (fresh login or a restored
     * session), not on every screen — cheap and safe to call repeatedly
     * either way, since the server treats "same token, same user" as a
     * no-op touch rather than re-creating anything (see
     * lambdas/device-token/index.ts's `existing?.fcmToken === fcmToken`
     * check).
     */
    suspend fun refreshDeviceTokenRegistration() {
        val token = fetchFcmToken() ?: return
        registerDeviceTokenIfNeeded(token)
    }

    /**
     * Bridges Firebase's Task-based `FirebaseMessaging.getInstance().token`
     * to a suspend call via [suspendCancellableCoroutine] — the same
     * technique `kotlinx-coroutines-play-services`' own `Task.await()`
     * extension uses internally, used directly here instead of adding that
     * dependency for this one call site. Returns null (logged, not thrown)
     * on failure, matching [registerDeviceTokenIfNeeded]'s own "never take
     * the caller down over this" stance — verified against Firebase's
     * `Task<TResult>` API (addOnSuccessListener/addOnFailureListener, each
     * called at most once) before writing this.
     */
    private suspend fun fetchFcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> if (cont.isActive) cont.resume(token) }
            .addOnFailureListener { e ->
                Log.w(TAG, "fetchFcmToken: failed", e)
                if (cont.isActive) cont.resume(null)
            }
    }

    /**
     * Pushes every pending local change, then pulls. Called by SyncWorker
     * on its schedule and opportunistically right after a mutation when a
     * network is already known to be up (see SyncScheduler).
     */
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
        // Explicit Unit: Log.d's return type (Int) would otherwise leak out
        // as this runCatching block's inferred type — since it's the last
        // expression above — making this Result<Int> instead of the
        // declared Result<Unit>. (This is exactly the compile error this
        // logging change introduced; leaving the note so the same mistake
        // doesn't get reintroduced by a future log line added at the end.)
        Unit
    }.onFailure { e -> Log.e(TAG, "syncNow: failed", e) }

    private suspend fun syncDelete(entity: TodoEntity) {
        if (isLocalOnlyId(entity.id)) {
            // Was PENDING_CREATE, got deleted before it ever synced, but
            // syncNow raced with that delete — nothing to do server-side.
            dao.delete(entity)
            return
        }
        val response = api.deleteTodo(entity.id)
        if (response.isSuccessful || response.code() == 404) {
            // 404 means it's already gone server-side (e.g. deleted from
            // another client) — that's the outcome we wanted anyway.
            dao.delete(entity)
            Log.d(TAG, "syncDelete: ${entity.id} deleted (server response ${response.code()})")
        } else {
            Log.w(TAG, "syncDelete: ${entity.id} failed with ${response.code()}, leaving PENDING_DELETE for next sync")
        }
    }

    private suspend fun syncCreate(entity: TodoEntity) {
        val response = api.createTodo(entity.toCreateRequest())
        val body = response.body()
        if (response.isSuccessful && body != null) {
            dao.replaceId(entity.id, body.toEntity(SyncState.SYNCED))
            Log.d(TAG, "syncCreate: ${entity.id} -> ${body.id}")
        } else {
            Log.w(TAG, "syncCreate: ${entity.id} failed with ${response.code()}, leaving PENDING_CREATE for next sync")
        }
    }

    private suspend fun syncUpdate(entity: TodoEntity) {
        val response = api.updateTodo(entity.id, entity.toUpdateRequest())
        val body = response.body()
        if (response.isSuccessful && body != null) {
            dao.update(body.asTodoDto().toEntity(SyncState.SYNCED))
            body.nextOccurrence?.let { dao.upsert(it.toEntity(SyncState.SYNCED)) }
            Log.d(TAG, "syncUpdate: ${entity.id} synced" + if (body.nextOccurrence != null) " (recurred)" else "")
        } else if (response.code() == 404) {
            // Deleted server-side (another client) while we had a pending
            // edit — the edit has nowhere to go; drop our local copy too.
            dao.delete(entity)
            Log.d(TAG, "syncUpdate: ${entity.id} was deleted server-side, dropping local copy")
        } else {
            Log.w(TAG, "syncUpdate: ${entity.id} failed with ${response.code()}, leaving PENDING_UPDATE for next sync")
        }
    }

    private companion object {
        const val TAG = "TodoAi.TodoRepository"
    }
}
