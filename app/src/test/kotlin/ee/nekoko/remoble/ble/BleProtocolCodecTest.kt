package ee.nekoko.remoble.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleProtocolCodecTest {
    @Test
    fun selectAidBuildsIso7816Command() {
        val aid = "A0000005591010FFFFFFFF8900000100".hexToBytes()
        assertEquals(
                "00a4040010a0000005591010ffffffff890000010000",
                BleProtocolCodec.selectAid(aid).toHex()
        )
    }

    @Test
    fun redFrameUsesLittleEndianLength() {
        val frame = BleProtocolCodec.redFrame(0x04, ByteArray(258) { 0x5a })
        assertEquals(0x04, frame[0].toInt())
        assertEquals(0x02, frame[1].toInt())
        assertEquals(0x01, frame[2].toInt())
        assertEquals(frame.size, BleProtocolCodec.redFrameLength(frame))
    }

    @Test
    fun ccidRoundTripExtractsPayload() {
        val payload = byteArrayOf(0x90.toByte(), 0x00)
        val frame = BleProtocolCodec.ccidFrame(0x80, payload)
        assertEquals(12, BleProtocolCodec.ccidFrameLength(frame))
        assertArrayEquals(payload, BleProtocolCodec.ccidPayload(frame))
    }

    @Test
    fun detectsTheSameReaderFamiliesAsTheBridgeProfiles() {
        assertEquals(BleProfileFactory.TYPE_RED_2, BleProfileFactory.detect("ESTKme RED", emptySet()))
        assertEquals(BleProfileFactory.TYPE_RED, BleProfileFactory.detect("ESTKme", emptySet()))
        assertEquals(
                BleProfileFactory.TYPE_SIMLINK,
                BleProfileFactory.detect(
                        "unknown",
                        setOf(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
                )
        )
        assertEquals(BleProfileFactory.TYPE_NBLE, BleProfileFactory.detect("9eSIM888888", emptySet()))
        assertEquals(BleProfileFactory.TYPE_SIMLINK, BleProfileFactory.detect("eSIM_Writer", emptySet()))
    }

    @Test
    fun nbleNativeFrameMatchesNekokoLpa2() {
        val apdu = "01a4040010a0000005591010ffffffff890000010000".hexToBytes()
        val frames = BleProtocolCodec.nbleFrames(4, apdu)
        assertEquals(1, frames.size)
        assertEquals(
                "200401a4040010a0000005591010ffffffff8900000100000661",
                frames.single().toHex()
        )
        val decoded = BleProtocolCodec.decodeNbleFrames(
                BleProtocolCodec.nbleFrames(4, "9000".hexToBytes())
        )
        assertEquals(4, decoded.counter)
        assertArrayEquals("9000".hexToBytes(), decoded.apdu)
    }

    @Test
    fun nblePinRulesMatchNekokoLpa2() {
        assertEquals("778899", NbleAuth.calculatePasswordHex(" 778899 "))
        assertEquals("ABCDEF", NbleAuth.calculatePasswordHex("abcdef"))
        assertTrue(NbleAuth.calculatePasswordHex("hunter2").matches(Regex("^[0-9A-F]{6}$")))
        assertFalse(NbleAuth.calculatePasswordHex("77889") == "77889")
        assertEquals("311052", NbleAuth.defaultPasswordForDeviceName("9eSIM250113"))
        assertEquals(24, NbleAuth.buildTripleDesKey("9eSIM250113", "311052").size)
    }
}
