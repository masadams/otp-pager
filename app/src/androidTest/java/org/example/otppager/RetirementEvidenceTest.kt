package org.example.otppager

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RetirementEvidenceTest {
    private class Fixture(base: Context) : ContextWrapper(base) {
        val directory = File(base.cacheDir, "terminal-record-" + UUID.randomUUID()).apply { check(mkdir()) }
        override fun getNoBackupFilesDir() = directory
        override fun getApplicationContext(): Context = this
        fun freeze() { GlobalRetirement::class.java.getDeclaredMethod("freeze", Context::class.java).apply { isAccessible = true }.invoke(GlobalRetirement, this) }
        fun close() {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(RetirementEvidence.alias(directory)) }
            directory.deleteRecursively()
        }
    }
    private fun fixture() = Fixture(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid terminal evidence accepted") } catch (_: Exception) {} }
    @Test fun authenticatedIntentRoundTripNeverGrantsEnrollmentOrReplacesAnExistingRecord() {
        val f = fixture(); var ticket: RetirementEvidence.Ticket? = null
        try {
            rejected { RetirementEvidence.beginFresh(f) }
            assertEquals(RetirementEvidence.Presence.NONE, RetirementEvidence.probe(f.directory))
            f.freeze(); ticket = RetirementEvidence.beginFresh(f)
            val file = RetirementEvidence.marker(f.directory); val before = file.readBytes()
            val record = RetirementEvidence.read(f.directory)
            assertEquals(ticket.generation, record.generation); assertEquals(RetirementEvidenceCodec.Phase.INTENT, record.phase)
            assertTrue(record.roots.isEmpty()); assertTrue(record.guards.isEmpty())
            rejected { RetirementEvidence.verifyCompleted(f) }; rejected { RetirementEvidence.beginFresh(f) }
            assertArrayEquals(before, file.readBytes())
            assertTrue(GlobalRetirement.isFrozen(f.directory))
        } finally { ticket?.let(RetirementEvidence::close); f.close() }
    }
    @Test fun tamperingVariantsMissingKeyAndKeyOnlyStateCannotBeRepairedOrReset() {
        val f = fixture(); var ticket: RetirementEvidence.Ticket? = null
        try {
            f.freeze(); ticket = RetirementEvidence.beginFresh(f)
            val file = RetirementEvidence.marker(f.directory); val original = file.readBytes()
            val changed = original.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }; file.writeBytes(changed)
            rejected { RetirementEvidence.read(f.directory) }; assertArrayEquals(changed, file.readBytes())
            file.writeBytes(original)
            for (suffix in listOf(".bak", ".new")) {
                val side = File(file.path + suffix); side.writeBytes(original)
                rejected { RetirementEvidence.read(f.directory) }; assertArrayEquals(original, side.readBytes()); side.delete()
            }
            file.delete()
            assertEquals(RetirementEvidence.Presence.PRESENT, RetirementEvidence.probe(f.directory))
            rejected { RetirementEvidence.read(f.directory) }; rejected { RetirementEvidence.beginFresh(f) }
            assertFalse(file.exists())
            file.writeBytes(original)
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; keys.deleteEntry(RetirementEvidence.alias(f.directory))
            rejected { RetirementEvidence.read(f.directory) }; rejected { RetirementEvidence.beginFresh(f) }
            assertFalse(keys.containsAlias(RetirementEvidence.alias(f.directory))); assertArrayEquals(original, file.readBytes())
        } finally { ticket?.let(RetirementEvidence::close); f.close() }
    }
    @Test fun legacyIntentCannotAcquireRetrospectiveCaptureAuthority() {
        val f = fixture()
        try {
            GlobalRetirement.marker(f.directory).writeText("existing legacy intent")
            rejected { RetirementEvidence.beginFresh(f) }
            assertEquals(RetirementEvidence.Presence.NONE, RetirementEvidence.probe(f.directory))
            assertFalse(RetirementEvidence.marker(f.directory).exists())
        } finally { f.close() }
    }
}
