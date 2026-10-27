package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class EnrollmentStageCodecTest {
    private fun record() = EnrollmentStageCodec.Record("a".repeat(32), "b".repeat(32), "c".repeat(64), EnrollmentStageCodec.Phase.PREPARING)
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid staging record accepted") } catch (_: Exception) {} }
    @Test fun fixedCanonicalPhasesRoundTripAndNamespacesFitExistingVaultLimits() {
        for (phase in EnrollmentStageCodec.Phase.entries) {
            var r = record().copy(phase = phase)
            if (phase == EnrollmentStageCodec.Phase.PREPARED) r = r.copy(indexHash = "d".repeat(64), claimsHash = "e".repeat(64))
            val payload = EnrollmentStageCodec.encode(r)
            assertEquals(137, payload.size); assertEquals(r, EnrollmentStageCodec.decode(payload))
            assertTrue(r.index.length <= 40); assertTrue(r.claims.length <= 40)
            assertTrue(Regex("chat-[a-f0-9]{20}").matches(r.root))
        }
    }
    @Test fun everyTruncationExtensionsUnknownPhaseAndVersionFailClosed() {
        val bytes = EnrollmentStageCodec.encode(record())
        for (n in 0 until bytes.size) rejected { EnrollmentStageCodec.decode(bytes.copyOf(n)) }
        rejected { EnrollmentStageCodec.decode(bytes + byteArrayOf(0)) }
        rejected { EnrollmentStageCodec.decode(bytes.copyOf().also { it[8] = 3 }) } // No ACTIVE phase.
        rejected { EnrollmentStageCodec.decode(bytes.copyOf().also { it[7] = '2'.code.toByte() }) }
    }
    @Test fun commitmentsAndPhaseSpecificFieldsCannotBeOmittedOrCoerced() {
        val r = record()
        rejected { EnrollmentStageCodec.encode(r.copy(epoch = "X".repeat(32))) }
        rejected { EnrollmentStageCodec.encode(r.copy(terminalGeneration = "bad")) }
        rejected { EnrollmentStageCodec.encode(r.copy(terminalCommitment = EnrollmentStageCodec.zeroHash)) }
        rejected { EnrollmentStageCodec.encode(r.copy(indexHash = "d".repeat(64))) }
        rejected { EnrollmentStageCodec.encode(r.copy(phase = EnrollmentStageCodec.Phase.PREPARED)) }
    }
}
