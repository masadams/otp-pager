package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PadProtocolTest {
    private val pad = ByteArray(PadProtocol.MATERIAL_BYTES) { (it * 37 + it / 255).toByte() }
    private fun rejects(action: () -> Unit) { try { action(); fail("Accepted invalid data") } catch (_: IllegalArgumentException) {} }
    @Test fun hmacMatchesRfc4231CaseOne() {
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", PadProtocol.hex(PadProtocol.authenticate(ByteArray(20) { 0x0b }, "Hi There".toByteArray())))
    }
    @Test fun fixedSizeDirectionalSlotsAndExhaustion() {
        val (id, wire) = PadProtocol.seal(pad, 'A', 1, "Hello 世界")
        assertEquals("cb826c251bc79df335b2f00f4dbd81e35a1df011f552315040a8e9e56dc4ea5a", PadProtocol.hex(java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(wire)))) // independent Python hashlib/hmac/struct vector
        val opened = PadProtocol.open(pad, wire, 'A')
        assertEquals(id, opened.id); assertEquals("Hello 世界", opened.payload)
        assertEquals(wire.length, PadProtocol.seal(pad, 'B', 127, "x".repeat(4094)).second.length)
        assertNotEquals(wire, PadProtocol.seal(pad, 'B', 1, "Hello 世界").second)
        rejects { PadProtocol.open(pad, wire, 'B') }; rejects { PadProtocol.seal(pad, 'A', 0, "x") }
        rejects { PadProtocol.seal(pad, 'A', 128, "x") }; rejects { PadProtocol.seal(pad, 'A', 1, "x".repeat(4095)) }
        val wrong = pad.clone(); wrong[0] = (wrong[0].toInt() xor 1).toByte()
        rejects { PadProtocol.open(wrong, wire, 'A') }
    }
    @Test fun everyFrameByteIsAuthenticated() {
        val wire = PadProtocol.seal(pad, 'B', 2, "test").second
        val bytes = Base64.getDecoder().decode(wire)
        for (i in bytes.indices) {
            val altered = bytes.clone(); altered[i] = (altered[i].toInt() xor 1).toByte()
            rejects { PadProtocol.open(pad, Base64.getEncoder().encodeToString(altered), 'B') }
        }
    }
    @Test fun bundleMustMatchSizeVersionAndPairing() {
        val binding = ByteArray(32) { it.toByte() }; val bundle = PadProtocol.generate(binding)
        assertEquals(PadProtocol.MATERIAL_BYTES, PadProtocol.material(bundle, binding).size)
        rejects { PadProtocol.material(bundle, ByteArray(32)) }; rejects { PadProtocol.material(bundle.copyOf(bundle.size - 1), binding) }
        bundle[0] = 0; rejects { PadProtocol.material(bundle, binding) }
    }
}
