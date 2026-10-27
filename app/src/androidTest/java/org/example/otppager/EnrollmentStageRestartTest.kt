package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Disposable-emulator only; staging never opens the app or activates a pairing. */
@RunWith(AndroidJUnit4::class)
class EnrollmentStageRestartTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir get() = context.noBackupFilesDir
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun rejected(action: () -> Unit) { try { action(); fail("Unsafe staging operation accepted") } catch (_: Exception) {} }
    private fun saved() = JSONObject(File(dir, "stage-reference.json").readText())
    private fun save(epoch: String, old: String? = null) {
        File(dir, "stage-reference.json").writeText(JSONObject().put("epoch", epoch).put("old", old ?: "").toString())
    }
    private fun cleanupOld() {
        val book = ChatBook(context); val engine = book.engine()
        engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
        engine.generatePad().fill(0)
        assertTrue(GlobalRetirement.submit(context).get(30, TimeUnit.SECONDS).completed)
        RetirementEvidence.verifyCompleted(context)
    }
    private fun prepared(): EnrollmentStageCodec.Record {
        cleanupOld()
        val confirmation = EnrollmentStaging.confirmation(context)
        val r = EnrollmentStaging.prepare(context, confirmation)
        assertEquals(EnrollmentStageCodec.Phase.PREPARED, r.phase)
        rejected { EnrollmentStaging.prepare(context, confirmation) }
        assertTrue(keys().containsAlias("otp-pager-${r.index}")); assertTrue(keys().containsAlias("otp-pager-${r.claims}"))
        assertFalse(keys().containsAlias("otp-pager-${r.root}"))
        blocked(r)
        return r
    }
    private fun blocked(r: EnrollmentStageCodec.Record) {
        assertTrue(GlobalRetirement.isFrozen(dir))
        rejected { ChatBook(context) }; rejected { SecureStore(context, r.index).load() }
        rejected { SecureStore(context, r.root).save(JSONObject().put("version", 2)) }
        rejected { SecureStore(context, r.claims).forget() }
        RetirementEvidence.verifyCompleted(context)
    }
    private fun erased(r: EnrollmentStageCodec.Record) {
        for (slot in listOf(r.index, r.claims, r.root, "${r.root}-draft", "${r.root}-pads")) {
            assertFalse(keys().containsAlias("otp-pager-$slot")); assertFalse(keys().containsAlias("otp-pager-$slot-device"))
            assertTrue(VaultLifecycle.variants(File(dir, "$slot.bin")).none { it.exists() })
        }
        assertTrue(keys().containsAlias(EnrollmentStaging.keyAlias(dir, r.epoch))) // Reservation/authentication survives.
    }
    private fun rawRecord(r: EnrollmentStageCodec.Record) {
        // Test-only interrupted/older record injection under the fixture's real non-exportable key.
        val payload = EnrollmentStageCodec.encode(r)
        val key = keys().getKey(EnrollmentStaging.keyAlias(dir, r.epoch), null) as SecretKey
        val tag = Mac.getInstance("HmacSHA256").run { init(key); doFinal(payload) }
        EnrollmentStaging.marker(dir, r.epoch).writeBytes(payload + tag)
    }
    @Test fun stagedStorageCannotActivateResumeEncryptionOrLoseAbandonedReservations() {
        val phase = InstrumentationRegistry.getArguments().getString("enrollmentPhase")
        assumeTrue("Exclusive host staging fixture only", phase in setOf("prepared-seed", "prepared-verify", "interrupted-verify", "rollback-verify",
            "proof-change", "orphan-seed", "orphan-verify", "tamper-seed", "tamper-verify", "variants-seed", "variants-verify",
            "partial-seed", "partial-verify", "unknown-seed", "unknown-verify"))
        when (phase) {
            "prepared-seed" -> { val r = prepared(); save(r.epoch) }
            "prepared-verify" -> {
                val first = EnrollmentStaging.inspect(context).single { it.epoch == saved().getString("epoch") }
                assertEquals(EnrollmentStageCodec.Phase.PREPARED, first.phase); blocked(first)
                EnrollmentStaging.abandon(context, first.epoch)
                val abandoned = EnrollmentStaging.inspect(context).single(); assertEquals(EnrollmentStageCodec.Phase.ABANDONED, abandoned.phase); erased(abandoned)
                val next = EnrollmentStaging.prepare(context, EnrollmentStaging.confirmation(context))
                assertNotEquals(first.epoch, next.epoch); assertNotEquals(first.index, next.index); assertNotEquals(first.root, next.root)
                assertEquals(2, EnrollmentStaging.inspect(context).size)
                File(dir, "prepared-stage-copy.bin").writeBytes(EnrollmentStaging.marker(dir, next.epoch).readBytes())
                save(next.epoch, first.epoch)
                // Interrupted after key creation: one slot lacks its file and an unexpected root key exists.
                File(dir, "${next.claims}.bin").delete()
                DeviceIdentity("otp-pager-${next.root}-device").card(true)
                rawRecord(next.copy(phase = EnrollmentStageCodec.Phase.PREPARING, indexHash = EnrollmentStageCodec.zeroHash, claimsHash = EnrollmentStageCodec.zeroHash))
            }
            "interrupted-verify" -> {
                val record = EnrollmentStaging.inspect(context).single { it.epoch == saved().getString("epoch") }
                assertEquals(EnrollmentStageCodec.Phase.ABANDONED, record.phase); erased(record); blocked(record)
                assertFalse(VaultLifecycle.marker(dir, record.root).exists()) // Reserved in its authenticated record/key, never an old pairing.
                EnrollmentStaging.marker(dir, record.epoch).writeBytes(File(dir, "prepared-stage-copy.bin").readBytes())
            }
            "rollback-verify" -> {
                val records = EnrollmentStaging.inspect(context)
                assertEquals(2, records.size); assertTrue(records.all { it.phase == EnrollmentStageCodec.Phase.ABANDONED })
                records.forEach { erased(it); blocked(it) }
            }
            "proof-change" -> {
                cleanupOld()
                val token = EnrollmentStaging.confirmation(context)
                val terminal = RetirementEvidence.marker(dir); val before = terminal.readBytes()
                terminal.writeBytes(before.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() })
                rejected { EnrollmentStaging.prepare(context, token) }
                assertEquals(EnrollmentStaging.Presence.NONE, EnrollmentStaging.probe(dir))
                terminal.writeBytes(before)
                rejected { EnrollmentStaging.prepare(context, token) } // Repaired proof does not resurrect consumed consent.
                val cancelled = EnrollmentStaging.confirmation(context); EnrollmentStaging.cancel(cancelled)
                rejected { EnrollmentStaging.prepare(context, cancelled) }
                rejected { EnrollmentStaging.prepare(context, EnrollmentStaging.Confirmation(dir.absolutePath,
                    RetirementEvidence.read(dir).generation, RetirementEvidence.commitment(dir))) }
                assertEquals(EnrollmentStaging.Presence.NONE, EnrollmentStaging.probe(dir))
            }
            "orphan-seed", "tamper-seed", "variants-seed", "partial-seed", "unknown-seed" -> {
                val r = prepared(); save(r.epoch)
                val file = EnrollmentStaging.marker(dir, r.epoch)
                when (phase) {
                    "orphan-seed" -> check(file.delete())
                    "tamper-seed" -> file.writeBytes(file.readBytes().also { it[20] = (it[20].toInt() xor 1).toByte() })
                    "variants-seed" -> File(file.path + ".new").writeBytes(file.readBytes())
                    "partial-seed" -> { File(dir, "${r.claims}.bin").delete(); keys().deleteEntry("otp-pager-${r.index}") }
                    "unknown-seed" -> { check(file.delete()); keys().deleteEntry(EnrollmentStaging.keyAlias(dir, r.epoch)) }
                    else -> error("Unknown seed phase")
                }
            }
            "partial-verify" -> {
                val r = EnrollmentStaging.inspect(context).single()
                assertEquals(EnrollmentStageCodec.Phase.ABANDONED, r.phase); erased(r); blocked(r)
            }
            "orphan-verify", "tamper-verify", "variants-verify", "unknown-verify" -> {
                assertEquals(EnrollmentStaging.Presence.PRESENT, EnrollmentStaging.probe(dir))
                val aliases = keys().aliases().toList().toSet()
                val names = dir.listFiles()!!.associate { it.name to it.readBytes() }
                rejected { EnrollmentStaging.inspect(context) }; rejected { EnrollmentStaging.confirmation(context) }
                assertEquals(aliases, keys().aliases().toList().toSet())
                names.forEach { (name, bytes) -> assertArrayEquals(bytes, File(dir, name).readBytes()) }
                assertTrue(GlobalRetirement.isFrozen(dir)); rejected { ChatBook(context) }
                if (phase == "orphan-verify" || phase == "unknown-verify") assertFalse(EnrollmentStaging.marker(dir, saved().getString("epoch")).exists())
            }
        }
    }
}
