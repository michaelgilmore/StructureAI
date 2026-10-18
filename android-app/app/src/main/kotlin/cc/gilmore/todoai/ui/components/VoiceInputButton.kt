package cc.gilmore.todoai.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val TAG = "TodoAi.VoiceInput"
private const val PROMPT_UTTERANCE_ID = "todoai_voice_prompt"
private const val SPOKEN_PROMPT = "What would you like to add?"

/**
 * Owns a [TextToSpeech] engine for as long as the composition that calls
 * this is alive, releasing it in onDispose. [rememberVoiceInputLauncher]
 * uses it to speak [SPOKEN_PROMPT] out loud before opening the speech
 * recognizer, so there's an audible cue to start talking — this matters
 * most for the hands-free auto-listen-on-launch path (see PodScreen's doc
 * comment), where nothing else tells you the app is now listening, but
 * it's wired into every voice-input trigger (manual FAB tap included) so
 * the behavior is the same everywhere.
 *
 * Init is asynchronous (the system TTS engine loads out of process), so
 * this returns a value that starts null and flips to the real engine once
 * [TextToSpeech]'s init callback fires. A caller sees a still-null engine
 * the same as "no voice prompt available right now" — [rememberVoiceInputLauncher]
 * falls straight through to opening the recognizer with no spoken prompt
 * in that case, same as how a missing speech-recognizer app is handled:
 * this feature degrades silently rather than ever blocking voice-add.
 */
@Composable
fun rememberVoiceTts(): TextToSpeech? {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine = created
            } else {
                Log.w(TAG, "TextToSpeech init failed (status=$status) — voice prompt will be skipped")
            }
        }
        onDispose {
            created?.stop()
            created?.shutdown()
            engine = null
        }
    }
    return engine
}

/**
 * Builds and returns a zero-arg trigger function that, if [tts] is ready,
 * speaks [SPOKEN_PROMPT] out loud and — once speech finishes (or fails) —
 * launches the system's own speech-to-text screen
 * (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` — the same one-shot dictation
 * dialog Google's own apps use), handing the top transcription candidate
 * to [onResult]. If [tts] is null (still initializing, or failed to
 * initialize), the recognizer opens immediately with no spoken prompt.
 * Pulled out of [VoiceInputButton] so PodScreen can fire the exact same
 * flow from a `LaunchedEffect` (its cold-start auto-listen check) without
 * a visible button click to hang it off — both the button and the
 * auto-listen path share one implementation (and one [TextToSpeech]
 * instance, via [rememberVoiceTts]) rather than two copies that could
 * drift apart.
 *
 * [UtteranceProgressListener]'s completion callbacks fire on a background
 * thread owned by the TTS service, not the main thread — launching an
 * [androidx.activity.result.ActivityResultLauncher] from there would
 * crash, so the actual recognizer launch is posted back to the main
 * thread via a [Handler] before calling it.
 *
 * Deliberately not a hand-rolled `android.speech.SpeechRecognizer` +
 * `RecognitionListener`: that needs the `RECORD_AUDIO` runtime permission
 * and this app managing partial results/no-match/network-error states
 * itself. Delegating to the system activity needs neither — no new
 * permission in AndroidManifest.xml, no listener lifecycle to own, and the
 * person gets the exact mic UI they already know from every other app.
 *
 * `ActivityResultLauncher.launch` calls straight through to
 * `Activity.startActivityForResult` under the hood, so it throws
 * [ActivityNotFoundException] the same way a plain `startActivity()` would
 * when no app can fulfill the intent — caught below rather than checked for
 * in advance with `PackageManager.resolveActivity`, because Android 11+'s
 * package-visibility filtering can make that kind of pre-check return null
 * even when a recognizer IS installed (see Android's package-visibility use
 * cases doc: "the most direct approach ... is to call the intent and handle
 * ActivityNotFoundException"). No `<queries>` manifest entry is needed for
 * the recognizer itself — that restriction governs *introspecting* other
 * apps (`resolveActivity`/`queryIntentActivities`), not launching one via
 * `startActivity()`. (The spoken prompt's [TextToSpeech] engine lookup is
 * a different case and does need one — see AndroidManifest.xml's
 * `<queries>` block.)
 */
@Composable
fun rememberVoiceInputLauncher(
    tts: TextToSpeech?,
    onResult: (String) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val transcript = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!transcript.isNullOrBlank()) onResult(transcript)
        }
        // RESULT_CANCELED (backed out of the mic dialog) or an empty/missing
        // transcript: do nothing, same as dismissing the manual add-todo
        // form without saving.
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    fun openRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Add a todo…")
        }
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }

    return {
        if (tts == null) {
            Log.d(TAG, "TTS not ready — opening recognizer with no spoken prompt")
            openRecognizer()
        } else {
            Log.d(TAG, "speaking prompt before opening recognizer")
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    mainHandler.post { openRecognizer() }
                }

                @Deprecated("Deprecated in Java, but still the one abstract overload UtteranceProgressListener requires")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { openRecognizer() }
                }
            })
            tts.speak(SPOKEN_PROMPT, TextToSpeech.QUEUE_FLUSH, null, PROMPT_UTTERANCE_ID)
        }
    }
}

/** Small FAB wrapper around [rememberVoiceInputLauncher] — see that function's doc comment for the mechanics. */
@Composable
fun VoiceInputButton(onResult: (String) -> Unit, onUnavailable: () -> Unit) {
    val tts = rememberVoiceTts()
    val startVoiceInput = rememberVoiceInputLauncher(tts, onResult, onUnavailable)
    SmallFloatingActionButton(onClick = startVoiceInput) {
        Icon(Icons.Filled.Mic, contentDescription = "Add todo by voice")
    }
}
