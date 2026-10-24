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
    @Test fun retirementKeepsIdentityAndUnspentDirectionalSlotsButRejectsZeroKeyForgery() {
        val mutable = pad.clone(); val identity = PadProtocol.id(mutable)
        val old = PadProtocol.seal(mutable, 'A', 1, "old", identity).second
        val later = PadProtocol.seal(mutable, 'A', 2, "later", identity).second
        val opposite = PadProtocol.seal(mutable, 'B', 1, "opposite", identity).second
        PadProtocol.retire(mutable, 'A', 1)
        assertTrue(mutable.take(PadProtocol.STRIDE).all { it == 0.toByte() })
        assertFalse(identity.contentEquals(PadProtocol.id(mutable)))
        rejects { PadProtocol.open(mutable, old, 'A', identity) }
        rejects { PadProtocol.seal(mutable, 'A', 1, "reuse", identity) }
        assertEquals("later", PadProtocol.open(mutable, later, 'A', identity).payload)
        assertEquals("opposite", PadProtocol.open(mutable, opposite, 'B', identity).payload)
        val forged = Base64.getDecoder().decode(old)
        val tag = PadProtocol.authenticate(ByteArray(32), forged.copyOfRange(0, 26 + PadProtocol.BLOCK))
        tag.copyInto(forged, 26 + PadProtocol.BLOCK)
        rejects { PadProtocol.open(mutable, Base64.getEncoder().encodeToString(forged), 'A', identity) }
        assertEquals(PadProtocol.messageId(pad, 'A', 2), PadProtocol.open(mutable, later, 'A', identity).id)
    }
    @Test fun largerPadsKeepDirectionsSeparateAndEnforceTheirCapacity() {
        for (size in listOf(2, 4)) {
            val material = ByteArray(size * PadProtocol.MATERIAL_BYTES) { (it * 13 + 7).toByte() }
            val capacity = PadProtocol.slots(material)
            assertEquals(size * PadProtocol.MATERIAL_BYTES / 2 / PadProtocol.STRIDE, capacity)
            val identity = PadProtocol.id(material)
            val message = PadProtocol.seal(material, 'B', capacity.toLong(), "large pad", identity)
            assertEquals("large pad", PadProtocol.open(material, message.second, 'B', identity).payload)
            rejects { PadProtocol.seal(material, 'B', capacity + 1L, "overflow", identity) }
            PadProtocol.retire(material, 'A', capacity.toLong())
            assertEquals("large pad", PadProtocol.open(material, message.second, 'B', identity).payload)
            val binding = ByteArray(32) { 4 }
            assertArrayEquals(material, PadProtocol.material(PadProtocol.bundle(material, binding), binding))
        }
        rejects { PadProtocol.generate(ByteArray(32), 3 * PadProtocol.MATERIAL_BYTES) }
    }
    @Test fun bundleMustMatchSizeVersionAndPairing() {
        val binding = ByteArray(32) { it.toByte() }; val bundle = PadProtocol.generate(binding)
        assertEquals(PadProtocol.MATERIAL_BYTES, PadProtocol.material(bundle, binding).size)
        rejects { PadProtocol.material(bundle, ByteArray(32)) }; rejects { PadProtocol.material(bundle.copyOf(bundle.size - 1), binding) }
        bundle[0] = 0; rejects { PadProtocol.material(bundle, binding) }
    }
}
