package org.example.otppager

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(AndroidJUnit4::class)
class RetainedGuardReaderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pad = "a".repeat(32)
    private val room = "b".repeat(64)
    private class Fixture(base: Context) : ContextWrapper(base) {
        val directory = File(base.cacheDir, "guard-reader-" + UUID.randomUUID()).apply { check(mkdir()) }
        val root = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
        val slots = mutableSetOf<String>()
        private val previousAliases = keys().aliases().toList().toSet()
        override fun getNoBackupFilesDir() = directory
        override fun getApplicationContext(): Context = this
        fun file(slot: String) = File(directory, "$slot.bin")
        fun freeze() { GlobalRetirement.marker(directory).writeText("fixture frozen; not an enrollment proof") }
        fun unfreezeForFixtureOnly() { GlobalRetirement.marker(directory).delete() }
        fun save(slot: String, value: JSONObject) {
            unfreezeForFixtureOnly(); slots.add(slot); SecureStore(this, slot).save(value); freeze()
        }
        fun reference(slot: String) = RetainedGuardReader.Reference(slot, hash(file(slot)))
        fun read(slot: String) = RetainedGuardReader.readFrozen(this, reference(slot))
        fun raw(slot: String, json: ByteArray) {
            // Test-only malformed plaintext creation under an EXISTING real vault key.
            val key = keys().getKey("otp-pager-$slot", null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD("OTP Pager local vault v2".toByteArray())
            file(slot).writeBytes(byteArrayOf(2) + cipher.iv + cipher.doFinal(json))
        }
        fun close() {
            for (slot in slots) {
                val alias = "otp-pager-$slot"
                if (alias !in previousAliases) keys().deleteEntry(alias)
            }
            directory.deleteRecursively()
        }
    }
    companion object {
        private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun hash(file: File) = PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    }
    private fun claims(root: String) = JSONObject().put("version", 2)
        .put("pads", JSONObject().put(pad, root)).put("rooms", JSONObject().put(room, root))
    private fun ledger(high: Long = 1) = JSONObject().put("version", 2).put("pads", JSONObject().put(pad, high))
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Unsafe guard accepted") } catch (_: Exception) {}
    }
    @Test fun frozenReaderDecryptsOnlyExpectedGuardsWithoutChangingFilesOrKeys() {
        val f = Fixture(context)
        try {
            f.save("chat-index-claims", claims(f.root)); f.save("${f.root}-pads", ledger())
            val before = f.directory.listFiles()!!.associate { it.name to it.readBytes() }
            val aliases = keys().aliases().toList().toSet()
            val c = f.read("chat-index-claims"); val l = f.read("${f.root}-pads")
            RetainedGuardReader.readCohortFrozen(f, listOf(c.reference, l.reference), setOf(f.root))
            val savedClaims = c.guard as RetainedGuardReader.Guard.Claims
            assertEquals(f.root, savedClaims.pads[pad])
            assertEquals(RetainedGuardReader.Reservation(1, PadProtocol.SLOTS),
                (l.guard as RetainedGuardReader.Guard.Ledger).reservations[pad])
            assertEquals(before.keys, f.directory.listFiles()!!.map { it.name }.toSet())
            before.forEach { (name, bytes) -> assertArrayEquals(bytes, File(f.directory, name).readBytes()) }
            assertEquals(aliases, keys().aliases().toList().toSet())
            rejected { SecureStore(f, f.root).load() } // Narrow adapter never opens ordinary storage.
            rejected { (savedClaims.pads as MutableMap<String, String>)["c".repeat(32)] = f.root }
        } finally { f.close() }
    }
    @Test fun activeAccessAndOperationalOrArbitraryNamespacesAreRejected() {
        val f = Fixture(context)
        try {
            f.save("${f.root}-pads", ledger()); val reference = f.reference("${f.root}-pads")
            f.unfreezeForFixtureOnly()
            rejected { RetainedGuardReader.readFrozen(f, reference) }
            for (name in listOf(f.root, "${f.root}-draft", "chat-index", "../chat-index-claims", "${f.root}-pads-extra", "station-v2", "chat-index-claims-device"))
                rejected { RetainedGuardReader.Reference(name, "0".repeat(64)) }
            rejected { RetainedGuardReader.Reference("${f.root}-pads", "not-a-commitment") }
        } finally { f.close() }
    }
    @Test fun missingFileMissingKeyAndCiphertextRestorationCannotBecomeEmptyState() {
        val f = Fixture(context)
        try {
            val slot = "${f.root}-pads"
            f.save(slot, ledger()); val old = f.file(slot).readBytes()
            f.save(slot, ledger(2)); val current = f.reference(slot)
            f.file(slot).writeBytes(old); rejected { RetainedGuardReader.readFrozen(f, current) }
            assertArrayEquals(old, f.file(slot).readBytes())
            val expected = f.reference(slot)
            f.file(slot).delete(); rejected { RetainedGuardReader.readFrozen(f, expected) }
            assertTrue(keys().containsAlias("otp-pager-$slot")); assertFalse(f.file(slot).exists())
            f.file(slot).writeBytes(old)
            keys().deleteEntry("otp-pager-$slot")
            rejected { RetainedGuardReader.readFrozen(f, expected) }
            assertFalse(keys().containsAlias("otp-pager-$slot")); assertArrayEquals(old, f.file(slot).readBytes())
            f.file(slot).delete(); rejected { RetainedGuardReader.readFrozen(f, expected) }
            assertFalse(f.file(slot).exists()); assertFalse(keys().containsAlias("otp-pager-$slot"))
        } finally { f.close() }
    }
    @Test fun sideVariantsDirectoriesSymlinksAndOversizedFilesAreNeverRepairedOrOpened() {
        val f = Fixture(context)
        try {
            val slot = "${f.root}-pads"; f.save(slot, ledger()); val reference = f.reference(slot)
            for (suffix in listOf(".bak", ".new")) {
                val variant = File(f.file(slot).path + suffix); variant.writeText("untrusted side variant")
                rejected { RetainedGuardReader.readFrozen(f, reference) }
                assertEquals("untrusted side variant", variant.readText()); variant.delete()
            }
            val ciphertext = f.file(slot).readBytes(); f.file(slot).delete(); assertTrue(f.file(slot).mkdir())
            rejected { RetainedGuardReader.readFrozen(f, reference) }; assertTrue(f.file(slot).isDirectory)
            f.file(slot).delete()
            val target = File(f.directory, "target").apply { writeBytes(ciphertext) }
            java.nio.file.Files.createSymbolicLink(f.file(slot).toPath(), target.toPath())
            rejected { RetainedGuardReader.readFrozen(f, reference) }; assertArrayEquals(ciphertext, target.readBytes())
            f.file(slot).delete(); f.file(slot).writeBytes(ByteArray(RetainedGuardReader.MAX_BYTES + 1))
            rejected { f.read(slot) }; assertEquals((RetainedGuardReader.MAX_BYTES + 1).toLong(), f.file(slot).length())
        } finally { f.close() }
    }
    @Test fun strictSchemasRejectDuplicatesCoercionsUnknownFieldsAndTrailingValues() {
        val f = Fixture(context)
        try {
            val slot = "${f.root}-pads"; f.save(slot, ledger())
            val invalid = listOf(
                "{\"version\":2,\"version\":2,\"pads\":{}}", "{\"version\":\"2\",\"pads\":{}}",
                "{\"version\":2,\"pads\":{},\"secret\":\"forbidden\"}", "{\"version\":2,\"pads\":{}} {}",
                "{\"version\":2,\"pads\":{\"$pad\":1,\"$pad\":2}}",
                "{\"version\":2,\"pads\":{\"$pad\":\"1\"}}", "{\"version\":2,\"pads\":{\"$pad\":1.0}}",
                "{\"version\":2,\"pads\":{\"$pad\":-1}}", "{\"version\":2,\"pads\":{\"$pad\":1e0}}",
                "{\"version\":2,\"pads\":{\"bad-id\":1}}", "{\"version\":2}",
                "{\"version\":2,\"pads\":{},\"capacities\":{\"$pad\":127}}",
                "{\"version\":2,\"pads\":{\"$pad\":128},\"capacities\":{\"$pad\":127}}",
                "{\"version\":2,\"pads\":{\"$pad\":0},\"capacities\":{\"$pad\":0}}",
                "{\"version\":2,\"pads\":{\"$pad\":999999999999999999999}}",
                "{\"version\":3,\"pads\":{}}", "{\"version\":2,\"pads\":[[]]}"
            )
            for (json in invalid) { f.raw(slot, json.toByteArray()); val before = f.file(slot).readBytes(); rejected { f.read(slot) }; assertArrayEquals(before, f.file(slot).readBytes()) }
            f.raw(slot, byteArrayOf(0xc3.toByte(), 0x28)); rejected { f.read(slot) }
        } finally { f.close() }
    }
    @Test fun claimsRejectInvalidOwnersMissingMapsDuplicateIdsAndNumericStrings() {
        val f = Fixture(context)
        try {
            val slot = "chat-index-claims"; f.save(slot, claims(f.root))
            val invalid = listOf(
                "{\"version\":2,\"pads\":{}}",
                "{\"version\":2,\"pads\":{},\"rooms\":{\"$room\":\"arbitrary-owner\"}}",
                "{\"version\":2,\"pads\":{\"$pad\":123},\"rooms\":{}}",
                "{\"version\":2,\"pads\":{},\"rooms\":{\"$room\":\"${f.root}\",\"$room\":\"station-v2\"}}",
                "{\"version\":2,\"pads\":{},\"rooms\":{\"bad-room\":\"${f.root}\"}}",
                "{\"version\":2,\"pads\":{},\"rooms\":{},\"capacities\":{}}"
            )
            for (json in invalid) { f.raw(slot, json.toByteArray()); rejected { f.read(slot) } }
        } finally { f.close() }
    }
    @Test fun authenticatedCiphertextTamperingAndCapacityLimitsFailClosed() {
        val f = Fixture(context)
        try {
            val slot = "${f.root}-pads"; f.save(slot, ledger())
            val bytes = f.file(slot).readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            f.file(slot).writeBytes(bytes); rejected { f.read(slot) } // Commitment matches tampered bytes; AEAD must still fail.
            val pads = JSONObject()
            repeat(RetainedGuardReader.MAX_ENTRIES) { pads.put(it.toString(16).padStart(32, '0'), 0) }
            f.save(slot, JSONObject().put("version", 2).put("pads", pads))
            assertEquals(RetainedGuardReader.MAX_ENTRIES, (f.read(slot).guard as RetainedGuardReader.Guard.Ledger).reservations.size)
            pads.put(RetainedGuardReader.MAX_ENTRIES.toString(16).padStart(32, '0'), 0)
            f.save(slot, JSONObject().put("version", 2).put("pads", pads)); rejected { f.read(slot) }
        } finally { f.close() }
    }
    @Test fun exactCiphertextByteBoundAcceptsValidGuardButNextByteIsRefused() {
        val f = Fixture(context)
        try {
            val slot = "${f.root}-pads"; f.save(slot, ledger())
            val json = "{\"version\":2,\"pads\":{}}"
            f.raw(slot, json.padEnd(RetainedGuardReader.MAX_BYTES - 29, ' ').toByteArray())
            assertEquals(RetainedGuardReader.MAX_BYTES.toLong(), f.file(slot).length())
            assertTrue((f.read(slot).guard as RetainedGuardReader.Guard.Ledger).reservations.isEmpty())
            f.file(slot).appendBytes(byteArrayOf(0))
            rejected { f.read(slot) }
        } finally { f.close() }
    }
    @Test fun cohortByteBudgetAppliesAcrossOtherwiseValidIndividualRecords() {
        val f = Fixture(context)
        try {
            f.save("chat-index-claims", JSONObject().put("version", 2).put("pads", JSONObject()).put("rooms", JSONObject()))
            val references = mutableListOf(f.reference("chat-index-claims"))
            val roots = (0..7).map { "chat-" + it.toString(16).padStart(20, '0') }.toSet()
            val json = "{\"version\":2,\"pads\":{}}".padEnd(RetainedGuardReader.MAX_BYTES - 29, ' ').toByteArray()
            for (root in roots) {
                val slot = "$root-pads"; f.save(slot, JSONObject().put("version", 2).put("pads", JSONObject()))
                f.raw(slot, json); references.add(f.reference(slot))
            }
            assertEquals(8, RetainedGuardReader.readCohortFrozen(f, references.dropLast(1), roots).size)
            rejected { RetainedGuardReader.readCohortFrozen(f, references, roots) }
        } finally { f.close() }
    }
    @Test fun cohortChecksRequireBothDirectionsOfPadOwnershipAndKnownRoots() {
        val f = Fixture(context)
        try {
            val slot = "${f.root}-pads"
            f.save("chat-index-claims", claims(f.root)); f.save(slot, ledger())
            val c = f.read("chat-index-claims"); val l = f.read(slot)
            rejected { RetainedGuardReader.readCohortFrozen(f, listOf(c.reference), setOf(f.root)) }
            rejected { RetainedGuardReader.readCohortFrozen(f, listOf(l.reference), setOf(f.root)) }
            rejected { RetainedGuardReader.readCohortFrozen(f, listOf(c.reference, l.reference, l.reference), setOf(f.root)) }
            rejected { RetainedGuardReader.readCohortFrozen(f, listOf(c.reference, l.reference), emptySet()) }
            f.save("chat-index-claims", JSONObject().put("version", 2).put("pads", JSONObject()).put("rooms", JSONObject()))
            rejected { RetainedGuardReader.readCohortFrozen(f, listOf(f.reference("chat-index-claims"), l.reference), setOf(f.root)) }
            f.save("chat-index-claims", claims("station-v2"))
            rejected { RetainedGuardReader.readCohortFrozen(f, listOf(f.reference("chat-index-claims"), l.reference), setOf(f.root, "station-v2")) }
        } finally { f.close() }
    }
}
