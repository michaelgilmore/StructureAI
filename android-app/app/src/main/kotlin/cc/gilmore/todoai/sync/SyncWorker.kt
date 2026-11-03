package cc.gilmore.todoai.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cc.gilmore.todoai.TodoAiApplication

/**
 * Thin WorkManager wrapper around TodoRepository.syncNow(). No DI
 * framework in this app (see AppContainer.kt), so this reaches the shared
 * repository instance off the Application — the standard way to get
 * app-scoped dependencies into a Worker without Hilt's WorkerFactory
 * integration.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: starting (attempt ${runAttemptCount + 1})")
        val app = applicationContext as TodoAiApplication
        val todoResult = app.container.todoRepository.syncNow()
        // Goals share this worker/schedule rather than getting their own —
        // see AppContainer.kt's doc comment on goalRepository. Both run
        // every time regardless of whether the other failed, so one
        // failing never blocks the other's pending changes from syncing.
        val goalResult = app.container.goalRepository.syncNow()
        // Routines share this worker/schedule too, for the same reason —
        // see AppContainer.kt's doc comment on routineRepository.
        val routineResult = app.container.routineRepository.syncNow()
        return if (todoResult.isSuccess && goalResult.isSuccess && routineResult.isSuccess) {
            Log.d(TAG, "doWork: success")
            Result.success()
        } else {
            Log.w(
                TAG,
                "doWork: sync failed, will retry",
                todoResult.exceptionOrNull() ?: goalResult.exceptionOrNull() ?: routineResult.exceptionOrNull(),
            )
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "TodoAi.SyncWorker"
    }
}
