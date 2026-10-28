package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class ActiveManifestCodecTest {
    private val epoch = "a1".repeat(16)
    private val initial = "chat-" + epoch.take(20)
    private fun record(roots: List<String> = listOf(initial), sequence: Long = 1) =
        ActiveManifestCodec.record(epoch, "b2".repeat(16), "c3".repeat(32), "d4".repeat(32), "e5".repeat(32), sequence, roots)
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid manifest accepted") } catch (_: Exception) {} }
    private fun root(n: Int) = "chat-" + "%020x".format(n)

    @Test fun canonicalRoundTripSortsRootsAndDerivesEpochNamespaces() {
        val r = record(listOf(root(9), initial, root(3)), sequence = 3)
        assertEquals(listOf(root(3), root(9), initial).sorted(), r.roots)
        val payload = ActiveManifestCodec.encode(r)
        assertEquals(ActiveManifestCodec.HEADER_BYTES + 30, payload.size)
        assertEquals(r, ActiveManifestCodec.decode(payload))
        assertEquals("enr-" + epoch.take(20), r.index); assertEquals(r.index + "-claims", r.claims)
        assertTrue(r.claims.length <= 40) // Fits the existing SecureStore namespace limit.
        assertEquals(initial, r.initialRoot)
        val max = record(listOf(initial) + (1 until ActiveManifestCodec.MAX_ROOTS).map(::root), sequence = 0xFFFF_FFFFL)
        assertEquals(max, ActiveManifestCodec.decode(ActiveManifestCodec.encode(max)))
    }

    @Test fun everyTruncationExtensionAndFormatChangeFailsClosed() {
        val bytes = ActiveManifestCodec.encode(record(listOf(initial, root(1))))
        for (n in 0 until bytes.size) rejected { ActiveManifestCodec.decode(bytes.copyOf(n)) }
        rejected { ActiveManifestCodec.decode(bytes + ByteArray(10)) }
        rejected { ActiveManifestCodec.decode(bytes + byteArrayOf(0)) }
        rejected { ActiveManifestCodec.decode(bytes.copyOf().also { it[7] = '2'.code.toByte() }) }
        rejected { ActiveManifestCodec.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }) }
    }

    @Test fun rootListMustBeStrictlyAscendingUniqueCountedAndIncludeInitialRoot() {
        val bytes = ActiveManifestCodec.encode(record(listOf(initial, root(1))))
        val first = ActiveManifestCodec.HEADER_BYTES
        // Swap the two encoded roots: same set, noncanonical order.
        val swapped = bytes.copyOf().also {
            val a = bytes.copyOfRange(first, first + 10); val b = bytes.copyOfRange(first + 10, first + 20)
            b.copyInto(it, first); a.copyInto(it, first + 10)
        }
        rejected { ActiveManifestCodec.decode(swapped) }
        val duplicate = bytes.copyOf().also { bytes.copyOfRange(first, first + 10).copyInto(it, first + 10) }
        rejected { ActiveManifestCodec.decode(duplicate) }
        val miscounted = bytes.copyOf().also { it[first - 1] = 3 }
        rejected { ActiveManifestCodec.decode(miscounted) }
        val zeroCount = bytes.copyOfRange(0, first).also { it[first - 1] = 0 }
        rejected { ActiveManifestCodec.decode(zeroCount) }
        rejected { record(listOf(root(1))) } // Initial root missing.
        rejected { record(emptyList()) }
        rejected { record(listOf(initial, initial)) }
        rejected { record(listOf(initial, "chat-" + "G".repeat(20))) }
        rejected { record(listOf(initial, "station-v2")) }
        rejected { record(listOf(initial) + (1..ActiveManifestCodec.MAX_ROOTS).map(::root)) }
    }

    @Test fun commitmentsAndSequenceCannotBeZeroOrMalformed() {
        val zero = "0".repeat(64)
        rejected { ActiveManifestCodec.record(epoch, "b2".repeat(16), zero, "d4".repeat(32), "e5".repeat(32), 1, listOf(initial)) }
        rejected { ActiveManifestCodec.record(epoch, "b2".repeat(16), "c3".repeat(32), zero, "e5".repeat(32), 1, listOf(initial)) }
        rejected { ActiveManifestCodec.record(epoch, "b2".repeat(16), "c3".repeat(32), "d4".repeat(32), zero, 1, listOf(initial)) }
        rejected { ActiveManifestCodec.record("A1".repeat(16), "b2".repeat(16), "c3".repeat(32), "d4".repeat(32), "e5".repeat(32), 1, listOf("chat-" + "A1".repeat(10))) }
        rejected { ActiveManifestCodec.record(epoch, "short", "c3".repeat(32), "d4".repeat(32), "e5".repeat(32), 1, listOf(initial)) }
        rejected { record(sequence = 0) }
        rejected { record(sequence = 0x1_0000_0000L) }
        val bytes = ActiveManifestCodec.encode(record())
        val zeroSequence = bytes.copyOf().also { for (i in 136 until 140) it[i] = 0 }
        rejected { ActiveManifestCodec.decode(zeroSequence) }
    }

    @Test fun appendAddsOneRootAtTheNextSequenceAndNeverRemoves() {
        val r = record()
        val next = ActiveManifestCodec.appended(r, root(7))
        assertEquals(2L, next.sequence); assertTrue(root(7) in next.roots); assertTrue(initial in next.roots)
        rejected { ActiveManifestCodec.appended(next, root(7)) }
        rejected { ActiveManifestCodec.appended(next, "chat-index") }
        val full = record(listOf(initial) + (1 until ActiveManifestCodec.MAX_ROOTS).map(::root))
        rejected { ActiveManifestCodec.appended(full, root(100000)) }
    }
}
