package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class PostQuantumTest {
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid ML-KEM input accepted") } catch (_: Exception) {} }
    @Test fun encapsulationRoundTripsWithFips203Sizes() {
        val pair = PostQuantum.kemKeyPair()
        assertEquals(PostQuantum.KEM_PUBLIC_BYTES, pair.public.size)
        val sent = PostQuantum.encapsulate(pair.public)
        assertEquals(PostQuantum.KEM_CIPHERTEXT_BYTES, sent.ciphertext.size)
        assertArrayEquals(sent.secret, PostQuantum.decapsulate(pair, sent.ciphertext))
    }
    @Test fun freshEncapsulationsDiffer() {
        val pair = PostQuantum.kemKeyPair()
        assertFalse(PostQuantum.encapsulate(pair.public).secret.contentEquals(PostQuantum.encapsulate(pair.public).secret))
    }
    @Test fun tamperedCiphertextGivesADifferentSecret() {
        // FIPS 203 implicit rejection: no error, but an unrelated secret, so the channel key differs.
        val pair = PostQuantum.kemKeyPair(); val sent = PostQuantum.encapsulate(pair.public)
        val changed = sent.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(sent.secret.contentEquals(PostQuantum.decapsulate(pair, changed)))
    }
    @Test fun rejectsWrongSizes() {
        val pair = PostQuantum.kemKeyPair()
        rejected { PostQuantum.checkPublic(pair.public.copyOf(PostQuantum.KEM_PUBLIC_BYTES - 1)) }
        rejected { PostQuantum.encapsulate(ByteArray(10)) }
        rejected { PostQuantum.decapsulate(pair, ByteArray(PostQuantum.KEM_CIPHERTEXT_BYTES + 1)) }
    }
    @Test fun rejectsAnEncapsulationKeyOutsideTheModulus() {
        // FIPS 203 §7.2 input check: every 12-bit coefficient must be below q = 3329.
        rejected { PostQuantum.checkPublic(ByteArray(PostQuantum.KEM_PUBLIC_BYTES) { 0xff.toByte() }) }
    }
}
