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
        val result = app.container.todoRepository.syncNow()
        return if (result.isSuccess) {
            Log.d(TAG, "doWork: success")
            Result.success()
        } else {
            Log.w(TAG, "doWork: sync failed, will retry", result.exceptionOrNull())
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "TodoAi.SyncWorker"
    }
}
