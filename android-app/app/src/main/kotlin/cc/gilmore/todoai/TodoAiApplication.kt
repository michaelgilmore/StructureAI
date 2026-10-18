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

    /**
     * Consumed at most once per process lifetime, by PodScreen's
     * cold-start auto-listen check (see its doc comment). A plain
     * Application-scoped flag is what actually captures "cold start" —
     * unlike a LaunchedEffect(Unit) alone, which would also re-run every
     * time PodScreen re-enters composition (navigating back to the Today
     * tab from elsewhere), and unlike a ViewModel-held flag, which
     * survives configuration changes but NOT "the Activity was destroyed
     * and recreated by the same still-alive process" the way this needs
     * to for "Ok Google, launch Structure" to correctly fire once per
     * real launch, not once per PodViewModel instance.
     */
    var hasAutoListenedThisLaunch = false

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
