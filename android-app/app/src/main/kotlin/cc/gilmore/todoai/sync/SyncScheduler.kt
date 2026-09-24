package cc.gilmore.todoai.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Two WorkManager schedules, both constrained to `NetworkType.CONNECTED`
 * (so WorkManager itself defers and re-triggers them as connectivity
 * changes — see android-app/README.md, "Offline sync model"):
 *
 * - [syncSoon] — a coalesced one-off request, enqueued after every local
 *   mutation, so pending changes reach the server promptly rather than
 *   waiting for the next periodic tick. `REPLACE` policy means rapid edits
 *   (e.g. typing in the detail screen) collapse into one run instead of
 *   queueing a run per keystroke.
 * - [schedulePeriodic] — a 30-minute safety net that also does the pull
 *   half of sync (see TodoRepository.syncNow), so a second device's
 *   changes eventually show up here too, not just outgoing changes.
 */
class SyncScheduler(context: Context) {
    // WorkManager.getInstance throws IllegalStateException if WorkManager
    // hasn't initialized itself yet (e.g. the manifest's default
    // WorkManagerInitializer content provider was disabled without a
    // replacement Configuration.Provider) — worth its own try/catch since
    // that's a real, if uncommon, launch-time crash mode distinct from
    // anything AppContainer's own `step` wrapper would already log.
    private val workManager = try {
        WorkManager.getInstance(context.applicationContext)
    } catch (e: Throwable) {
        Log.e(TAG, "WorkManager.getInstance failed — is the default WorkManagerInitializer disabled without a replacement?", e)
        throw e
    }

    fun syncSoon() {
        Log.d(TAG, "syncSoon: enqueueing")
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(SYNC_SOON_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun schedulePeriodic() {
        Log.d(TAG, "schedulePeriodic: enqueueing")
        val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        private const val TAG = "TodoAi.SyncScheduler"
        private const val SYNC_SOON_WORK_NAME = "todo-sync-soon"
        private const val PERIODIC_SYNC_WORK_NAME = "todo-sync-periodic"
    }
}
