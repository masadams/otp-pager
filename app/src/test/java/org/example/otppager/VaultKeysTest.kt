package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class VaultKeysTest {
    private val root = "chat-" + "a".repeat(20)
    @Test fun aliasesAreUnambiguousAcrossNamespacesAndGenerations() {
        assertEquals("otp-pager-$root", VaultKeys.alias(root, 0))
        assertEquals("otp-pager-$root~7", VaultKeys.alias(root, 7))
        val aliases = listOf("otp-pager-$root", "otp-pager-$root~1", "otp-pager-$root~42", "otp-pager-$root-draft",
            "otp-pager-$root-device", "otp-pager-$root-pads", "otp-pager-$root~0", "otp-pager-$root~01", "otp-pager-$root~x",
            "otp-pager-${root}b", "otp-pager-chat-" + "b".repeat(20) + "~1")
        assertEquals(listOf("otp-pager-$root", "otp-pager-$root~1", "otp-pager-$root~42"), VaultKeys.all(root, aliases))
        assertTrue(VaultKeys.rotates(root)); assertTrue(VaultKeys.rotates("station-v2"))
        assertFalse(VaultKeys.rotates("chat-index")); assertFalse(VaultKeys.rotates("$root-pads")); assertFalse(VaultKeys.rotates("$root-draft"))
    }
    @Test fun rotatedKeysAreDiscoveredAsContactArtifacts() {
        assertEquals(setOf(root), ContactInventory.discover(emptyList(), listOf("otp-pager-$root~3")))
        assertEquals(emptySet<String>(), ContactInventory.discover(emptyList(), listOf("otp-pager-$root~0", "otp-pager-$root~")))
    }
}
