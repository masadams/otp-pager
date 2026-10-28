package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class PadMaterialTest {
    private val root = "chat-" + "ab".repeat(10)
    private fun rejected(action: () -> Unit) { try { action(); fail("Unsafe pad storage accepted") } catch (_: Exception) {} }
    private fun fixture(size: Int = PadProtocol.MATERIAL_BYTES): Triple<File, ByteArray, String> {
        val base = Files.createTempDirectory("padmat").toFile()
        val pad = PadProtocol.material(PadProtocol.generate(ByteArray(32) { 1 }, size), ByteArray(32) { 1 })
        return Triple(base, pad, PadProtocol.hex(PadProtocol.id(pad)))
    }
    private fun legacySlot(pad: ByteArray, role: Char, counter: Long): ByteArray {
        val at = (if (role == 'A') 0 else pad.size / 2) + (counter.toInt() - 1) * PadProtocol.STRIDE
        return pad.copyOfRange(at, at + PadProtocol.STRIDE)
    }

    @Test fun everySlotRoundTripsAndWireFormatMatchesTheInVaultPad() {
        for (size in listOf(1, 2, 4).map { it * PadProtocol.MATERIAL_BYTES }) {
            val (base, pad, id) = fixture(size)
            try {
                val file = PadMaterial.file(base, root, id)
                val keys = PadMaterial.write(file, id, pad)
                val slots = PadProtocol.slots(pad)
                assertEquals(slots, PadMaterial.slots(keys))
                assertEquals(PadMaterial.HEADER + 2L * slots * PadMaterial.RECORD, file.length())
                for (role in listOf('A', 'B')) for (counter in listOf(1L, 2L, slots.toLong()))
                    assertArrayEquals(legacySlot(pad, role, counter), PadMaterial.slot(file, id, keys, role, counter))
                // Same message id and ciphertext as the whole-pad path; either side can open the other's packet.
                val stable = PadProtocol.id(pad)
                val whole = PadProtocol.seal(pad, 'A', 3, "hello", stable)
                val slot = PadMaterial.slot(file, id, keys, 'A', 3)
                assertEquals(whole, PadProtocol.sealSlot(slot, 'A', 3, "hello", stable))
                assertEquals(3L, PadProtocol.counter(whole.second, 'A', stable))
                assertEquals("hello", PadProtocol.openSlot(slot, whole.second, 'A', stable).payload)
                assertEquals(PadProtocol.open(pad, whole.second, 'A', stable), PadProtocol.openSlot(slot, whole.second, 'A', stable))
                // No plaintext pad bytes appear in the material file.
                val bytes = file.readBytes(); val probe = legacySlot(pad, 'B', 5).copyOf(32)
                assertFalse((0..bytes.size - 32).any { i -> (0 until 32).all { bytes[i + it] == probe[it] } })
            } finally { base.deleteRecursively() }
        }
    }

    @Test fun retiredSlotsHaveNoKeyAndConsumedKeysCannotBeRecovered() {
        val (base, pad, id) = fixture()
        try {
            pad.fill(0, 0, PadProtocol.STRIDE) // A1 already used before migration.
            val file = PadMaterial.file(base, root, id)
            val keys = PadMaterial.write(file, id, pad)
            assertFalse(PadMaterial.available(keys, 'A', 1)); assertTrue(PadMaterial.available(keys, 'A', 2))
            rejected { PadMaterial.slot(file, id, keys, 'A', 1) }
            assertEquals(PadProtocol.SLOTS - 1, PadMaterial.remaining(keys, 'A'))
            assertTrue(PadMaterial.retire(keys, 'B', 7)); assertFalse(PadMaterial.retire(keys, 'B', 7))
            rejected { PadMaterial.slot(file, id, keys, 'B', 7) }
            assertTrue(PadMaterial.available(keys, 'B', 6) && PadMaterial.available(keys, 'B', 8))
            rejected { PadMaterial.slot(file, id, keys, 'A', 0) }
            rejected { PadMaterial.slot(file, id, keys, 'A', PadProtocol.SLOTS + 1L) }
            rejected { PadProtocol.sealSlot(ByteArray(PadProtocol.STRIDE), 'A', 2, "x", PadProtocol.id(pad)) }
        } finally { base.deleteRecursively() }
    }

    @Test fun recordsAreBoundToTheirPadPositionAndContent() {
        val (base, pad, id) = fixture()
        try {
            val file = PadMaterial.file(base, root, id)
            val keys = PadMaterial.write(file, id, pad)
            // Swap two records: authenticated position binding rejects both, even with valid keys.
            val swapped = keys.copyOf().also { k -> k.copyOfRange(0, 32).copyInto(k, 32); keys.copyOfRange(32, 64).copyInto(k, 0) }
            rejected { PadMaterial.slot(file, id, swapped, 'A', 1) }
            RandomAccessFile(file, "rw").use { f ->
                val a = ByteArray(PadMaterial.RECORD); val b = ByteArray(PadMaterial.RECORD)
                f.seek(PadMaterial.HEADER.toLong()); f.readFully(a); f.readFully(b)
                f.seek(PadMaterial.HEADER.toLong()); f.write(b); f.write(a)
            }
            rejected { PadMaterial.slot(file, id, keys, 'A', 1) }
            rejected { PadMaterial.slot(file, id, swapped, 'A', 1) } // Keys follow records, AAD still names slot 0.
            rejected { PadMaterial.verify(file, id, keys) }
            // A flipped bit anywhere in a record fails; the header binds the pad id and slot count.
            val (base2, pad2, id2) = fixture()
            try {
                val other = PadMaterial.file(base2, root, id2); val k2 = PadMaterial.write(other, id2, pad2)
                RandomAccessFile(other, "rw").use { f -> f.seek(PadMaterial.HEADER + 100L); val b = f.read(); f.seek(PadMaterial.HEADER + 100L); f.write(b xor 1) }
                rejected { PadMaterial.slot(other, id2, k2, 'A', 1) }
                assertNotNull(PadMaterial.slot(other, id2, k2, 'A', 2))
                rejected { PadMaterial.verify(other, id, k2) }
                rejected { PadMaterial.verify(other, id2, k2.copyOf(k2.size - 64)) }
            } finally { base2.deleteRecursively() }
        } finally { base.deleteRecursively() }
    }

    @Test fun pruneAndDeleteAllRemoveOnlyWhatTheyShould() {
        val (base, pad, id) = fixture()
        try {
            val keep = PadMaterial.file(base, root, id); PadMaterial.write(keep, id, pad)
            val stale = PadMaterial.file(base, root, "cd".repeat(16)); stale.writeBytes(ByteArray(3))
            val partial = File(keep.parentFile, keep.name + ".new"); partial.writeBytes(ByteArray(3))
            val neighbor = PadMaterial.file(base, "chat-" + "ef".repeat(10), id); neighbor.parentFile!!.mkdirs(); neighbor.writeBytes(ByteArray(1))
            PadMaterial.prune(base, root, setOf(id))
            assertTrue(keep.exists()); assertFalse(stale.exists()); assertFalse(partial.exists()); assertTrue(neighbor.exists())
            assertFalse(PadMaterial.gone(base, root))
            PadMaterial.deleteAll(base, root)
            assertTrue(PadMaterial.gone(base, root)); assertTrue(neighbor.exists())
            PadMaterial.deleteAll(base, root) // Idempotent.
            rejected { PadMaterial.file(base, "../x", id) }
            rejected { PadMaterial.file(base, root, "../../" + id) }
            rejected { PadMaterial.write(File(keep.parentFile, "other.bin"), id, pad) }
        } finally { base.deleteRecursively() }
    }
    @Test fun streamedWritesInAnyChunkSizeMatchTheWholePadAndFailClosedOnLength() {
        val (base, pad, id) = fixture(2 * PadProtocol.MATERIAL_BYTES)
        try {
            val dir = PadMaterial.directory(base, root)
            for (chunk in listOf(1, 4127, 4129, 65536, pad.size)) {
                val installed = PadMaterial.Writer(dir, pad.size).use { w ->
                    var at = 0
                    while (at < pad.size) { val n = minOf(chunk, pad.size - at); w.write(pad, at, n); at += n }
                    w.finish()
                }
                assertEquals(id, installed.padId)
                assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(pad), installed.digest)
                for (role in listOf('A', 'B')) for (counter in listOf(1L, 77L, 254L))
                    assertArrayEquals(legacySlot(pad, role, counter), PadMaterial.slot(installed.file, id, installed.keys, role, counter))
                installed.file.delete()
            }
            // Short, long and abandoned streams leave no material behind.
            PadMaterial.Writer(dir, pad.size).use { w -> w.write(pad, 0, 100); rejected { w.finish() } }
            PadMaterial.Writer(dir, pad.size).use { w -> w.write(pad); rejected { w.write(ByteArray(1)) } }
            PadMaterial.Writer(dir, pad.size).use { w -> w.write(pad, 0, 5000) }
            assertTrue(dir.listFiles()!!.none { it.name.startsWith("pending-") })
            rejected { PadMaterial.Writer(dir, 3 * PadProtocol.MATERIAL_BYTES) }
            // Two files of the same pad never share record binding (fresh nonce and keys each time).
            val a = PadMaterial.Writer(dir, pad.size).use { it.write(pad); it.finish() }
            val aBytes = a.file.readBytes(); a.file.delete()
            val b = PadMaterial.Writer(dir, pad.size).use { it.write(pad); it.finish() }
            rejected { PadMaterial.slot(b.file, id, a.keys, 'A', 1) }
            assertFalse(aBytes.copyOfRange(8, 24).contentEquals(b.file.readBytes().copyOfRange(8, 24)))
        } finally { base.deleteRecursively() }
    }
}
