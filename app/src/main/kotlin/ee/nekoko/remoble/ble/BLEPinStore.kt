package ee.nekoko.remoble.ble

import android.content.Context

/**
 * Lightweight per-reader PIN store.
 *
 * Do not touch AndroidKeyStore from the UI refresh path. Some vendor keystore providers block for
 * seconds after boot or after the app process is restored, which made the whole card list stop
 * responding because MainActivity asks for the saved-PIN state every two seconds.
 */
internal class BLEPinStore(context: Context) {
    private val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(identity: String, legacyPreferences: android.content.SharedPreferences, legacyKey: String): String {
        val key = entry(identity)
        preferences.getString(key, null)?.let { stored ->
            if (NbleAuth.isHexPin(stored)) return stored
            // Values written by the short-lived Keystore implementation are ciphertext, not a
            // PIN. Drop them so the user can enter the current credential without clearing all
            // application data.
            preferences.edit().remove(key).apply()
        }
        val legacy = legacyPreferences.getString(legacyKey, "").orEmpty()
        legacyPreferences.edit().remove(legacyKey).apply()
        if (legacy.isNotEmpty()) save(identity, legacy)
        return legacy
    }

    fun save(identity: String, value: String) {
        val normalized = value.trim().uppercase()
        if (normalized.isEmpty()) {
            delete(identity)
            return
        }
        preferences.edit().putString(entry(identity), normalized).apply()
    }

    fun delete(identity: String) {
        preferences.edit().remove(entry(identity)).apply()
    }

    private fun entry(identity: String) = "pin.${identity.uppercase().replace(Regex("[^0-9A-F]"), "")}.v1"

    private companion object {
        const val PREFERENCES = "remoble_ble_credentials"
    }
}
