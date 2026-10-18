package cc.gilmore.todoai.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.voiceLaunchDataStore by preferencesDataStore(name = "voice_launch")

/**
 * Persists one switch (SettingsScreen's "Listen for a new todo when I open
 * the app"): whether a genuine cold start of the app should immediately
 * pop the same speech-to-text screen VoiceInputButton's mic FAB uses,
 * instead of waiting for a tap. Off by default — most app opens are "let
 * me check my list," not "let me add one by voice," and this only enables
 * the fully hands-free "Ok Google, launch Structure" + speak flow for
 * people who've deliberately turned it on (see PodScreen's doc comment for
 * how "cold start" is actually distinguished from "navigated back to
 * Today" or "resumed an already-running process"). Same DataStore-backed,
 * construction-failure-tolerant shape as AiModeStore — see that file's doc
 * comment for why IOException specifically is caught here, not Throwable.
 */
class VoiceLaunchPreferenceStore(context: Context) {
    private val dataStore = context.applicationContext.voiceLaunchDataStore

    val autoListenOnLaunch: Flow<Boolean> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.w(TAG, "reading auto-listen preference failed — defaulting to off", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> prefs[AUTO_LISTEN_KEY] ?: false }

    /** One-shot read for PodScreen's cold-start check — see its doc comment on why this is awaited directly rather than read from a StateFlow snapshot (avoids a race against DataStore's first real emission). */
    suspend fun currentlyEnabled(): Boolean = autoListenOnLaunch.first()

    suspend fun setAutoListenOnLaunch(enabled: Boolean) {
        dataStore.edit { it[AUTO_LISTEN_KEY] = enabled }
        Log.d(TAG, "setAutoListenOnLaunch: persisted $enabled")
    }

    private companion object {
        const val TAG = "TodoAi.VoiceLaunchPreferenceStore"
        val AUTO_LISTEN_KEY = booleanPreferencesKey("auto_listen_on_launch")
    }
}
