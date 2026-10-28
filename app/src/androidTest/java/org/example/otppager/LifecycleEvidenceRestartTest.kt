package org.example.otppager

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit

/** Exclusive host fixture on a cleared disposable emulator. No production reset or unfreeze API. */
@RunWith(AndroidJUnit4::class)
class LifecycleEvidenceRestartTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root(n: Int) = "chat-" + n.toString().padStart(20, '0')
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun rejected(action: () -> Unit) { try { action(); fail("Unsafe lifecycle transition allowed") } catch (_: Exception) {} }
    private fun freeze() = GlobalRetirement::class.java.getDeclaredMethod("freeze", Context::class.java)
        .apply { isAccessible = true }.invoke(GlobalRetirement, context)
    private fun seed(): JSONObject {
        assertFalse(GlobalRetirement.isFrozen(context.noBackupFilesDir))
        SecureStore(context, "chat-index").save(JSONObject().put("version", 3).put("active", root(41))
            .put("chats", JSONArray((41..42).map { n -> JSONObject().put("id", root(n)).put("entryId", root(n)).put("name", "Lifecycle fixture $n") })))
        val book = ChatBook(context)
        for (n in 41..42) {
            val engine = book.engine(root(n)); engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            if (n == 41) {
                engine.generatePad().fill(0)
                val ledger = File(context.noBackupFilesDir, "${root(41)}-pads.bin")
                File(context.noBackupFilesDir, "fixture-old-ledger.bin").writeBytes(ledger.readBytes())
                PadLedger(SecureStore(context, "${root(41)}-pads")).reserve(checkNotNull(engine.state).getString("padId"))
                File(context.noBackupFilesDir, "fixture-current-ledger.bin").writeBytes(ledger.readBytes())
            }
        }
        // Evidence discovery does not decrypt or require a healthy contact index.
        File(context.noBackupFilesDir, "chat-index.bin").writeText("corrupt index fixture")
        return JSONObject().put("root", root(41))
    }
    private fun localGone() {
        assertTrue(GlobalRetirement.isFrozen(context.noBackupFilesDir))
        for (n in 41..42) {
            for (suffix in listOf("", "-draft", "-device", "-draft-device")) assertFalse(keys().containsAlias("otp-pager-${root(n)}$suffix"))
            assertTrue(VaultKeys.all(root(n), keys().aliases().toList()).isEmpty()) // Every rotated generation.
        }
        assertFalse(keys().containsAlias("otp-pager-chat-index"))
        assertTrue(keys().containsAlias("otp-pager-${root(41)}-pads"))
        assertTrue(keys().containsAlias("otp-pager-chat-index-claims"))
        rejected { ChatBook(context) }; rejected { SecureStore(context, "chat-index-claims").forget() }
    }
    @Test fun durableEvidenceDoesNotInventProvenanceAfterRestartOrLoss() {
        val phase = InstrumentationRegistry.getArguments().getString("lifecyclePhase")
        val phases = setOf("sealed-seed", "sealed-verify", "restored", "key-only", "submit-seed", "submit-verify",
            "failure-seed", "failure-verify", "intent-seed", "intent-verify", "scope-seed", "scope-verify", "key-seed", "key-verify")
        assumeTrue("Exclusive host lifecycle fixture only", phase in phases)
        val dir = context.noBackupFilesDir
        val referenceFile = File(dir, "lifecycle-test-reference.json")
        when (phase) {
            "sealed-seed", "intent-seed", "scope-seed", "key-seed" -> {
                val ref = seed(); freeze()
                val ticket = RetirementEvidence.beginFresh(context)
                try {
                    if (phase !in setOf("intent-seed", "key-seed")) {
                        val snapshot = RetainedGuardInventory.captureFresh(context, setOf("station-v2", root(41), root(42)))
                        RetirementEvidence.seal(ticket, snapshot)
                        assertEquals(RetirementEvidenceCodec.Phase.SEALED, RetirementEvidence.read(dir).phase)
                        rejected { RetirementEvidence.seal(ticket, snapshot) }
                    }
                    if (phase == "intent-seed") {
                        val observed = RetainedGuardInventory.captureFresh(context, setOf("station-v2", root(41), root(42)))
                        RetirementEvidence.retireCaptureWindow(dir)
                        rejected { RetirementEvidence.seal(ticket, observed) }
                        assertEquals(RetirementEvidenceCodec.Phase.INTENT, RetirementEvidence.read(dir).phase)
                    }
                    if (phase == "key-seed") check(RetirementEvidence.marker(dir).delete())
                    ref.put("generation", ticket.generation)
                    referenceFile.writeText(ref.toString())
                    assertTrue(VaultKeys.all(root(41), keys().aliases().toList()).isNotEmpty())
                    assertFalse(GlobalRetirement.marker(dir).exists()) // Simulated death before ordinary cleanup journal.
                    if (phase == "scope-seed") DeviceIdentity("otp-pager-${root(43)}-device").card(true)
                } finally { RetirementEvidence.close(ticket) }
            }
            "sealed-verify", "submit-verify" -> {
                localGone()
                val record = RetirementEvidence.verifyCompleted(context)
                assertEquals(JSONObject(referenceFile.readText()).getString("generation"), record.generation)
                assertEquals(RetirementEvidenceCodec.Phase.CLEANED, record.phase)
                val before = RetirementEvidence.marker(dir).readBytes()
                assertTrue(GlobalRetirement.retryExisting(context).get(30, TimeUnit.SECONDS).completed)
                assertArrayEquals(before, RetirementEvidence.marker(dir).readBytes())
                rejected { RetirementEvidence.beginFresh(context) }
                val forged = RetirementEvidence.Ticket(context, record.generation)
                rejected { RetirementEvidence.seal(forged, RetainedGuardInventory.Snapshot(record.roots, record.guards)) }
                if (phase == "sealed-verify") {
                    val journal = GlobalRetirement.marker(dir); val journalBytes = journal.readBytes()
                    val marker = VaultLifecycle.marker(dir, root(42)); val markerBytes = marker.readBytes()
                    val underdeclared = JSONObject(journal.readText()).put("roots", JSONArray(record.roots.filter { it != root(42) }.sorted()))
                    journal.writeText(underdeclared.toString()); check(marker.delete())
                    rejected { RetirementEvidence.verifyCompleted(context) }
                    assertEquals(GlobalRetirement.LocalState.UNAVAILABLE, GlobalRetirement.inspect(context).state)
                    journal.writeBytes(journalBytes); marker.writeBytes(markerBytes)
                    RetirementEvidence.verifyCompleted(context)
                    File(dir, "${root(41)}-pads.bin").writeBytes(File(dir, "fixture-old-ledger.bin").readBytes())
                }
            }
            "restored" -> {
                localGone()
                val before = RetirementEvidence.marker(dir).readBytes()
                rejected { RetirementEvidence.verifyCompleted(context) }
                assertEquals(GlobalRetirement.LocalState.UNAVAILABLE, GlobalRetirement.inspect(context).state)
                assertTrue(GlobalRetirement.retryExisting(context).get(30, TimeUnit.SECONDS).completed)
                assertArrayEquals(before, RetirementEvidence.marker(dir).readBytes()) // Never bless the older guard hash.
                File(dir, "${root(41)}-pads.bin").writeBytes(File(dir, "fixture-current-ledger.bin").readBytes())
                // Leave only the non-exportable key; no marker survives into the next process.
                VaultLifecycle.variants(GlobalRetirement.marker(dir)).forEach { check(!it.exists() || it.delete()) }
                VaultLifecycle.variants(RetirementEvidence.marker(dir)).forEach { check(!it.exists() || it.delete()) }
                assertTrue(keys().containsAlias(RetirementEvidence.alias(dir)))
            }
            "key-only", "key-verify" -> {
                localGone()
                assertEquals(RetirementEvidence.Presence.PRESENT, RetirementEvidence.probe(dir))
                assertFalse(RetirementEvidence.marker(dir).exists())
                assertTrue(keys().containsAlias(RetirementEvidence.alias(dir)))
                rejected { RetirementEvidence.read(dir) }; rejected { RetirementEvidence.beginFresh(context) }
                assertFalse(RetirementEvidence.marker(dir).exists())
                assertEquals(GlobalRetirement.LocalState.UNAVAILABLE, GlobalRetirement.inspect(context).state)
            }
            "submit-seed", "failure-seed" -> {
                val ref = seed()
                if (phase == "failure-seed") {
                    val file = File(dir, "chat-index-claims.bin"); val bytes = file.readBytes()
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
                }
                val future = GlobalRetirement.submit(context); assertFalse(future.cancel(true))
                val result = future.get(30, TimeUnit.SECONDS); assertTrue(result.failures.toString(), result.completed)
                localGone()
                val record = RetirementEvidence.read(dir)
                ref.put("generation", record.generation); referenceFile.writeText(ref.toString())
                if (phase == "failure-seed") {
                    assertEquals(RetirementEvidenceCodec.Phase.INTENT, record.phase)
                    rejected { RetirementEvidence.verifyCompleted(context) }
                } else assertEquals(RetirementEvidenceCodec.Phase.CLEANED, RetirementEvidence.verifyCompleted(context).phase)
            }
            "failure-verify", "intent-verify" -> {
                localGone()
                val before = RetirementEvidence.marker(dir).readBytes()
                val record = RetirementEvidence.read(dir)
                assertEquals(RetirementEvidenceCodec.Phase.INTENT, record.phase)
                assertEquals(JSONObject(referenceFile.readText()).getString("generation"), record.generation)
                rejected { RetirementEvidence.verifyCompleted(context) }
                rejected { RetainedGuardInventory.captureFresh(context, setOf("station-v2", root(41), root(42))) }
                assertTrue(GlobalRetirement.retryExisting(context).get(30, TimeUnit.SECONDS).completed)
                assertArrayEquals(before, RetirementEvidence.marker(dir).readBytes())
            }
            "scope-verify" -> {
                localGone()
                assertFalse(keys().containsAlias("otp-pager-${root(43)}-device"))
                assertEquals(RetirementEvidenceCodec.Phase.SEALED, RetirementEvidence.read(dir).phase)
                rejected { RetirementEvidence.verifyCompleted(context) }
                assertEquals(GlobalRetirement.LocalState.UNAVAILABLE, GlobalRetirement.inspect(context).state)
            }
        }
    }
}
