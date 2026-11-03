package cc.gilmore.todoai

import android.app.Application
import android.util.Log
import cc.gilmore.todoai.push.NotificationHelper
import cc.gilmore.todoai.voice.VoiceTtsHolder

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

    /**
     * The result of the post-splash "use voice this launch?" prompt (see
     * ui/voiceprompt/VoiceLaunchPromptDialog.kt) — null until that prompt
     * has resolved (a tap, or its 5-second countdown lapsing), then fixed
     * for the rest of this process's life. Application-scoped for the
     * same reason [hasAutoListenedThisLaunch] is (see its doc comment
     * just above): a plain remembered Compose state wouldn't survive the
     * Activity being destroyed and recreated by the same still-alive
     * process (e.g. a rotation), and this prompt should only ever ask
     * once per real launch, not once per Activity instance.
     *
     * Deliberately NOT written back to VoiceLaunchPreferenceStore — this
     * is a one-time-per-launch override of whether PodScreen's cold-start
     * check fires, never a change to the persisted Settings switch; see
     * VoiceLaunchPromptDialog's own doc comment and PodScreen's auto-listen
     * effect.
     */
    var voiceLaunchPromptDecision: Boolean? = null

    /**
     * Created in [onCreate], before anything else that doesn't strictly
     * need to come first — see [VoiceTtsHolder]'s doc comment for why the
     * engine needs the earliest possible head start on initializing.
     * Nullable (unlike [container]): construction failing here should
     * degrade the voice-add feature's spoken prompt, not take the whole
     * app down the way a failed [AppContainer] genuinely should.
     */
    var voiceTts: VoiceTtsHolder? = null
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
            Log.d(TAG, "onCreate: creating VoiceTtsHolder…")
            voiceTts = VoiceTtsHolder(this)
            Log.d(TAG, "onCreate: VoiceTtsHolder created (engine init is async from here)")
        } catch (e: Throwable) {
            // Deliberately not rethrown, and deliberately not retried —
            // voiceTts just stays null, which every caller (see
            // VoiceInputButton.kt) already treats the same as "engine
            // never became ready": skip the spoken prompt, open the
            // recognizer anyway.
            Log.e(TAG, "onCreate: VoiceTtsHolder construction failed — voice-add will skip the spoken prompt", e)
        }

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
