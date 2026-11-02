package ee.nekoko.remoble.ble

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores per-reader BLE PINs encrypted by an Android Keystore key. */
internal class BLEPinStore(context: Context) {
    private val preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(identity: String, legacyPreferences: android.content.SharedPreferences, legacyKey: String): String {
        val key = entry(identity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            preferences.getString(key, null)?.let { encoded ->
                return try {
                    val sealed = Base64.decode(encoded, Base64.NO_WRAP)
                    require(sealed.size > IV_BYTES)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                            GCMParameterSpec(128, sealed.copyOfRange(0, IV_BYTES)))
                    String(cipher.doFinal(sealed.copyOfRange(IV_BYTES, sealed.size)), Charsets.UTF_8)
                } catch (_: Exception) {
                    preferences.edit().remove(key).apply()
                    ""
                }
            }
        }

        // One-time migration from the old plaintext preference. API 21/22 deliberately do not
        // persist the PIN because their Keystore lacks the AES/GCM primitive used here.
        val legacy = legacyPreferences.getString(legacyKey, "").orEmpty()
        legacyPreferences.edit().remove(legacyKey).apply()
        if (legacy.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) save(identity, legacy)
        return legacy
    }

    fun save(identity: String, value: String) {
        val normalized = value.trim().uppercase()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || normalized.isEmpty()) {
            delete(identity)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(entry(identity), Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        sealed.fill(0)
    }

    fun delete(identity: String) {
        preferences.edit().remove(entry(identity)).apply()
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        return generator.generateKey()
    }

    private fun entry(identity: String) = "pin.${identity.uppercase().replace(Regex("[^0-9A-F]"), "")}.v1"

    private companion object {
        const val PREFERENCES = "remoble_ble_credentials"
        const val KEY_ALIAS = "remoble-ble-pin-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
