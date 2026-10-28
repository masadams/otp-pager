package org.example.otppager

import org.example.otppager.VaultGeneration.Capability
import org.junit.Assert.*
import org.junit.Test

class CapabilityDecisionTest {
    private val e1 = "e".repeat(32)
    private val e2 = "f".repeat(32)
    private fun manifest(epoch: String) = ActiveManifestCodec.record(epoch, "1".repeat(32), "a".repeat(64), "c".repeat(64),
        "d".repeat(64), 1, listOf("chat-" + epoch.take(20)))
    private val activeE1 = EpochLineage.State.Active(manifest(e1))
    private val root = "chat-" + e1.take(20)
    private fun neverRead(): EpochLineage.State = throw AssertionError("Lineage must not be read for this owner")

    @Test fun legacyAndBlockedOwnersKeepTheirPreviousRulesWithoutReadingLineage() {
        assertTrue(VaultGeneration.allows(Capability.Legacy, stagingAbsent = true, ::neverRead, "chat-index"))
        assertFalse(VaultGeneration.allows(Capability.Legacy, stagingAbsent = false, ::neverRead, "chat-index"))
        assertFalse(VaultGeneration.allows(Capability.Blocked, stagingAbsent = true, ::neverRead, "chat-index"))
        assertFalse(VaultGeneration.allows(Capability.Blocked, stagingAbsent = false, ::neverRead, root))
    }

    @Test fun activeOwnersNeedTheSameEpochStillActiveAndAnAllowlistedNamespace() {
        val owner = Capability.Active(e1)
        for (ns in listOf(root, "$root-draft", "$root-pads", "enr-" + e1.take(20), "enr-" + e1.take(20) + "-claims"))
            assertTrue(ns, VaultGeneration.allows(owner, false, { activeE1 }, ns))
        for (ns in listOf("station-v2", "chat-index", "chat-index-claims", "chat-" + e2.take(20), "enr-" + e2.take(20)))
            assertFalse(ns, VaultGeneration.allows(owner, false, { activeE1 }, ns))
        // Epoch changed, froze, or staging vanished: the captured owner never rebinds.
        assertFalse(VaultGeneration.allows(owner, false, { EpochLineage.State.Active(manifest(e2)) }, root))
        assertFalse(VaultGeneration.allows(owner, false, { EpochLineage.State.Frozen("cleanup-incomplete") }, root))
        assertFalse(VaultGeneration.allows(owner, false, { EpochLineage.State.Legacy }, root))
        assertFalse(VaultGeneration.allows(owner, true, { activeE1 }, root))
    }
}
