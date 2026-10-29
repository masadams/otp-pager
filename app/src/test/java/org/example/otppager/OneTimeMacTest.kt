package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class OneTimeMacTest {
    private fun hex(s: String) = PadProtocol.hexBytes(s)
    private fun rejected(block: () -> Unit) { try { block(); fail("Accepted") } catch (_: Exception) {} }
    @Test fun poly1305MatchesRfc8439() {
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", PadProtocol.hex(PadProtocol.oneTimeTag(key, "Cryptographic Forum Research Group".toByteArray())))
    }
    @Test fun oneTimeSlotsRoundTripAndNeverAcceptTheOtherMode() {
        val slot = ByteArray(PadProtocol.STRIDE).also(SecureRandom()::nextBytes); val id = ByteArray(16) { 3 }
        val (messageId, wire) = PadProtocol.sealSlot(slot, 'A', 5, "hello", id, oneTime = true)
        assertEquals('A', PadProtocol.role(wire))
        val opened = PadProtocol.openSlot(slot, wire, 'A', id, oneTime = true)
        assertEquals("hello", opened.payload); assertEquals(messageId, opened.id); assertEquals(5, opened.counter)
        // A one-time-MAC chat refuses HMAC packets and vice versa: no downgrade or confusion.
        val (_, legacy) = PadProtocol.sealSlot(slot, 'A', 5, "hello", id)
        rejected { PadProtocol.openSlot(slot, legacy, 'A', id, oneTime = true) }
        rejected { PadProtocol.openSlot(slot, wire, 'A', id, oneTime = false) }
    }
    @Test fun anyChangedByteFailsTheTag() {
        val slot = ByteArray(PadProtocol.STRIDE).also(SecureRandom()::nextBytes); val id = ByteArray(16) { 3 }
        val bytes = java.util.Base64.getDecoder().decode(PadProtocol.sealSlot(slot, 'B', 1, "meet at 9", id, oneTime = true).second)
        for (at in listOf(30, 26 + PadProtocol.BLOCK - 1, bytes.size - 1)) {
            val changed = bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            rejected { PadProtocol.openSlot(slot, java.util.Base64.getEncoder().encodeToString(changed), 'B', id, oneTime = true) }
        }
    }
}
