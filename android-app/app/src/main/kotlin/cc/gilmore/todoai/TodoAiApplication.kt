package cc.gilmore.todoai

import android.app.Application
import android.util.Log
import cc.gilmore.todoai.push.NotificationHelper

/**
 * Logging here is deliberately heavy — this is the process's single entry
 * point, so anything that throws before [container] is assigned takes the
 * whole app down before a single screen is drawn ("crashes on launch," no
 * stack trace visible to the user). The milestone/error logs below and the
 * uncaught-exception handler are meant to make that failure legible in
 * Logcat rather than guessed at. See android-app/README.md, "Debugging a
 * crash on launch" for how to read them.
 */
class TodoAiApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        // Installed before anything else in this method runs, so it catches
        // a throw from AppContainer's construction below too, not just
        // exceptions on other threads later on. Chains to Android's own
        // default handler afterward so the normal crash dialog / process
        // death still happens — this only adds a clear log line first.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL: uncaught exception on thread '${thread.name}'", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

        Log.d(TAG, "onCreate: starting")
        super.onCreate()

        try {
            Log.d(TAG, "onCreate: building AppContainer…")
            container = AppContainer(this)
            Log.d(TAG, "onCreate: AppContainer ready")
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate: AppContainer construction failed — see the cause below for which dependency", e)
            throw e
        }

        try {
            Log.d(TAG, "onCreate: ensuring notification channel…")
            NotificationHelper.ensureChannel(this)
            Log.d(TAG, "onCreate: notification channel ready")
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate: NotificationHelper.ensureChannel failed", e)
            throw e
        }

        try {
            Log.d(TAG, "onCreate: scheduling periodic sync…")
            container.syncScheduler.schedulePeriodic()
            Log.d(TAG, "onCreate: periodic sync scheduled")
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate: schedulePeriodic failed", e)
            throw e
        }

        Log.d(TAG, "onCreate: finished")
    }

    private companion object {
        const val TAG = "TodoAi.Application"
    }
}
