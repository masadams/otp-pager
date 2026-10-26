package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class VaultRetirementTest {
    private class Death : Error()
    private class Fake : VaultRetirement.Operations {
        val calls = mutableListOf<String>()
        var marker: Boolean? = null
        var denied = false
        val present = mutableSetOf("vault-key", "draft-key", "device-key", "vault-files", "draft-files")
        var fail: String? = null
        var dieAfter: String? = null
        private fun step(name: String, action: () -> Unit) {
            calls.add(name)
            if (fail == name) error("Injected failure")
            action()
            if (dieAfter == name) throw Death()
        }
        override fun denyInProcess() = step("deny") { denied = true }
        override fun writeMarker(complete: Boolean) = step(if (complete) "complete" else "pending") { marker = complete }
        override fun cleanup(): List<Pair<String, () -> Unit>> =
            listOf("vault-key", "draft-key", "device-key", "vault-files", "draft-files").map { name ->
                name to { step(name) { present.remove(name); Unit } }
            }
    }
    @Test fun terminalMarkerPrecedesCleanupAndEveryStepIsIdempotent() {
        val fake = Fake()
        assertTrue(VaultRetirement.perform("test", fake).completed)
        assertEquals(listOf("deny", "pending", "vault-key", "draft-key", "device-key", "vault-files", "draft-files", "complete"), fake.calls)
        assertTrue(fake.denied); assertEquals(true, fake.marker); assertTrue(fake.present.isEmpty())
        assertTrue(VaultRetirement.perform("test", fake).completed)
    }
    @Test fun eachDeletionFailureStillAttemptsTheRemainingStepsAndNeverReportsCompletion() {
        for (failure in listOf("vault-key", "draft-key", "device-key", "vault-files", "draft-files")) {
            val fake = Fake().apply { fail = failure }
            val result = VaultRetirement.perform("test", fake)
            assertTrue(result.terminalDurable); assertFalse(result.completed)
            assertEquals(listOf(failure), result.failures)
            assertEquals(setOf(failure), fake.present); assertEquals(false, fake.marker)
            assertTrue(fake.calls.contains("draft-files")); assertFalse(fake.calls.contains("complete"))
            fake.fail = null
            assertTrue(VaultRetirement.perform("test", fake).completed)
            assertTrue(fake.present.isEmpty())
        }
    }
    @Test fun missingDurableMarkerDoesNotPreventBestEffortKeyDestruction() {
        val fake = Fake().apply { fail = "pending" }
        val result = VaultRetirement.perform("test", fake)
        assertFalse(result.terminalDurable); assertFalse(result.completed)
        assertEquals(listOf("terminal-marker"), result.failures)
        assertTrue(fake.denied); assertTrue(fake.present.isEmpty()); assertNull(fake.marker)
    }
    @Test fun failedCompletionRecordLeavesPendingState() {
        val fake = Fake().apply { fail = "complete" }
        val result = VaultRetirement.perform("test", fake)
        assertTrue(result.terminalDurable); assertFalse(result.completed)
        assertEquals(listOf("completion-marker"), result.failures); assertEquals(false, fake.marker)
    }
    @Test fun interruptionAfterEveryDurableBoundaryCanResumeCleanup() {
        for (boundary in listOf("pending", "vault-key", "draft-key", "device-key", "vault-files", "draft-files", "complete")) {
            val fake = Fake().apply { dieAfter = boundary }
            try { VaultRetirement.perform("test", fake); fail("Interruption ignored") } catch (_: Death) {}
            assertTrue(fake.denied); assertNotNull(fake.marker)
            fake.dieAfter = null
            assertTrue(VaultRetirement.perform("test", fake).completed)
            assertTrue(fake.present.isEmpty()); assertEquals(true, fake.marker)
        }
    }
}
