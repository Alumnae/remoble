package ee.nekoko.remoble.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
