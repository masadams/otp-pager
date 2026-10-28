package org.example.otppager

import org.example.otppager.RetirementEvidenceCodec.Guard
import org.example.otppager.RetirementEvidenceCodec.Phase
import org.junit.Assert.*
import org.junit.Test

class EpochTerminalCodecTest {
    private val epoch = "e".repeat(32)
    private val generation = "1".repeat(32)
    private val initial = "chat-" + epoch.take(20)
    private val other = "chat-" + "a".repeat(20)
    private val claims = Guard("enr-" + epoch.take(20) + "-claims", "c".repeat(64))
    private fun ledger(root: String) = Guard("$root-pads", "d".repeat(64))
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid epoch terminal accepted") } catch (_: Exception) {} }

    @Test fun phasesRoundTripCanonicallyAndBindTheEpoch() {
        val intent = EpochTerminalCodec.record(generation, epoch, Phase.INTENT, emptySet(), emptyList())
        assertEquals(intent, EpochTerminalCodec.decode(EpochTerminalCodec.encode(intent)))
        for (phase in listOf(Phase.SEALED, Phase.CLEANED)) {
            val r = EpochTerminalCodec.record(generation, epoch, phase, listOf(other, initial), listOf(ledger(initial), claims))
            val bytes = EpochTerminalCodec.encode(r)
            assertEquals(r, EpochTerminalCodec.decode(bytes))
            assertEquals(listOf(other, initial).sorted(), r.roots.toList())
            for (n in 0 until bytes.size) rejected { EpochTerminalCodec.decode(bytes.copyOf(n)) }
            rejected { EpochTerminalCodec.decode(bytes + byteArrayOf(0)) }
            rejected { EpochTerminalCodec.decode(bytes.copyOf().also { it[7] = '1'.code.toByte() }) } // OTPTERM1 is not OTPTERM2.
        }
    }

    @Test fun scopeIsConfinedToTheEpochsOwnRootsClaimsAndLedgers() {
        rejected { EpochTerminalCodec.record(generation, epoch, Phase.INTENT, setOf(initial), emptyList()) }
        rejected { EpochTerminalCodec.record(generation, epoch, Phase.SEALED, setOf(other), listOf(claims)) } // Initial root missing.
        rejected { EpochTerminalCodec.record(generation, epoch, Phase.SEALED, setOf(initial), emptyList()) } // Claims missing.
        rejected { EpochTerminalCodec.record(generation, epoch, Phase.SEALED, setOf(initial),
            listOf(Guard("chat-index-claims", "c".repeat(64)))) }
        rejected { EpochTerminalCodec.record(generation, epoch, Phase.SEALED, setOf(initial), listOf(claims, ledger(other))) }
        rejected { EpochTerminalCodec.record(generation, epoch, Phase.SEALED, setOf(initial, "station-v2"), listOf(claims)) }
        rejected { EpochTerminalCodec.record(generation, epoch, Phase.SEALED, setOf(initial),
            listOf(claims, Guard("enr-" + "f".repeat(20) + "-claims", "c".repeat(64)))) }
        rejected { EpochTerminalCodec.record(generation, epoch, Phase.SEALED, setOf(initial), listOf(claims, claims)) }
        rejected { EpochTerminalCodec.record(epoch, epoch, Phase.INTENT, emptySet(), emptyList()) }
        rejected { EpochTerminalCodec.record("XYZ", epoch, Phase.INTENT, emptySet(), emptyList()) }
    }
}
