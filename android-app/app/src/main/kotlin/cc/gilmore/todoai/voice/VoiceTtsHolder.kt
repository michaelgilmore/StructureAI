package cc.gilmore.todoai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the single [TextToSpeech] engine used for the "What would you like
 * to add?" voice-add prompt (see ui/components/VoiceInputButton.kt),
 * created once in `TodoAiApplication.onCreate()` rather than per-screen.
 *
 * Why Application-scoped instead of a per-composable, `remember`-owned
 * engine (the first version of this feature): [TextToSpeech] initializes
 * asynchronously — binding to the system TTS service takes real wall-clock
 * time, its `onInit` callback can never fire synchronously within the
 * same frame as the constructor call — and the hands-free
 * auto-listen-on-launch path (PodScreen) fires during the FIRST
 * composition of the app's very first screen, nowhere near enough time
 * for a freshly-constructed engine to be ready. Creating it here, in
 * `Application.onCreate()`, gives it the maximum possible head start —
 * it starts initializing before a single Activity or Compose frame even
 * exists — and [awaitReady] lets a caller that still loses that race wait
 * a bounded extra amount of time rather than silently skipping the prompt
 * forever.
 *
 * (The first attempt at this fix tied `rememberVoiceInputLauncher` to a
 * `TextToSpeech?` value captured once by a `LaunchedEffect(Unit)` — which,
 * because that effect's key never changes, kept using whatever `tts`
 * value was current at the moment the effect first launched for the rest
 * of the process's life, i.e. permanently null, even after wrapping it in
 * `rememberUpdatedState` — that only fixes staleness if the *next*
 * recomposition happens to land after the engine becomes ready, which
 * isn't guaranteed either. Routing every lookup through [awaitReady] —
 * called fresh, inside the triggered coroutine, every single time voice
 * input is launched, with an actual wait instead of a recomposition gamble
 * — removes the race instead of chasing it.)
 */
class VoiceTtsHolder(context: Context) {
    private val ready = CompletableDeferred<Boolean>()

    @Volatile
    private var engine: TextToSpeech? = null

    init {
        val created = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.d(TAG, "TextToSpeech ready")
                ready.complete(true)
            } else {
                Log.w(TAG, "TextToSpeech init failed (status=$status) — voice prompt will be skipped")
                engine?.shutdown()
                engine = null
                ready.complete(false)
            }
        }
        engine = created
    }

    /**
     * Suspends until the engine finishes initializing (or fails to), up
     * to [timeoutMs], then returns the ready engine, or null if it's not
     * ready by then (or failed outright). If initialization already
     * finished before this is called — true for anything but a genuine
     * cold-start auto-listen, which is usually racing it — awaiting an
     * already-completed [CompletableDeferred] returns immediately, so
     * this is cheap to call unconditionally everywhere voice input can be
     * triggered, not just the auto-listen path.
     */
    suspend fun awaitReady(timeoutMs: Long = 2_500L): TextToSpeech? {
        withTimeoutOrNull(timeoutMs) { ready.await() }
        return engine
    }

    private companion object {
        const val TAG = "TodoAi.VoiceTts"
    }
}
