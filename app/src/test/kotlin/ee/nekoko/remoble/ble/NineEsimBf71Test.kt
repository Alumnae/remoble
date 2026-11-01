package ee.nekoko.remoble.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NineEsimBf71Test {
    @Test
    fun syntheticAesVectorMatchesClarivateAndOpenSsl() {
        val request = NineEsimBf71.buildRequest(
                ByteArray(16) { it.toByte() },
                ByteArray(16) { (it + 16).toByte() },
                ByteArray(16) { (it + 32).toByte() }
        )
        assertEquals(
                "bf7118800406a18b068110202122232425262728292a2b2c2d2e2f",
                request.toHex()
        )
    }

    @Test
    fun b0RangeDoesNotCaptureOtherCards() {
        assertTrue(NineEsimBf71.requiresAuthorization("89086029202200002122000030900000"))
        assertTrue(NineEsimBf71.requiresAuthorization("89086029202200002122000031589236"))
        assertFalse(NineEsimBf71.requiresAuthorization("89086029202200002122000040000000"))
        assertFalse(NineEsimBf71.requiresAuthorization("89086030202200000026000482694297"))
        assertFalse(NineEsimBf71.requiresAuthorization(null))
    }

    @Test
    fun detectsCommandsAndExtractsCardValues() {
        assertTrue(NineEsimBf71.isGetEid("81e2910006bf3e035c015a".hexToBytes()))
        assertTrue(NineEsimBf71.isGetChallenge("81e2910003bf2e00".hexToBytes()))
        assertTrue(NineEsimBf71.isAuthenticateServer("81e2110005bf38020100".hexToBytes()))
        assertTrue(NineEsimBf71.isAuthorization("81e291001bbf7118800400000000811000000000000000000000000000000000".hexToBytes()))

        assertEquals(
                "89086029202200002122000031589236",
                NineEsimBf71.extractEid(
                        "bf3e125a10890860292022000021220000315892369000".hexToBytes()
                )
        )
        val challenge = "101112131415161718191a1b1c1d1e1f".hexToBytes()
        assertArrayEquals(
                challenge,
                NineEsimBf71.extractChallenge(
                        ("bf2e128010" + challenge.toHex() + "9000").hexToBytes()
                )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCryptographicInputs() {
        NineEsimBf71.buildRequest(ByteArray(15), ByteArray(16), ByteArray(16))
    }
}
