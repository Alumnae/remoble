package ee.nekoko.remoble.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object TybleProtocol {
    private val DEVICE_NAME = Regex(
            "^(?:[A-Za-z0-9]+-BT-([0-9A-Fa-f]{8})|9eSIM([0-9]{8}))$",
            RegexOption.IGNORE_CASE
    )

    fun isDeviceName(name: String): Boolean = DEVICE_NAME.matches(name.trim())

    fun uidFromDeviceName(name: String): String {
        val match = DEVICE_NAME.matchEntire(name.trim())
                ?: throw IllegalArgumentException("TYBLE device name does not contain an eight-character UID")
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).uppercase()
    }

    fun pinForDeviceName(name: String): ByteArray {
        val uid = uidFromDeviceName(name)
        val bytes = uid.chunked(2).map { it.toInt(16) }
        val sum = bytes.fold(0) { value, byte -> (value + byte) and 0xff }
        val xor = bytes.fold(0) { value, byte -> value xor byte }
        val mixed = (sum + xor) and 0xff
        return listOf(sum, xor, mixed).flatMap { value ->
            listOf(asciiDigit(value ushr 4), asciiDigit(value and 0x0f))
        }.toByteArray()
    }

    fun authenticationApdu(name: String): ByteArray =
            byteArrayOf(0xff.toByte(), 0x60, 0x5a, 0x01, 0x06) + pinForDeviceName(name)

    fun frame(payload: ByteArray): ByteArray {
        require(payload.size <= 0xffff) { "TYBLE command is too large" }
        return byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload
    }

    fun frameLength(bytes: ByteArray): Int? =
            if (bytes.size < 2) null else 2 + ((bytes[0].toInt() and 0xff) shl 8) +
                    (bytes[1].toInt() and 0xff)

    fun payload(frame: ByteArray): ByteArray {
        val length = frameLength(frame) ?: error("Truncated TYBLE response")
        require(frame.size >= length) { "Truncated TYBLE response" }
        return frame.copyOfRange(2, length)
    }

    private fun asciiDigit(nibble: Int): Byte =
            (0x30 + if (nibble <= 9) nibble else nibble - 6).toByte()
}

internal object BleProtocolCodec {
    data class NbleResponse(val counter: Int, val apdu: ByteArray)

    enum class NbleCardState {
        READY,
        PROACTIVE_REFRESH,
        SETTLING,
        OTHER
    }

    /**
     * Classifies the status words used by 9eSIM while a profile switch is being applied.
     *
     * 91xx asks the handset modem to perform a proactive REFRESH. 6985 and 6999 are the
     * transient replies observed while that reset owns the card. The BLE side must go silent
     * for these states instead of immediately reopening ISD-R, otherwise the card can remain
     * wedged until it is physically re-seated.
     */
    fun nbleCardState(response: ByteArray): NbleCardState {
        if (response.size < 2) return NbleCardState.OTHER
        val sw1 = response[response.size - 2].toInt() and 0xff
        val sw2 = response.last().toInt() and 0xff
        return when {
            sw1 == 0x90 && sw2 == 0x00 -> NbleCardState.READY
            sw1 == 0x91 -> NbleCardState.PROACTIVE_REFRESH
            sw1 == 0x69 && (sw2 == 0x85 || sw2 == 0x99) -> NbleCardState.SETTLING
            else -> NbleCardState.OTHER
        }
    }

    /** Returns true for ES10b Enable/DisableProfile carrying refreshFlag=true. */
    fun nbleRequestsProactiveRefresh(apdu: ByteArray): Boolean {
        if (apdu.size < 8 || (apdu[1].toInt() and 0xff) != 0xe2 ||
                (apdu[2].toInt() and 0xff) != 0x91) return false

        val shortLength = apdu[4].toInt() and 0xff
        val dataOffset: Int
        val dataLength: Int
        if (shortLength != 0) {
            dataOffset = 5
            dataLength = shortLength
        } else {
            if (apdu.size < 7) return false
            dataOffset = 7
            dataLength = ((apdu[5].toInt() and 0xff) shl 8) or
                    (apdu[6].toInt() and 0xff)
        }
        if (dataLength < 6 || dataOffset + dataLength > apdu.size) return false

        val end = dataOffset + dataLength
        if ((apdu[dataOffset].toInt() and 0xff) != 0xbf) return false
        val requestTag = apdu[dataOffset + 1].toInt() and 0xff
        if (requestTag != 0x31 && requestTag != 0x32) return false

        val rootLength = readBerLength(apdu, dataOffset + 2, end) ?: return false
        var cursor = rootLength.second
        val rootEnd = (cursor + rootLength.first).coerceAtMost(end)
        while (cursor < rootEnd) {
            val tag = apdu[cursor].toInt() and 0xff
            val length = readBerLength(apdu, cursor + 1, rootEnd) ?: return false
            val valueStart = length.second
            val valueEnd = valueStart + length.first
            if (valueEnd > rootEnd) return false
            if (tag == 0x81 && length.first == 1) {
                return apdu[valueStart].toInt() != 0
            }
            cursor = valueEnd
        }
        return false
    }

    private fun readBerLength(
            bytes: ByteArray,
            offset: Int,
            limit: Int
    ): Pair<Int, Int>? {
        if (offset >= limit) return null
        val first = bytes[offset].toInt() and 0xff
        if (first < 0x80) return first to (offset + 1)
        val count = first and 0x7f
        if (count == 0 || count > 2 || offset + 1 + count > limit) return null
        var value = 0
        repeat(count) { index ->
            value = (value shl 8) or (bytes[offset + 1 + index].toInt() and 0xff)
        }
        return value to (offset + 1 + count)
    }

    fun beeSimFrames(payload: ByteArray, framePayloadSize: Int): List<ByteArray> {
        require(framePayloadSize > 0) { "BeeSIM frame payload must be positive" }
        val total = if (payload.isEmpty()) 1 else (payload.size + framePayloadSize - 1) / framePayloadSize
        require(total <= 255) { "BeeSIM command needs more than 255 frames" }
        return (0 until total).map { index ->
            val start = index * framePayloadSize
            val end = (start + framePayloadSize).coerceAtMost(payload.size)
            byteArrayOf(total.toByte(), (index + 1).toByte()) + payload.copyOfRange(start, end)
        }
    }

    fun decodeBeeSimFrames(frames: List<ByteArray>): ByteArray {
        require(frames.isNotEmpty()) { "Missing BeeSIM response" }
        val total = frames.first().firstOrNull()?.toInt()?.and(0xff)
                ?: error("Empty BeeSIM response frame")
        require(total == frames.size && total > 0) { "Incomplete BeeSIM response" }
        val output = ArrayList<Byte>()
        frames.forEachIndexed { index, frame ->
            require(frame.size >= 2) { "Truncated BeeSIM response frame" }
            require((frame[0].toInt() and 0xff) == total) { "BeeSIM frame-count mismatch" }
            require((frame[1].toInt() and 0xff) == index + 1) { "BeeSIM frame sequence mismatch" }
            output.addAll(frame.copyOfRange(2, frame.size).toList())
        }
        return output.toByteArray()
    }

    fun selectAid(aid: ByteArray): ByteArray {
        require(aid.isNotEmpty() && aid.size <= 255) { "AID length must be between 1 and 255 bytes" }
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) +
                aid + byteArrayOf(0x00)
    }

    fun redFrame(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(payload.size <= 0xffff) { "RED payload is too large" }
        return byteArrayOf(
                command.toByte(),
                (payload.size and 0xff).toByte(),
                ((payload.size ushr 8) and 0xff).toByte()
        ) + payload
    }

    fun redFrameLength(buffer: ByteArray): Int? {
        if (buffer.size < 3) return null
        return 3 + (buffer[1].toInt() and 0xff) + ((buffer[2].toInt() and 0xff) shl 8)
    }

    fun ccidFrame(messageType: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val header = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        header.put(messageType.toByte())
        header.putInt(payload.size)
        header.put(0) // slot
        header.put(0) // sequence
        header.put(0) // status
        header.put(0) // error
        header.put(0) // message-specific
        return header.array() + payload
    }

    fun ccidFrameLength(buffer: ByteArray): Int? {
        if (buffer.size < 10) return null
        val payloadLength = ByteBuffer.wrap(buffer, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(payloadLength >= 0) { "Invalid CCID payload length" }
        return 10 + payloadLength
    }

    fun ccidPayload(frame: ByteArray): ByteArray {
        val expected = ccidFrameLength(frame) ?: error("Truncated CCID header")
        require(frame.size >= expected) { "Truncated CCID response" }
        return frame.copyOfRange(10, expected)
    }

    fun nbleFrames(counter: Int, apdu: ByteArray, maxWriteSize: Int = 101): List<ByteArray> {
        require(maxWriteSize >= 2) { "NBLE write size must leave room for a header" }
        val body = byteArrayOf((counter and 0xff).toByte()) + apdu
        val checksum = body.fold(0) { total, byte -> (total + (byte.toInt() and 0xff)) and 0xffff }
        val full = body + byteArrayOf((checksum ushr 8).toByte(), checksum.toByte())
        val chunkSize = maxWriteSize - 1
        return full.asList().chunked(chunkSize).mapIndexed { index, chunk ->
            val isLast = (index + 1) * chunkSize >= full.size
            val header = (if (isLast) 0x20 else 0x00) or (index and 0x1f)
            byteArrayOf(header.toByte()) + chunk.toByteArray()
        }
    }

    fun decodeNbleFrames(frames: List<ByteArray>): NbleResponse {
        require(frames.isNotEmpty()) { "Missing NBLE response" }
        frames.forEachIndexed { index, frame ->
            require(frame.isNotEmpty()) { "Empty NBLE frame" }
            require((frame[0].toInt() and 0x1f) == (index and 0x1f)) {
                "NBLE frame sequence mismatch"
            }
            val isLast = (frame[0].toInt() and 0x20) != 0
            require(isLast == (index == frames.lastIndex)) { "Invalid NBLE final-frame marker" }
        }
        val payload = frames.fold(byteArrayOf()) { output, frame -> output + frame.copyOfRange(1, frame.size) }
        require(payload.size >= 5) { "NBLE response is too short" }
        val raw = payload.copyOfRange(0, payload.size - 2)
        val checksum = ((payload[payload.size - 2].toInt() and 0xff) shl 8) or
                (payload.last().toInt() and 0xff)
        val expected = raw.fold(0) { total, byte -> (total + (byte.toInt() and 0xff)) and 0xffff }
        require(checksum == expected) { "NBLE response checksum mismatch" }
        return NbleResponse(raw[0].toInt() and 0xff, raw.copyOfRange(1, raw.size))
    }
}
