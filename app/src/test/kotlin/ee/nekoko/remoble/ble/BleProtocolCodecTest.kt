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
        assertEquals(BleProfileFactory.TYPE_TYBLE, BleProfileFactory.detect("9eSIM00000184", emptySet()))
        assertEquals(BleProfileFactory.TYPE_TYBLE, BleProfileFactory.detect("TES-BT-00000184", emptySet()))
        assertEquals(BleProfileFactory.TYPE_SIMLINK, BleProfileFactory.detect("eSIM_Writer", emptySet()))
        assertEquals(BleProfileFactory.TYPE_BEESIM, BleProfileFactory.detect("BeeSIM", emptySet()))
        assertEquals(
                BleProfileFactory.TYPE_BEESIM,
                BleProfileFactory.detect("unknown", setOf(UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")))
        )
    }

    @Test
    fun tybleB1AuthenticationAndFramingMatchUpstreamVectors() {
        assertEquals("00000184", TybleProtocol.uidFromDeviceName("9eSIM00000184"))
        assertEquals("858504", String(TybleProtocol.pinForDeviceName("9eSIM00000184"), Charsets.US_ASCII))
        assertEquals("ff605a0106333230343336",
                TybleProtocol.authenticationApdu("SCT-BT-49535343").toHex())
        val frame = TybleProtocol.frame("9000".hexToBytes())
        assertEquals("00029000", frame.toHex())
        assertEquals(4, TybleProtocol.frameLength(frame))
        assertArrayEquals("9000".hexToBytes(), TybleProtocol.payload(frame))
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
    fun classifiesNbleProfileSwitchStatusWords() {
        assertEquals(
                BleProtocolCodec.NbleCardState.READY,
                BleProtocolCodec.nbleCardState("9000".hexToBytes())
        )
        assertEquals(
                BleProtocolCodec.NbleCardState.PROACTIVE_REFRESH,
                BleProtocolCodec.nbleCardState("9110".hexToBytes())
        )
        assertEquals(
                BleProtocolCodec.NbleCardState.SETTLING,
                BleProtocolCodec.nbleCardState("6985".hexToBytes())
        )
        assertEquals(
                BleProtocolCodec.NbleCardState.SETTLING,
                BleProtocolCodec.nbleCardState("6999".hexToBytes())
        )
        assertEquals(
                BleProtocolCodec.NbleCardState.OTHER,
                BleProtocolCodec.nbleCardState("6d00".hexToBytes())
        )
    }

    @Test
    fun detectsRefreshFlagInEnableAndDisableProfileCommands() {
        val iccid = "98100000000000000000"
        val enable = "80e2910014bf3111a00c5a0a${iccid}8101ff".hexToBytes()
        val disable = "80e2910014bf3211a00c5a0a${iccid}8101ff".hexToBytes()
        val noRefresh = "80e2910014bf3111a00c5a0a${iccid}810100".hexToBytes()
        val unrelated = "80e2910006bf3e035c015a".hexToBytes()

        assertTrue(BleProtocolCodec.nbleRequestsProactiveRefresh(enable))
        assertTrue(BleProtocolCodec.nbleRequestsProactiveRefresh(disable))
        assertFalse(BleProtocolCodec.nbleRequestsProactiveRefresh(noRefresh))
        assertFalse(BleProtocolCodec.nbleRequestsProactiveRefresh(unrelated))
    }

    @Test
    fun beeSimFramesRoundTripAtNegotiatedMtu() {
        val payload = ByteArray(500) { (it and 0xff).toByte() }
        val frames = BleProtocolCodec.beeSimFrames(payload, 242)

        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size <= 244 })
        assertEquals(1, frames.first()[1].toInt())
        assertEquals(3, frames.last()[1].toInt())
        assertArrayEquals(payload, BleProtocolCodec.decodeBeeSimFrames(frames))
    }

    @Test(expected = IllegalArgumentException::class)
    fun beeSimRejectsOutOfOrderResponseFrames() {
        BleProtocolCodec.decodeBeeSimFrames(
                listOf(byteArrayOf(2, 1, 0x11), byteArrayOf(2, 3, 0x22))
        )
    }

    @Test
    fun beeSimPacerCoolsDownBeforeCommandTwentyTwo() {
        val delays = mutableListOf<Long>()
        val pacer = BeeSimCommandPacer(delays::add)
        repeat(21) { assertFalse(pacer.beforeCommand()) }
        assertTrue(pacer.beforeCommand())
        assertEquals(listOf(1_200L), delays)
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

    @Test
    fun nbleCredentialUpdateMatchesNekokoLpa2() {
        assertEquals(
                "3027000009111111A1B2C3D4E5F6",
                NbleAuth.credentialUpdateApdu(0x30, "a1b2c3", "d4e5f6").toHex().uppercase()
        )
        assertEquals(
                "8027000009111111A1B2C3D4E5F6",
                NbleAuth.credentialUpdateApdu(0x80, "A1B2C3", "D4E5F6").toHex().uppercase()
        )
    }
}
