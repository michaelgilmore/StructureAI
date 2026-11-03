package cc.gilmore.todoai.ui.voiceprompt

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.gilmore.todoai.data.local.VoiceLaunchPreferenceStore
import kotlinx.coroutines.delay

private const val COUNTDOWN_SECONDS = 5

/**
 * Shown exactly once per process, right after SplashScreen finishes and
 * before anything else in the main app composes (see
 * ui/navigation/NavGraph.kt) — "do you want to use voice [this launch]?",
 * counting down from [COUNTDOWN_SECONDS] seconds and auto-resolving to
 * whatever [VoiceLaunchPreferenceStore]'s persisted switch currently says
 * if nothing is tapped in time.
 *
 * [onDecision] carries the result straight to PodScreen's cold-start
 * auto-listen check via `TodoAiApplication.voiceLaunchPromptDecision` —
 * see NavGraph.kt for the wiring. This deliberately never calls
 * [VoiceLaunchPreferenceStore.setAutoListenOnLaunch]: answering either way
 * here, or letting the countdown lapse, never changes SettingsScreen's
 * "Listen for what to do when I open the app" switch. It's a one-time
 * override for this single launch, read fresh from the persisted setting
 * every time (so it still reflects whatever Settings currently says), not
 * a second place that setting gets written.
 */
@Composable
fun VoiceLaunchPromptDialog(
    voiceLaunchPreferenceStore: VoiceLaunchPreferenceStore,
    onDecision: (Boolean) -> Unit,
) {
    // Null until the persisted setting has actually been read — this is
    // also the countdown's own fallback value, so nothing is shown (and
    // the countdown can't start) until it resolves. In practice this is a
    // DataStore read that completes in a few milliseconds.
    var persistedDefault by remember { mutableStateOf<Boolean?>(null) }
    var secondsLeft by remember { mutableStateOf(COUNTDOWN_SECONDS) }
    var resolved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        persistedDefault = voiceLaunchPreferenceStore.currentlyEnabled()
    }

    val fallback = persistedDefault ?: return

    fun resolve(value: Boolean) {
        // Guards against the countdown firing after a tap already has (or
        // vice versa) — whichever happens first wins, onDecision fires
        // exactly once.
        if (resolved) return
        resolved = true
        onDecision(value)
    }

    LaunchedEffect(fallback) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        resolve(fallback)
    }

    AlertDialog(
        // Back-press counts as "don't make me decide" — same outcome as
        // letting the countdown lapse, not a way to dodge the dialog
        // entirely.
        onDismissRequest = { resolve(fallback) },
        title = { Text("Use voice?") },
        text = {
            Column {
                Text("Would you like to use voice to tell Structure what to do right now?")
                Text(
                    "Defaulting to ${if (fallback) "Yes" else "No"} in ${secondsLeft}s…",
                    modifier = Modifier.padding(top = 8.dp),
                )
                LinearProgressIndicator(
                    progress = secondsLeft / COUNTDOWN_SECONDS.toFloat(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { resolve(true) }) { Text("Yes") }
        },
        dismissButton = {
            TextButton(onClick = { resolve(false) }) { Text("No") }
        },
    )
}
