package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class RetirementEvidenceCodecTest {
    private val a = "chat-" + "a".repeat(20)
    private val b = "chat-" + "b".repeat(20)
    private fun sealed() = RetirementEvidenceCodec.record("1".repeat(32), RetirementEvidenceCodec.Phase.SEALED,
        listOf(b, "station-v2", a), listOf(RetirementEvidenceCodec.Guard("$b-pads", "2".repeat(64)),
            RetirementEvidenceCodec.Guard("chat-index-claims", "3".repeat(64))))
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid record accepted") } catch (_: Exception) {} }
    @Test fun canonicalRoundTripAcrossPhasesAndInputOrdering() {
        val r = sealed(); val payload = RetirementEvidenceCodec.encode(r)
        assertEquals(r, RetirementEvidenceCodec.decode(payload))
        val shuffled = RetirementEvidenceCodec.record(r.generation, r.phase, r.roots.reversed(), r.guards.reversed())
        assertArrayEquals(payload, RetirementEvidenceCodec.encode(shuffled))
        for (phase in RetirementEvidenceCodec.Phase.entries) {
            val record = if (phase == RetirementEvidenceCodec.Phase.INTENT) RetirementEvidenceCodec.record(r.generation, phase, emptyList(), emptyList())
                else RetirementEvidenceCodec.record(r.generation, phase, r.roots, r.guards)
            assertEquals(record, RetirementEvidenceCodec.decode(RetirementEvidenceCodec.encode(record)))
        }
    }
    @Test fun everyTruncationTrailingDataUnknownVersionPhaseAndOversizeFailClosed() {
        val payload = RetirementEvidenceCodec.encode(sealed())
        for (length in 0 until payload.size) rejected { RetirementEvidenceCodec.decode(payload.copyOf(length)) }
        rejected { RetirementEvidenceCodec.decode(payload + byteArrayOf(0)) }
        rejected { RetirementEvidenceCodec.decode(payload.copyOf().also { it[7] = '2'.code.toByte() }) }
        rejected { RetirementEvidenceCodec.decode(payload.copyOf().also { it[8] = 3 }) } // No ACTIVE encoding.
        rejected { RetirementEvidenceCodec.decode(ByteArray(RetirementEvidenceCodec.MAX_BYTES)) }
        rejected { RetirementEvidenceCodec.decode(payload.copyOf().also { it[25] = 0x7f }) } // Oversized root count.
    }
    @Test fun unsupportedNamespacesDuplicatesMissingClaimsAndIntentWithScopeAreRejected() {
        val r = sealed()
        rejected { RetirementEvidenceCodec.record(r.generation, r.phase, listOf(a, a), r.guards) }
        rejected { RetirementEvidenceCodec.record(r.generation, r.phase, r.roots, r.guards + r.guards[0]) }
        rejected { RetirementEvidenceCodec.record(r.generation, r.phase, r.roots, listOf(RetirementEvidenceCodec.Guard("chat-index", "0".repeat(64)))) }
        rejected { RetirementEvidenceCodec.record(r.generation, r.phase, r.roots, emptyList()) }
        rejected { RetirementEvidenceCodec.record(r.generation, r.phase, listOf(a), r.guards) }
        rejected { RetirementEvidenceCodec.record(r.generation, RetirementEvidenceCodec.Phase.INTENT, r.roots, emptyList()) }
        rejected { RetirementEvidenceCodec.record("X".repeat(32), r.phase, r.roots, r.guards) }
        rejected { RetirementEvidenceCodec.record(r.generation, r.phase, r.roots, listOf(RetirementEvidenceCodec.Guard("chat-index-claims", "bad"))) }
    }
    @Test fun noncanonicalOrderingAndNonAsciiDoNotDecodeAsAlternativeRecords() {
        val r = RetirementEvidenceCodec.record("0".repeat(32), RetirementEvidenceCodec.Phase.SEALED,
            listOf(a, b, "station-v2"), listOf(RetirementEvidenceCodec.Guard("chat-index-claims", "0".repeat(64))))
        val payload = RetirementEvidenceCodec.encode(r)
        val first = 27 // magic+phase+generation+root count
        val width = 1 + a.length
        val reordered = payload.copyOf()
        payload.copyInto(reordered, first + width, first, first + width)
        payload.copyInto(reordered, first, first + width, first + 2 * width)
        rejected { RetirementEvidenceCodec.decode(reordered) }
        rejected { RetirementEvidenceCodec.decode(payload.copyOf().also { it[first + 1] = 0xff.toByte() }) }
    }
}
