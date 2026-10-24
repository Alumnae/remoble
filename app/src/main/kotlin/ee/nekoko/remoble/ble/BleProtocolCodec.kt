package ee.nekoko.remoble.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object BleProtocolCodec {
    data class NbleResponse(val counter: Int, val apdu: ByteArray)

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
