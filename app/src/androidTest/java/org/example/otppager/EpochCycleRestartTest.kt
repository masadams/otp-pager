package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Disposable-emulator only (scripts/verify_epoch_cycle_restart.py). Full lifecycle with real
 * Keystore records and cold restarts between phases: legacy → wipe → fresh epoch E1 → wipe →
 * fresh epoch E2, plus interrupted activation, interrupted epoch wipe and a tampered manifest.
 */
@RunWith(AndroidJUnit4::class)
class EpochCycleRestartTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir get() = context.noBackupFilesDir.absoluteFile
    private val endpoint = "https://pager.example.com"
    private val token = "t".repeat(43)
    private val reference get() = File(context.filesDir, "epoch-cycle-reference.json")
    private fun saved() = JSONObject(reference.readText())
    private fun save(update: JSONObject.() -> Unit) {
        val value = if (reference.exists()) saved() else JSONObject()
        value.update(); reference.writeText(value.toString())
    }
    private fun rejected(action: () -> Unit) { try { action(); fail("Unsafe lifecycle operation accepted") } catch (_: Exception) {} }
    private fun active(): ActiveManifestCodec.Record {
        EpochState.invalidate(dir)
        val state = EpochState.resolve(dir)
        assertTrue("Expected ACTIVE, got $state", state is EpochLineage.State.Active)
        assertFalse(GlobalRetirement.isFrozen(dir))
        return (state as EpochLineage.State.Active).manifest
    }
    private fun padIds(claimsNamespace: String) = SecureStore(context, claimsNamespace).load()?.getJSONObject("pads")?.keys()?.asSequence()?.toList() ?: emptyList()
    /** Pair a chat and install a pad; returns (secret, pad id). */
    private fun pair(book: ChatBook, id: String, claimsNamespace: String): Pair<String, String> {
        val before = padIds(claimsNamespace).toSet()
        val secret = Protocol.randomSecret()
        book.engine(id).configure(endpoint, token, secret, "A")
        book.engine(id).generatePad().fill(0)
        return secret to (padIds(claimsNamespace).toSet() - before).single()
    }
    private fun wipeAll() {
        val result = GlobalRetirement.submit(context).get(60, TimeUnit.SECONDS)
        assertTrue("Global wipe incomplete: ${result.failures}", result.completed)
        assertTrue(GlobalRetirement.isFrozen(dir))
        assertEquals(GlobalRetirement.LocalState.COMPLETE, GlobalRetirement.inspect(context).state)
        rejected { ChatBook(context) }
    }
    private fun enroll(): ActiveManifestCodec.Record {
        assertTrue(FreshEnrollment.eligible(context))
        val token = FreshEnrollment.confirm(context)
        val manifest = FreshEnrollment.enroll(context, token)
        rejected { FreshEnrollment.enroll(context, token) } // One use.
        assertEquals(manifest, active())
        assertEquals(manifest.index, EpochRouting.index(context))
        return manifest
    }
    /** Old room secrets and pad ids are refused in [manifest]'s epoch; fresh material works. */
    private fun refusesOldMaterial(manifest: ActiveManifestCodec.Record, secrets: List<String>, pads: List<String>) {
        val book = ChatBook(context)
        assertEquals(manifest.initialRoot, book.chats().first().id)
        for (secret in secrets) {
            try { book.engine().configure(endpoint, token, secret, "A"); fail("Old pairing secret reused") } catch (_: IllegalArgumentException) {}
        }
        val claims = PairingClaims(SecureStore(context, manifest.claims))
        for (pad in pads) {
            try { claims.pad(pad, manifest.initialRoot); fail("Old pad reused") } catch (_: IllegalArgumentException) {}
        }
        assertTrue(pads.none { it in padIds(manifest.claims) })
    }

    @Test fun freshEpochsRouteIsolateAndRefuseEveryEarlierPadAndPairing() {
        val phase = InstrumentationRegistry.getArguments().getString("epochPhase")
        assumeTrue("Exclusive host epoch fixture only", phase in setOf("cycle-seed", "cycle-verify", "cycle-verify2", "tamper-verify",
            "interrupt-seed", "interrupt-verify", "intent-seed", "intent-verify"))
        when (phase) {
            "cycle-seed" -> {
                val book = ChatBook(context)
                val legacyRoot = book.active().id
                val (s0, p0) = pair(book, legacyRoot, "chat-index-claims")
                val staleLegacy = SecureStore(context, "chat-index") // Captured LEGACY before the wipe.
                wipeAll()
                val m1 = enroll()
                rejected { staleLegacy.load() }
                rejected { SecureStore(context, "chat-index").load() }
                rejected { SecureStore(context, legacyRoot).load() }
                refusesOldMaterial(m1, listOf(s0), listOf(p0))
                val fresh = ChatBook(context)
                val (s1, p1) = pair(fresh, m1.initialRoot, m1.claims)
                save { put("s0", s0); put("p0", p0); put("s1", s1); put("p1", p1); put("e1", m1.epoch) }
            }
            "cycle-verify" -> {
                val m1 = active()
                assertEquals(saved().getString("e1"), m1.epoch)
                val book = ChatBook(context)
                assertNotNull("E1 conversation lost across restart", book.engine().state)
                val bob = book.add("Bob", copyServerFrom = null)
                assertEquals(2L, EpochManifests.read(dir, m1.epoch).sequence) // Listed before its first key/file.
                assertTrue(bob.id in EpochManifests.read(dir, m1.epoch).roots)
                book.engine(bob.id).configure(endpoint, token, Protocol.randomSecret(), "A")
                wipeAll()
                for (root in listOf(m1.initialRoot, bob.id))
                    assertTrue("$root survived", VaultLifecycle.variants(File(dir, "$root.bin")).none { it.exists() })
                assertTrue("Epoch ledger must be retained", File(dir, "${m1.initialRoot}-pads.bin").exists())
                assertTrue("Epoch claims must be retained", File(dir, "${m1.claims}.bin").exists())
                assertFalse(File(dir, "${m1.index}.bin").exists())
                val m2 = enroll()
                assertNotEquals(m1.epoch, m2.epoch)
                rejected { SecureStore(context, m1.initialRoot).load() }
                rejected { SecureStore(context, m1.index).load() }
                refusesOldMaterial(m2, listOf(saved().getString("s0"), saved().getString("s1")), listOf(saved().getString("p0"), saved().getString("p1")))
                pair(ChatBook(context), m2.initialRoot, m2.claims)
                save { put("e2", m2.epoch) }
            }
            "cycle-verify2" -> {
                val m2 = active()
                assertEquals(saved().getString("e2"), m2.epoch)
                assertEquals(2, (EpochState.resolve(dir) as EpochLineage.State.Active).chain.size)
                assertNotNull(ChatBook(context).engine().state)
                // Tamper with the manifest; the next cold start must freeze WITHOUT destroying E2.
                val file = EpochManifests.file(dir, m2.epoch); val bytes = file.readBytes()
                File(context.filesDir, "manifest-original.bin").writeBytes(bytes)
                file.writeBytes(bytes.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() })
            }
            "tamper-verify" -> {
                val root = "chat-" + saved().getString("e2").take(20)
                assertTrue(GlobalRetirement.isFrozen(dir))
                assertEquals(GlobalRetirement.LocalState.UNAVAILABLE, GlobalRetirement.inspect(context).state)
                assertFalse(FreshEnrollment.eligible(context))
                assertTrue("Boot recovery destroyed an epoch without durable wipe intent", File(dir, "$root.bin").exists())
                rejected { ChatBook(context) }
                EpochManifests.file(dir, saved().getString("e2")).writeBytes(File(context.filesDir, "manifest-original.bin").readBytes())
                active()
                assertNotNull(ChatBook(context).engine().state)
            }
            "interrupt-seed" -> {
                val book = ChatBook(context); pair(book, book.active().id, "chat-index-claims")
                wipeAll()
                // Crash after staging, before the activation commit.
                val stage = EnrollmentStaging.prepare(context, EnrollmentStaging.confirmation(context))
                save { put("stage", stage.epoch) }
            }
            "interrupt-verify" -> {
                assertTrue(GlobalRetirement.isFrozen(dir))
                rejected { ChatBook(context) }
                val pending = EnrollmentStaging.inspect(context).single()
                assertEquals(EnrollmentStageCodec.Phase.PREPARED, pending.phase)
                assertEquals(saved().getString("stage"), pending.epoch)
                assertEquals(pending.epoch, enroll().epoch) // The verified attempt is activated, not replaced.
            }
            "intent-seed" -> {
                val book = ChatBook(context); pair(book, book.active().id, "chat-index-claims")
                wipeAll()
                val m1 = enroll()
                val fresh = ChatBook(context); pair(fresh, m1.initialRoot, m1.claims)
                // Crash right after durable wipe intent, before any cleanup step.
                GlobalRetirement.latchOnlyForRestartFixture(context)
                EpochEvidence.beginFresh(dir, m1.epoch)
                EpochEvidence.closeCapture(dir)
                save { put("e1", m1.epoch) }
            }
            "intent-verify" -> {
                val root = "chat-" + saved().getString("e1").take(20)
                assertTrue(GlobalRetirement.isFrozen(dir))
                assertTrue("Boot recovery did not finish the epoch wipe", VaultLifecycle.variants(File(dir, "$root.bin")).none { it.exists() })
                assertTrue(VaultLifecycle.isRetired(dir, root))
                // INTENT was never sealed: cleanup cannot gain retrospective provenance.
                assertEquals(GlobalRetirement.LocalState.INCOMPLETE, GlobalRetirement.inspect(context).state)
                assertFalse(FreshEnrollment.eligible(context))
                rejected { ChatBook(context) }
            }
        }
    }
}
