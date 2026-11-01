package ee.nekoko.remoble

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

/** Android Keystore equivalent of the iOS Keychain-backed password store. */
internal class HTTPPasswordStore(context: Context) {
    private val preferences =
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return preferences.getString(LEGACY_KEY, "").orEmpty()
        }
        val encoded = preferences.getString(SEALED_KEY, null)
        if (encoded != null) {
            return try {
                val sealed = Base64.decode(encoded, Base64.NO_WRAP)
                if (sealed.size <= IV_BYTES) return ""
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, sealed.copyOfRange(0, IV_BYTES))
                )
                String(cipher.doFinal(sealed.copyOfRange(IV_BYTES, sealed.size)), Charsets.UTF_8)
            } catch (_: Exception) {
                preferences.edit().remove(SEALED_KEY).apply()
                ""
            }
        }

        val legacy = preferences.getString(LEGACY_KEY, "").orEmpty()
        if (legacy.isNotEmpty()) save(legacy)
        return legacy
    }

    fun save(value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) {
            preferences.edit().remove(SEALED_KEY).remove(LEGACY_KEY).apply()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            preferences.edit().putString(LEGACY_KEY, normalized).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit()
                .putString(SEALED_KEY, Base64.encodeToString(sealed, Base64.NO_WRAP))
                .remove(LEGACY_KEY)
                .apply()
        sealed.fill(0)
    }

    @Synchronized
    private fun key(): SecretKey {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
                KeyGenParameterSpec.Builder(
                                KEY_ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "remoble_prefs"
        const val LEGACY_KEY = "password"
        const val SEALED_KEY = "http.password.sealed.v1"
        const val KEY_ALIAS = "remoble-http-password-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
