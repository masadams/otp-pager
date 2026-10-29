package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Properties

class PqSignatureTest {
    private val v = Properties().apply { PqSignatureTest::class.java.getResourceAsStream("/mldsa65.properties")!!.use { load(it) } }
    private val d = Base64.getDecoder(); private val e = Base64.getEncoder()

    @Test fun matchesNodeOpenSslBothWays() {
        val seed = PadProtocol.hexBytes(v.getProperty("seed")); val message = PadProtocol.hexBytes(v.getProperty("message"))
        val spki = d.decode(v.getProperty("spki"))
        assertArrayEquals(spki, PqSignature.publicKey(seed)) // Same seed, same key as OpenSSL.
        assertTrue(PqSignature.verify(spki, message, d.decode(v.getProperty("node_signature"))))
        assertTrue(PqSignature.verify(spki, message, d.decode(v.getProperty("bc_signature"))))
        val ours = PqSignature.sign(seed, message)
        assertEquals(PqSignature.SIGNATURE_BYTES, ours.size)
        assertTrue(PqSignature.verify(spki, message, ours))
    }

    @Test fun rejectsTamperingWrongKeysAndBadEncodings() {
        val seed = PqSignature.newSeed(); val spki = PqSignature.publicKey(seed)
        val data = "OTPSTR01 frame".toByteArray(); val sig = PqSignature.sign(seed, data)
        assertFalse(PqSignature.verify(spki, data + 0, sig))
        assertFalse(PqSignature.verify(PqSignature.publicKey(PqSignature.newSeed()), data, sig))
        assertFalse(PqSignature.verify(spki, data, sig.copyOf(sig.size - 1)))
        assertFalse(PqSignature.verify(spki, data, sig.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }))
        assertFalse(PqSignature.verify(spki.copyOf(spki.size - 1), data, sig))
        assertFalse(PqSignature.verify(spki.copyOf().also { it[10] = 0 }, data, sig)) // Wrong algorithm OID.
        assertFalse(PqSignature.validKey(ByteArray(10)))
    }

    @Test fun relayKeyIsPinnedOnlyWithABindingFromThePinnedAuthority() {
        fun authority() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pinned = authority(); val card = e.encodeToString(pinned.public.encoded)
        val pq = PqSignature.publicKey(PqSignature.newSeed())
        fun bind(key: java.security.PrivateKey, spki: ByteArray) = e.encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(key); update("OTP Pager PQ authority v1\u0000".toByteArray(Charsets.ISO_8859_1) + spki); sign() })
        assertNull(StrictChat.pinnablePq(card, null))
        assertEquals(e.encodeToString(pq), StrictChat.pinnablePq(card, e.encodeToString(pq) to bind(pinned.private, pq)))
        // A key bound by another authority, a binding over another key, or a non-ML-DSA key is refused.
        assertThrows(IllegalArgumentException::class.java) { StrictChat.pinnablePq(card, e.encodeToString(pq) to bind(authority().private, pq)) }
        val other = PqSignature.publicKey(PqSignature.newSeed())
        assertThrows(IllegalArgumentException::class.java) { StrictChat.pinnablePq(card, e.encodeToString(other) to bind(pinned.private, pq)) }
        assertThrows(IllegalArgumentException::class.java) { StrictChat.pinnablePq(card, card to bind(pinned.private, pinned.public.encoded)) }
    }
}
