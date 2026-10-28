package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import org.example.otppager.EnrollmentStageCodec.Phase.ABANDONED
import org.example.otppager.EnrollmentStageCodec.Phase.PREPARED
import org.example.otppager.EnrollmentStageCodec.Phase.PREPARING
import org.example.otppager.EpochLineage.Observation
import org.example.otppager.EpochLineage.State
import org.example.otppager.EpochLineage.Terminal

class EpochLineageTest {
    private fun hex32(c: Char) = c.toString().repeat(32)
    private fun hex64(c: Char) = c.toString().repeat(64)
    private val t0 = Terminal(hex32('1'), EpochLineage.LEGACY, hex64('a'), cleaned = true)
    private val e1 = hex32('e')
    private val e2 = hex32('f')
    private fun stage(epoch: String, terminal: Terminal, phase: EnrollmentStageCodec.Phase) =
        EnrollmentStageCodec.Record(epoch, terminal.generation, terminal.commitment, phase,
            if (phase == PREPARED) hex64('c') else EnrollmentStageCodec.zeroHash,
            if (phase == PREPARED) hex64('d') else EnrollmentStageCodec.zeroHash)
    private fun manifest(epoch: String, terminal: Terminal, index: String = hex64('c'), commitment: String = terminal.commitment) =
        ActiveManifestCodec.record(epoch, terminal.generation, commitment, index, hex64('d'), 1, listOf("chat-" + epoch.take(20)))
    private fun frozen(o: Observation, reason: String) {
        val state = EpochLineage.resolve(o)
        assertTrue("Expected frozen($reason), got $state", state is State.Frozen)
        assertEquals(reason, (state as State.Frozen).reason)
    }
    private fun active(o: Observation): ActiveManifestCodec.Record {
        val state = EpochLineage.resolve(o)
        assertTrue("Expected active, got $state", state is State.Active)
        return (state as State.Active).manifest
    }
    /** A valid first epoch: T0 cleaned, E1 prepared and activated, one abandoned earlier attempt. */
    private fun firstEpoch() = Observation(legacyJournal = true, terminals = listOf(t0),
        stages = listOf(stage(hex32('9'), t0, ABANDONED), stage(e1, t0, PREPARED)), manifests = listOf(manifest(e1, t0)))

    @Test fun noLifecycleRecordsIsLegacyAndAJournalWithoutEvidenceStaysFrozen() {
        assertEquals(State.Legacy, EpochLineage.resolve(Observation()))
        frozen(Observation(legacyJournal = true), "legacy-journal-without-evidence")
        frozen(Observation(unreadable = true), "unreadable")
        frozen(firstEpoch().copy(unreadable = true), "unreadable")
    }

    @Test fun firstEnrollmentNeedsCleanedEvidenceAPreparedStageAndAMatchingManifest() {
        frozen(Observation(terminals = listOf(t0.copy(cleaned = false))), "cleanup-incomplete")
        frozen(Observation(terminals = listOf(t0), stages = listOf(stage(e1, t0, PREPARED))), "awaiting-enrollment")
        assertEquals(e1, active(firstEpoch()).epoch)
        assertEquals(e1, active(firstEpoch().copy(legacyJournal = false)).epoch)
        frozen(firstEpoch().copy(stages = listOf(stage(e1, t0, PREPARING))), "manifest-stage-mismatch")
        frozen(firstEpoch().copy(stages = emptyList()), "manifest-without-stage")
        frozen(firstEpoch().copy(manifests = listOf(manifest(e1, t0, index = hex64('7')))), "manifest-stage-mismatch")
        frozen(firstEpoch().copy(manifests = listOf(manifest(e1, t0, commitment = hex64('8')))), "manifest-commitment")
        frozen(firstEpoch().copy(stages = firstEpoch().stages + stage(hex32('5'), t0, PREPARED)), "open-stage")
        val foreignStage = EnrollmentStageCodec.Record(e1, t0.generation, hex64('b'), PREPARED, hex64('c'), hex64('d'))
        frozen(firstEpoch().copy(stages = listOf(foreignStage)), "stage-commitment")
    }

    @Test fun ambiguityDuplicatesAndUnexplainedRecordsNeverPickAWinner() {
        frozen(firstEpoch().copy(manifests = listOf(manifest(e1, t0), manifest(e2, t0)),
            stages = listOf(stage(e1, t0, PREPARED), stage(e2, t0, PREPARED))), "ambiguous-manifest")
        frozen(firstEpoch().copy(terminals = listOf(t0, t0.copy(generation = hex32('2')))), "ambiguous-terminal")
        frozen(firstEpoch().copy(terminals = listOf(t0, t0)), "duplicate-terminal")
        frozen(firstEpoch().copy(stages = firstEpoch().stages + stage(e1, t0, PREPARED)), "duplicate-stage")
        frozen(firstEpoch().copy(terminals = listOf(t0, Terminal(hex32('3'), hex32('4'), hex64('a'), true))), "unexplained-record")
        frozen(Observation(stages = listOf(stage(e1, t0, PREPARED))), "unexplained-record")
        frozen(Observation(manifests = listOf(manifest(e1, t0))), "unexplained-record")
        frozen(firstEpoch().copy(terminals = listOf(t0, Terminal(hex32('3'), "LEGACY", hex64('a'), true))), "invalid-terminal")
    }

    @Test fun secondWipeFreezesTheEpochAndOnlyANewEnrollmentReactivates() {
        val t1 = Terminal(hex32('6'), e1, hex64('b'), cleaned = false)
        val wiping = firstEpoch().copy(terminals = listOf(t0, t1))
        frozen(wiping, "cleanup-incomplete") // E1 is never usable once its wipe has started.
        val t1Done = t1.copy(cleaned = true)
        frozen(wiping.copy(terminals = listOf(t0, t1Done)), "awaiting-enrollment")
        val second = wiping.copy(terminals = listOf(t0, t1Done),
            stages = wiping.stages + stage(e2, t1Done, PREPARED), manifests = wiping.manifests + manifest(e2, t1Done))
        assertEquals(e2, active(second).epoch)
        // Rolling back E2's manifest must not resurrect E1.
        frozen(second.copy(manifests = listOf(manifest(e1, t0))), "awaiting-enrollment")
        // Hiding E1's terminal leaves E2's records unexplained instead of reopening E1.
        frozen(second.copy(terminals = listOf(t0)), "unexplained-record")
    }

    @Test fun resultIsIndependentOfObservationOrder() {
        val t1 = Terminal(hex32('6'), e1, hex64('b'), cleaned = true)
        val base = firstEpoch()
        val second = base.copy(terminals = listOf(t0, t1), stages = base.stages + stage(e2, t1, PREPARED),
            manifests = base.manifests + manifest(e2, t1))
        val shuffled = second.copy(terminals = second.terminals.reversed(), stages = second.stages.reversed(),
            manifests = second.manifests.reversed())
        assertEquals(EpochLineage.resolve(second), EpochLineage.resolve(shuffled))
        assertEquals(e2, active(shuffled).epoch)
    }

    @Test fun scopePermitsOnlyThisEpochsIndexClaimsAndListedRoots() {
        val m = ActiveManifestCodec.appended(manifest(e1, t0), "chat-" + "0".repeat(19) + "1")
        val root = "chat-" + e1.take(20); val added = "chat-" + "0".repeat(19) + "1"
        for (ok in listOf(m.index, m.claims, root, "$root-draft", "$root-pads", added, "$added-pads"))
            assertTrue("Refused $ok", EpochScope.permits(m, ok))
        val other = "enr-" + e2.take(20)
        for (bad in listOf("station-v2", "station-v2-pads", "chat-index", "chat-index-claims", other, "$other-claims",
            "chat-" + e2.take(20), "$root-other", "$root-draft-pads", "${m.index}-draft", "CHAT-" + e1.take(20), ""))
            assertFalse("Permitted $bad", EpochScope.permits(m, bad))
    }

    @Test fun detailReportsTheEpochBeingWipedAndEveryLinkedTerminal() {
        val t1 = Terminal(hex32('6'), e1, hex64('b'), cleaned = false)
        val detail = EpochLineage.detail(firstEpoch().copy(terminals = listOf(t0, t1)))
        assertEquals(State.Frozen("cleanup-incomplete"), detail.state)
        assertEquals(e1, detail.lastEpoch?.epoch)
        assertEquals(listOf(t0, t1), detail.chain)
        val active = EpochLineage.detail(firstEpoch())
        assertEquals(listOf(t0), (active.state as State.Active).chain)
        assertNull(EpochLineage.detail(Observation(unreadable = true)).lastEpoch)
        assertNull(EpochLineage.detail(Observation(terminals = listOf(t0))).lastEpoch)
    }

    @Test fun anEpochCanNeverRelistARootAnEarlierWipeDestroyed() {
        val initial = "chat-" + e1.take(20)
        frozen(firstEpoch().copy(terminals = listOf(t0.copy(roots = setOf("station-v2", initial)))), "root-reuse")
        val appended = ActiveManifestCodec.appended(manifest(e1, t0), "chat-" + "7".repeat(20))
        frozen(firstEpoch().copy(terminals = listOf(t0.copy(roots = setOf("chat-" + "7".repeat(20)))), manifests = listOf(appended)), "root-reuse")
        val distinct = firstEpoch().copy(terminals = listOf(t0.copy(roots = setOf("station-v2", "chat-" + "8".repeat(20)))))
        assertEquals(e1, active(distinct).epoch)
    }
}
