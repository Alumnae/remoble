package ee.nekoko.remoble.ble

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Card-level BF71 authorization used by the observed 9eSIM B0 download flow.
 *
 * This is deliberately separate from the BLE password/challenge handshake: BF71 runs on an
 * already selected ISD-R logical channel, after getEuiccChallenge and immediately before the
 * first authenticateServer block.
 */
internal object NineEsimBf71 {
    private const val B0_EID_START = "89086029202200002122000030900000"
    private const val B0_EID_END_EXCLUSIVE = "89086029202200002122000040000000"
    private val EID_PATTERN = Regex("^[0-9]{32}$")

    fun isValidEid(eid: String?): Boolean = eid != null && EID_PATTERN.matches(eid)

    fun requiresAuthorization(eid: String?): Boolean =
            isValidEid(eid) && eid!! >= B0_EID_START && eid < B0_EID_END_EXCLUSIVE

    fun isGetEid(command: ByteArray): Boolean = apduDataTag(command) == 0xbf3e

    fun isGetChallenge(command: ByteArray): Boolean = apduDataTag(command) == 0xbf2e

    fun isAuthenticateServer(command: ByteArray): Boolean = apduDataTag(command) == 0xbf38

    fun isAuthorization(command: ByteArray): Boolean = apduDataTag(command) == 0xbf71

    fun extractEid(response: ByteArray): String? {
        val value = nestedValue(responseWithoutStatus(response), 0xbf3e, 0x5a) ?: return null
        if (value.size != 16) return null
        return value.joinToString("") { "%02X".format(it) }.takeIf(EID_PATTERN::matches)
    }

    fun extractChallenge(response: ByteArray): ByteArray? {
        val value = nestedValue(responseWithoutStatus(response), 0xbf2e, 0x80) ?: return null
        return value.takeIf { it.size == 16 }?.copyOf()
    }

    fun buildRequest(psk: ByteArray, challenge: ByteArray, nonce: ByteArray): ByteArray {
        require(psk.size == 16 && challenge.size == 16 && nonce.size == 16) {
            "BF71 authorization requires 16-byte inputs"
        }
        val seed = challenge.copyOfRange(0, 8) + nonce.copyOfRange(0, 8)
        val derived = aesCbcEncrypt(psk, seed)
        val encrypted = try {
            aesCbcEncrypt(derived, challenge)
        } finally {
            derived.fill(0)
            seed.fill(0)
        }
        val mac = ByteArray(4) { index ->
            (encrypted[index].toInt() xor encrypted[index + 8].toInt()).toByte()
        }
        encrypted.fill(0)
        return byteArrayOf(
                0xbf.toByte(), 0x71, 0x18,
                0x80.toByte(), 0x04,
                *mac,
                0x81.toByte(), 0x10,
                *nonce
        )
    }

    fun buildApdu(psk: ByteArray, challenge: ByteArray, nonce: ByteArray): ByteArray {
        val request = buildRequest(psk, challenge, nonce)
        return byteArrayOf(0x81.toByte(), 0xe2.toByte(), 0x91.toByte(), 0x00, request.size.toByte()) + request
    }

    fun isSuccess(response: ByteArray): Boolean =
            response.size >= 2 && response[response.size - 2] == 0x90.toByte() && response.last() == 0x00.toByte()

    fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("^[0-9A-Fa-f]+$"))) {
            "Invalid hexadecimal value"
        }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun aesCbcEncrypt(key: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(ByteArray(16))
        )
        return cipher.doFinal(input)
    }

    private fun apduDataTag(command: ByteArray): Int? {
        val data = apduData(command) ?: return null
        return readTlv(data, 0)?.tag
    }

    private fun apduData(command: ByteArray): ByteArray? {
        if (command.size <= 5) return null
        val firstLength = command[4].toInt() and 0xff
        if (firstLength != 0) {
            if (command.size < 5 + firstLength) return null
            return command.copyOfRange(5, 5 + firstLength)
        }
        if (command.size < 7) return null
        val length = ((command[5].toInt() and 0xff) shl 8) or (command[6].toInt() and 0xff)
        if (length == 0 || command.size < 7 + length) return null
        return command.copyOfRange(7, 7 + length)
    }

    private fun responseWithoutStatus(response: ByteArray): ByteArray {
        if (response.size < 2 || !isSuccess(response)) return ByteArray(0)
        return response.copyOf(response.size - 2)
    }

    private fun nestedValue(data: ByteArray, outerTag: Int, childTag: Int): ByteArray? {
        val outer = readTlv(data, 0) ?: return null
        if (outer.tag != outerTag) return null
        var offset = outer.valueStart
        while (offset < outer.valueEnd) {
            val child = readTlv(data, offset, outer.valueEnd) ?: return null
            if (child.tag == childTag) return data.copyOfRange(child.valueStart, child.valueEnd)
            offset = child.valueEnd
        }
        return null
    }

    private data class Tlv(val tag: Int, val valueStart: Int, val valueEnd: Int)

    private fun readTlv(data: ByteArray, start: Int, limit: Int = data.size): Tlv? {
        if (start !in 0 until limit || limit > data.size) return null
        var offset = start
        val first = data[offset++].toInt() and 0xff
        var tag = first
        if (first and 0x1f == 0x1f) {
            do {
                if (offset >= limit) return null
                val next = data[offset++].toInt() and 0xff
                tag = (tag shl 8) or next
            } while (next and 0x80 != 0)
        }
        if (offset >= limit) return null
        val firstLength = data[offset++].toInt() and 0xff
        val length = if (firstLength and 0x80 == 0) {
            firstLength
        } else {
            val count = firstLength and 0x7f
            if (count == 0 || count > 4 || offset + count > limit) return null
            var decoded = 0
            repeat(count) { decoded = (decoded shl 8) or (data[offset++].toInt() and 0xff) }
            decoded
        }
        val end = offset + length
        if (end < offset || end > limit) return null
        return Tlv(tag, offset, end)
    }
}
