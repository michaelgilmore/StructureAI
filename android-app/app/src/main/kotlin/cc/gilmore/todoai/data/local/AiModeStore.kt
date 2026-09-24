package cc.gilmore.todoai.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.gilmore.todoai.model.AiMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.aiModeDataStore by preferencesDataStore(name = "ai_mode")

/**
 * Persists the user's chosen [AiMode] (Cloud vs. Home) via Jetpack
 * Preferences DataStore. This is groundwork, not a live feature yet — see
 * [AiMode]'s doc comment: nothing in the UI can actually write HOME today,
 * so [mode] will read CLOUD for every real user until Phase 3 enables
 * SettingsScreen's Home chip. Modeled the same shape as
 * [cc.gilmore.todoai.auth.CredentialStore] (construction-failure-tolerant,
 * a plain Context-scoped store) but using DataStore instead of
 * EncryptedSharedPreferences — nothing stored here is sensitive, so the
 * encryption CredentialStore pays for isn't needed.
 *
 * `Context.preferencesDataStore` is itself a singleton delegate keyed by
 * `name`, so constructing more than one [AiModeStore] from the same
 * application Context (which shouldn't happen — AppContainer holds one) is
 * safe: they'd share the same underlying file rather than fight over it.
 */
class AiModeStore(context: Context) {
    private val dataStore = context.applicationContext.aiModeDataStore

    /**
     * Reading DataStore can throw (backing file I/O failure) — caught and
     * degraded to "nothing stored" rather than crashing whichever screen
     * collects this, mirroring CredentialStore.safeRead's philosophy.
     * IOException specifically, not Throwable: a real bug (e.g. a bad
     * Preferences key type) should still surface as a crash rather than
     * silently reading back CLOUD forever.
     */
    val mode: Flow<AiMode> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                Log.w(TAG, "reading AI mode failed — defaulting to Cloud", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> AiMode.fromWire(prefs[MODE_KEY]) }

    /** One-shot read for callers (AiRepository) that don't want to collect a Flow per request. */
    suspend fun currentMode(): AiMode = mode.first()

    /**
     * Not called from anywhere reachable in the UI today — see [AiMode]'s
     * doc comment. Exists so Phase 3 only needs to wire SettingsScreen's
     * Home [androidx.compose.material3.FilterChip] to this, not build a
     * write path from scratch.
     */
    suspend fun setMode(mode: AiMode) {
        dataStore.edit { it[MODE_KEY] = mode.wireValue }
        Log.d(TAG, "setMode: persisted ${mode.wireValue}")
    }

    private companion object {
        const val TAG = "TodoAi.AiModeStore"
        val MODE_KEY = stringPreferencesKey("mode")
    }
}
