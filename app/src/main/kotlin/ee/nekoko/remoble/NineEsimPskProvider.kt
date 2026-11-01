package ee.nekoko.remoble

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ee.nekoko.remoble.ble.NineEsimBf71
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Retrieves a per-card PSK and stores it encrypted with an Android Keystore key. */
internal class NineEsimPskProvider(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    suspend fun load(eid: String): ByteArray? = withContext(Dispatchers.IO) {
        require(NineEsimBf71.isValidEid(eid)) { "Invalid card identifier" }
        readCached(eid)?.let { return@withContext it }
        val fetched = fetch(eid) ?: return@withContext null
        saveCached(eid, fetched)
        fetched
    }

    private fun fetch(eid: String): ByteArray? {
        val connection = URL("https://key.9esim.com/psk?eid=$eid").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val declaredLength = connection.contentLength
            if (declaredLength > MAX_RESPONSE_BYTES) error("Card authorization response is too large")
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > MAX_RESPONSE_BYTES) {
                        error("Card authorization response is too large")
                    }
                }
                output.toByteArray()
            }
            val psk = JSONObject(bytes.toString(Charsets.UTF_8)).optString("psk")
            if (!psk.matches(Regex("^[0-9A-Fa-f]{32}$"))) return null
            NineEsimBf71.decodeHex(psk)
        } finally {
            connection.disconnect()
        }
    }

    private fun readCached(eid: String): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val encoded = preferences.getString(account(eid), null) ?: return null
        return try {
            val sealed = Base64.decode(encoded, Base64.NO_WRAP)
            if (sealed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, sealed.copyOfRange(0, IV_BYTES))
            )
            cipher.doFinal(sealed.copyOfRange(IV_BYTES, sealed.size)).takeIf { it.size == 16 }
        } catch (_: Exception) {
            preferences.edit().remove(account(eid)).apply()
            null
        }
    }

    private fun saveCached(eid: String, psk: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(psk)
        preferences.edit()
                .putString(account(eid), Base64.encodeToString(sealed, Base64.NO_WRAP))
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

    private fun account(eid: String) = "card-psk:$eid"

    private companion object {
        const val PREFERENCES = "remoble_card_authorization"
        const val KEY_ALIAS = "remoble-card-authorization-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val MAX_RESPONSE_BYTES = 8192
    }
}
