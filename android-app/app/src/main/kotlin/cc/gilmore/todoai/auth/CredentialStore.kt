package cc.gilmore.todoai.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Backs the login screen's "Remember me" checkbox. Storing a raw password
 * at rest is a real security/convenience tradeoff, not a free feature —
 * this uses EncryptedSharedPreferences (AES256-SIV for keys, AES256-GCM for
 * values, both backed by a master key generated in and never leaving the
 * Android Keystore) rather than plain SharedPreferences, which is the
 * standard Android mitigation for exactly this kind of feature. It is
 * still not equivalent to not storing the password at all: anything that
 * can run as this app's UID on an unlocked, uncompromised device — which
 * is also everything needed to just use the signed-in app — can ask this
 * same class to decrypt it. What it *does* meaningfully defend against is
 * the much more common exposure: the raw value showing up in a device
 * backup, a `adb backup` pull, or a rooted-device file browse, none of
 * which get you anywhere without the Keystore-resident key.
 *
 * If that tradeoff isn't acceptable for this app's threat model later, the
 * honest fix isn't a "more obfuscated" storage scheme — it's dropping the
 * password half of "remember me" and keeping only the username, since
 * staying signed in across launches without storing a password anywhere is
 * already free: see CognitoAuthManager's doc comment on the SDK's own
 * refresh-token persistence, which is what actually keeps you logged in
 * day to day. This store only matters for the "let a new sign-in
 * pre-fill/skip the password field" convenience on top of that.
 *
 * Construction failure (a real possibility — Keystore access can fail on a
 * device with a broken/corrupted keystore, and did on some real Android
 * versions) degrades to "remember me silently doesn't work" rather than
 * crashing the app; see the `step()` pattern in AppContainer.kt for why
 * this one specifically is *not* wrapped the same way — a failure here
 * shouldn't take down sign-in entirely.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences? = run {
        Log.d(TAG, "creating encrypted credential store…")
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { Log.d(TAG, "encrypted credential store ready") }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to create encrypted credential store — remember me will behave as if nothing were ever saved", e)
            null
        }
    }

    fun save(username: String, password: String) {
        val editor = prefs?.edit()
        if (editor == null) {
            Log.w(TAG, "save: no credential store available, dropping silently")
            return
        }
        editor.putString(KEY_USERNAME, username)
        editor.putString(KEY_PASSWORD, password)
        editor.apply()
        Log.d(TAG, "save: remembered credentials for '$username'")
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
        Log.d(TAG, "clear: forgot any remembered credentials")
    }

    fun loadUsername(): String? = safeRead("username") { prefs?.getString(KEY_USERNAME, null) }

    fun loadPassword(): String? = safeRead("password") { prefs?.getString(KEY_PASSWORD, null) }

    fun hasSavedCredentials(): Boolean = loadUsername() != null && loadPassword() != null

    // A file that was encrypted on one device/keystore and somehow read
    // back without that same key (a restore-to-new-device edge case the
    // backup exclusions in res/xml/ are meant to prevent, but defense in
    // depth is cheap here) throws instead of returning null — treat that
    // the same as "nothing saved" rather than crashing the login screen,
    // and drop the now-unreadable file so it doesn't keep throwing on
    // every subsequent read.
    private fun safeRead(what: String, block: () -> String?): String? = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "reading the remembered $what failed — treating as none saved", e)
        clear()
        null
    }

    private companion object {
        const val TAG = "TodoAi.CredentialStore"
        const val PREFS_FILE_NAME = "todo_ai_credentials"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
