package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pipeline item 9: the pad keystream depends on both phones' seeds. */
class PadMixerTest {
    private fun stream(peer: ByteArray, own: ByteArray, n: Int = 4096) = CableProtocol.mixer(peer.copyOf(), own.copyOf()).update(ByteArray(n))
    @Test fun keystreamIsTheDocumentedAesCtrAndDependsOnBothSeeds() {
        val b = ByteArray(32) { 1 }; val a = ByteArray(32) { 2 }
        val key = MessageDigest.getInstance("SHA-256").digest("OTP Pager pad mix v1".toByteArray() + b + a)
        val expected = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16))) }.update(ByteArray(4096))
        assertArrayEquals(expected, stream(b, a))
        assertFalse(stream(b, a).contentEquals(stream(ByteArray(32) { 3 }, a)))
        assertFalse(stream(b, a).contentEquals(stream(b, ByteArray(32) { 3 })))
        assertFalse(stream(b, a).contentEquals(stream(a, b))) // Order is bound: peer seed first.
        // Even an all-zero (broken) host generator yields a pad that is not all zeros.
        assertTrue(stream(b, ByteArray(32)).any { it != 0.toByte() })
        try { CableProtocol.mixer(ByteArray(31), a); fail("Short seed accepted") } catch (_: IllegalArgumentException) {}
    }
}
