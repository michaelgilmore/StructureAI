package cc.gilmore.todoai.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cc.gilmore.todoai.TodoAiApplication
import cc.gilmore.todoai.repository.AiRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.voice.VoiceCommandRouter
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "TodoAi.VoiceInput"
private const val PROMPT_UTTERANCE_ID = "todoai_voice_prompt"
private const val INITIAL_PROMPT = "What would you like to do?"
private const val FOLLOWUP_PROMPT = "What else?"

/**
 * Safety bound on how many turns one voice-first session can take, not a
 * real user-facing limit — see [rememberVoiceCommandLauncher]'s doc
 * comment.
 */
private const val MAX_LOOP_TURNS = 20

/**
 * A single mutable slot, `remember`-held across recompositions, that lets
 * [rememberRecognizerCall]'s `ActivityResult` callback find whichever
 * coroutine is currently awaiting a result. Not Compose [androidx.compose.runtime.State] —
 * nothing here should ever trigger a recomposition, this is pure
 * coroutine bookkeeping, the same role `TodoRepository.fetchFcmToken`'s
 * local `cont` variable plays for a one-shot callback.
 */
private class PendingResult<T> {
    var continuation: CancellableContinuation<T>? = null
}

/**
 * Wraps one `RecognizerIntent` round trip as a *suspend* call instead of a
 * one-shot callback — the same `suspendCancellableCoroutine` bridge
 * `TodoRepository.fetchFcmToken` already uses for a callback-based Android
 * API, applied here to `ActivityResultLauncher` instead of a Firebase
 * `Task`. Only one call is ever in flight at a time (the voice-first loop
 * in [rememberVoiceCommandLauncher] always awaits one before starting the
 * next), so the single [PendingResult] slot is enough — no queue needed.
 *
 * Returns the top transcript, or null for anything that isn't a usable
 * result: `RESULT_CANCELED` (backed out of the mic dialog), an
 * empty/missing transcript, or [ActivityNotFoundException] (no
 * speech-recognizer app on the device — [onUnavailable] fires in that
 * last case specifically, distinct from "you said nothing").
 *
 * `ActivityResultLauncher.launch` calls straight through to
 * `Activity.startActivityForResult` under the hood, so it throws
 * [ActivityNotFoundException] the same way a plain `startActivity()`
 * would when no app can fulfill the intent — caught below rather than
 * checked for in advance with `PackageManager.resolveActivity`, because
 * Android 11+'s package-visibility filtering can make that kind of
 * pre-check return null even when a recognizer IS installed (see
 * Android's package-visibility use cases doc: "the most direct approach
 * ... is to call the intent and handle ActivityNotFoundException"). No
 * `<queries>` manifest entry is needed for the recognizer itself — that
 * restriction governs *introspecting* other apps
 * (`resolveActivity`/`queryIntentActivities`), not launching one via
 * `startActivity()`. (The spoken prompt's [TextToSpeech] engine lookup is
 * a different case and does need one — see AndroidManifest.xml's
 * `<queries>` block.)
 */
@Composable
private fun rememberRecognizerCall(onUnavailable: () -> Unit): suspend () -> String? {
    val pending = remember { PendingResult<String?>() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val transcript = if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        pending.continuation?.let { cont ->
            pending.continuation = null
            if (cont.isActive) cont.resume(transcript)
        }
    }
    return {
        suspendCancellableCoroutine { cont ->
            pending.continuation = cont
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, INITIAL_PROMPT)
            }
            try {
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                pending.continuation = null
                onUnavailable()
                cont.resume(null)
            }
        }
    }
}

/**
 * Speaks [text] out loud via [tts] and suspends until speech finishes (or
 * errors) — same `suspendCancellableCoroutine` bridge as
 * [rememberRecognizerCall], this time over [UtteranceProgressListener]'s
 * callbacks. Those callbacks fire on a background thread owned by the TTS
 * service, not the main thread, but that's not a problem here the way it
 * was in an earlier version of this feature (which called
 * `ActivityResultLauncher.launch` directly from inside the listener, and
 * needed a `Handler.post` back to main to do it safely): resuming a
 * suspended coroutine's continuation always hands control back through
 * that coroutine's own dispatcher, so the code *after* this `speak` call
 * resumes on whichever thread the surrounding coroutine was already
 * running on (Main, for everything in this file) — no manual thread
 * hopping needed. Does nothing (returns immediately) if [tts] is null,
 * the same "engine never became ready, skip the prompt" degrade every
 * other voice-input call site in this app already uses.
 */
private suspend fun speak(tts: TextToSpeech?, text: String) {
    if (tts == null) return
    suspendCancellableCoroutine<Unit> { cont ->
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java, but still the one abstract overload UtteranceProgressListener requires")
            override fun onError(utteranceId: String?) {
                if (cont.isActive) cont.resume(Unit)
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, PROMPT_UTTERANCE_ID)
    }
}

/**
 * Builds and returns a zero-arg trigger that runs the full voice-first
 * loop described in android-app/README.md's "Voice-first" section: speak
 * [INITIAL_PROMPT] ("What would you like to do?" — not just "add a
 * todo"), listen, hand the transcript to a fresh [VoiceCommandRouter],
 * speak back its reply, and — unless the router says to stop (a stop
 * phrase, or "start planning," which hands off to [onStartPlanning]
 * instead) — speak [FOLLOWUP_PROMPT] ("What else?") and listen again.
 * Repeats until the router says to stop, the recognizer returns nothing
 * usable (cancelled, no match, no recognizer app on this device), or
 * [MAX_LOOP_TURNS] is hit. That cap is a safety net against a genuine bug
 * turning this into a silent infinite loop — e.g. the AI backend somehow
 * always replying with something the router reads as "keep going" — not
 * a real limit anyone doing voice-first todo management for real should
 * ever reach.
 *
 * The TTS engine is looked up once per session (not once per turn), via
 * `application.voiceTts?.awaitReady()` — see `voice/VoiceTtsHolder.kt`'s
 * doc comment for why that's a suspend wait rather than a value captured
 * ahead of time. [onSpoken] is called with every line this function
 * would otherwise only speak, so the calling screen can also show it as
 * a Snackbar — useful with the volume down, or just as an on-screen echo
 * of what's happening during a hands-free session.
 *
 * Used identically by every voice-input trigger in the app now — both
 * screens' mic FABs (via [VoiceInputButton] below) and PodScreen's
 * cold-start auto-listen effect — so tapping the mic or launching the app
 * hands-free behave the same way: a general "what would you like to do?"
 * entry point, not a single-shot "add a todo" the way this used to work
 * (see this file's git history / android-app/README.md's earlier
 * "Add a todo by voice" section for that original, narrower version).
 */
@Composable
fun rememberVoiceCommandLauncher(
    todoRepository: TodoRepository,
    aiRepository: AiRepository,
    onStartPlanning: () -> Unit,
    onSpoken: (String) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val application = LocalContext.current.applicationContext as TodoAiApplication
    val listenOnce = rememberRecognizerCall(onUnavailable)
    val scope = rememberCoroutineScope()

    suspend fun say(tts: TextToSpeech?, text: String) {
        onSpoken(text)
        speak(tts, text)
    }

    return {
        scope.launch {
            val tts = application.voiceTts?.awaitReady()
            val router = VoiceCommandRouter(todoRepository, aiRepository)
            say(tts, INITIAL_PROMPT)
            var turn = 0
            while (turn < MAX_LOOP_TURNS) {
                turn++
                val transcript = listenOnce()
                if (transcript == null) {
                    Log.d(TAG, "voice session: no usable transcript (cancelled, no match, or no recognizer) — ending")
                    break
                }
                Log.d(TAG, "voice session: routing turn $turn")
                val outcome = router.route(transcript)
                say(tts, outcome.reply)
                if (outcome.startPlanning) {
                    onStartPlanning()
                    break
                }
                if (!outcome.continueListening) break
                say(tts, FOLLOWUP_PROMPT)
            }
        }
    }
}

/** Small FAB wrapper around [rememberVoiceCommandLauncher] — see that function's doc comment for the mechanics. */
@Composable
fun VoiceInputButton(
    todoRepository: TodoRepository,
    aiRepository: AiRepository,
    onStartPlanning: () -> Unit,
    onSpoken: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    val startVoiceSession = rememberVoiceCommandLauncher(todoRepository, aiRepository, onStartPlanning, onSpoken, onUnavailable)
    SmallFloatingActionButton(onClick = startVoiceSession) {
        Icon(Icons.Filled.Mic, contentDescription = "Voice command")
    }
}
