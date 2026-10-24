package ee.nekoko.remoble.ble

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** NBLESim/9eSIM password reduction and challenge-response helpers. */
internal object NbleAuth {
    const val PREF_KEY = "ble_nble_pin"

    fun isHexPin(value: String): Boolean = value.trim().matches(Regex("^[0-9a-fA-F]{6}$"))

    fun defaultPasswordForDeviceName(deviceName: String): String {
        val cleanName = deviceName.trim().uppercase()
        val suffix = cleanName.takeLast(6)
        return suffix.reversed()
    }

    fun calculatePasswordHex(value: String): String {
        if (isHexPin(value)) return value.trim().uppercase()

        var crc = 0xB704CE
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xff) shl 16)
            repeat(8) {
                crc = crc shl 1
                if ((crc and 0x1000000) != 0) crc = crc xor 0x1864CFB
            }
        }
        return "%06X".format(crc and 0xFFFFFF)
    }

    fun buildTripleDesKey(deviceName: String, password: String): ByteArray {
        val identifier = deviceName.trim().takeLast(6).uppercase()
        require(identifier.length >= 2 && identifier.takeLast(2).matches(Regex("^[0-9A-F]{2}$"))) {
            "NBLE device name must end in two hexadecimal characters"
        }
        val authBase = identifier.takeLast(2) + calculatePasswordHex(password)
        val twoKey = "$authBase$authBase${authBase}00000000".hexToBytes()
        // Java's DESede provider expects K1|K2|K1 rather than the compact 16-byte form.
        return twoKey + twoKey.copyOfRange(0, 8)
    }

    fun transform(block: ByteArray, key: ByteArray, decrypt: Boolean): ByteArray {
        require(block.size % 8 == 0) { "NBLE challenge length must be a multiple of 8" }
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(
                if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "DESede")
        )
        return cipher.doFinal(block)
    }
}
