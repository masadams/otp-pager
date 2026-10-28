package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class ControlProtocolTest {
    private val v = java.util.Properties().apply { load(ControlProtocolTest::class.java.getResourceAsStream("/control-v1.properties")) }
    private fun hex(name: String) = v.getProperty(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid control frame accepted") } catch (_: Exception) {} }
    private val generation get() = hex("generation")

    @Test fun framesAndMailboxMatchTheReferenceImplementation() {
        val wipe = ControlProtocol.wipe(hex("kCtlAB"), ControlProtocol.Direction.A_TO_B, generation, hex("request"), v.getProperty("issued").toLong())
        assertEquals(v.getProperty("wipeAB"), PadProtocol.hex(wipe))
        assertEquals(ControlProtocol.WIPE_BYTES, wipe.size)
        val opened = ControlProtocol.openWipe(hex("kCtlAB"), wipe, ControlProtocol.Direction.A_TO_B, generation)
        assertArrayEquals(hex("request"), opened.request); assertEquals(v.getProperty("issued").toLong(), opened.issued)
        for ((name, outcome) in listOf("Received" to ControlProtocol.Outcome.RECEIVED, "Wiped" to ControlProtocol.Outcome.WIPED, "Incomplete" to ControlProtocol.Outcome.INCOMPLETE)) {
            val ack = ControlProtocol.ack(hex("kAckBA"), ControlProtocol.Direction.B_TO_A, generation, outcome)
            assertEquals(v.getProperty("ack$name"), PadProtocol.hex(ack))
            assertEquals(v.getProperty("ack${name}Sha256"), PadProtocol.hex(ControlProtocol.sha256(ack)))
            assertEquals(outcome, ControlProtocol.peekAck(ack).outcome)
        }
        assertEquals(v.getProperty("mailbox"), ControlProtocol.mailbox(hex("credential")))
    }

    @Test fun everyAlterationTruncationAndCrossUseIsRejected() {
        val key = hex("kCtlAB")
        val wipe = ControlProtocol.wipe(key, ControlProtocol.Direction.A_TO_B, generation, hex("request"), 1)
        for (i in wipe.indices) rejected {
            ControlProtocol.openWipe(key, wipe.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, ControlProtocol.Direction.A_TO_B, generation)
        }
        for (n in 0 until wipe.size) rejected { ControlProtocol.openWipe(key, wipe.copyOf(n), ControlProtocol.Direction.A_TO_B, generation) }
        rejected { ControlProtocol.openWipe(key, wipe + byteArrayOf(0), ControlProtocol.Direction.A_TO_B, generation) }
        rejected { ControlProtocol.openWipe(key, wipe, ControlProtocol.Direction.B_TO_A, generation) } // Reflection.
        rejected { ControlProtocol.openWipe(key, wipe, ControlProtocol.Direction.A_TO_B, ByteArray(16)) } // Other pairing.
        rejected { ControlProtocol.openWipe(hex("kAckBA"), wipe, ControlProtocol.Direction.A_TO_B, generation) } // Wrong key.
        val ack = ControlProtocol.ack(hex("kAckBA"), ControlProtocol.Direction.B_TO_A, generation, ControlProtocol.Outcome.WIPED)
        rejected { ControlProtocol.peekWipe(ack) }; rejected { ControlProtocol.peekAck(wipe) }
        rejected { ControlProtocol.peekAck(ack.copyOf().also { it[26] = 9 }) }
        rejected { ControlProtocol.peekAck(ack.copyOf().also { it[27] = 1 }) }
        rejected { ControlProtocol.wipe(ByteArray(31), ControlProtocol.Direction.A_TO_B, generation, hex("request"), 1) }
        rejected { ControlProtocol.mailbox(ByteArray(16)) }
        assertNotEquals(ControlProtocol.mailbox(hex("credential")), ControlProtocol.mailbox(hex("kCtlAB")))
    }
}
