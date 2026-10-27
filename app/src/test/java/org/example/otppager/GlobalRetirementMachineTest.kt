package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class GlobalRetirementMachineTest {
    private class Death : Error()
    private class Fixture : GlobalRetirementMachine.Operations {
        val events = mutableListOf<String>()
        var old = GlobalRetirementMachine.Previous(setOf("old"))
        var before = GlobalRetirementMachine.Scope(setOf("a", "b"), true)
        var after = before
        var record: Set<String> = emptySet()
        var complete = false
        var failIntent = false
        var failCompletion = false
        var failContact: String? = null
        var failMetadata = false
        var dieAfter = -1
        var boundaries = 0
        private fun boundary() { if (++boundaries == dieAfter) throw Death() }
        override fun freeze(): Set<String> { events.add("freeze"); return setOf("live") }
        override fun previous() = old
        override fun write(roots: Set<String>, complete: Boolean) {
            events.add(if (complete) "complete" else "pending")
            if (if (complete) failCompletion else failIntent) error("write failed")
            record = roots; this.complete = complete; boundary()
        }
        override fun discover(): GlobalRetirementMachine.Scope {
            events.add("discover")
            return if (events.count { it == "discover" } == 1) before else after
        }
        override fun retire(root: String): Boolean {
            events.add("retire:$root"); boundary()
            return root != failContact
        }
        override fun metadata() = listOf(
            "index-key" to { events.add("index-key"); if (failMetadata) error("key failed"); boundary() },
            "index-file" to { events.add("index-file"); boundary() })
    }
    @Test fun unionIsDurableBeforeCleanupAndEachIndependentFailureContinuesOtherTargets() {
        val f = Fixture(); f.failContact = "a"; f.failMetadata = true
        val result = GlobalRetirementMachine.perform(f)
        assertEquals(setOf("old", "live", "a", "b"), f.record)
        assertEquals(listOf("freeze", "pending", "discover", "pending"), f.events.take(4))
        assertTrue(f.events.containsAll(listOf("retire:a", "retire:b", "retire:old", "retire:live", "index-key", "index-file")))
        assertTrue(result.terminalDurable); assertTrue(result.scopeComplete); assertFalse(result.completed)
        assertFalse(f.complete)
    }
    @Test fun journalFailureStillAttemptsAllKnownDestructionButCannotClaimDurableCompletion() {
        val f = Fixture(); f.failIntent = true
        val result = GlobalRetirementMachine.perform(f)
        assertFalse(result.terminalDurable); assertFalse(result.completed)
        assertTrue(f.events.contains("retire:b")); assertTrue(f.events.contains("index-file"))
    }
    @Test fun unavailableOrCorruptJournalNeverGetsOverwrittenOrReportedAsACompletedEmptyWipe() {
        val f = Fixture(); f.old = GlobalRetirementMachine.Previous(setOf("recoverable"), false)
        val result = GlobalRetirementMachine.perform(f)
        assertFalse(result.completed); assertFalse(result.terminalDurable)
        assertTrue(f.events.contains("retire:recoverable")); assertFalse(f.events.contains("pending"))
    }
    @Test fun partialDiscoveryAndNewlyDiscoveredRootsRequireAnotherRecoveryPass() {
        val partial = Fixture(); partial.before = GlobalRetirementMachine.Scope(setOf("a"), false, listOf("files-unavailable"))
        assertFalse(GlobalRetirementMachine.perform(partial).completed)
        assertTrue(partial.events.contains("retire:a"))
        val changed = Fixture(); changed.after = GlobalRetirementMachine.Scope(setOf("a", "b", "late"), true)
        val result = GlobalRetirementMachine.perform(changed)
        assertFalse(result.completed); assertTrue("late" in changed.record)
        assertFalse(changed.events.contains("retire:late")) // Persist before a subsequent recovery attempts it.
    }
    @Test fun completionWriteFailureLeavesPendingAndAFullSuccessfulRetryCanComplete() {
        val f = Fixture(); f.failCompletion = true
        assertFalse(GlobalRetirementMachine.perform(f).completed); assertFalse(f.complete)
        f.old = GlobalRetirementMachine.Previous(f.record); f.failCompletion = false; f.events.clear()
        assertTrue(GlobalRetirementMachine.perform(f).completed); assertTrue(f.complete)
    }
    @Test fun interruptionAtEveryDurableOrDeletionBoundaryCanResumeWithoutLosingTargets() {
        // Two intent commits, four contact cleanups, two metadata steps and completion.
        for (boundary in 1..9) {
            val f = Fixture(); f.dieAfter = boundary
            try { GlobalRetirementMachine.perform(f); fail("No interruption at $boundary") } catch (_: Death) {}
            val saved = f.record
            f.old = GlobalRetirementMachine.Previous(saved); f.dieAfter = -1; f.events.clear()
            val resumed = GlobalRetirementMachine.perform(f)
            assertTrue("Failed recovery at $boundary: ${resumed.failures}", resumed.completed)
            assertTrue(resumed.roots.containsAll(saved))
        }
    }
}
