package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class ContactInventoryTest {
    private val a = "chat-0123456789abcdef0123"
    private val b = "chat-fedcba9876543210fedc"
    @Test fun orphanDraftsPadsJournalsAndKeysAreFoundWithoutAnyContactIndex() {
        assertEquals(setOf("station-v2", a, b), ContactInventory.discover(
            listOf("$a-draft.bin.new", "$a-pads.bin.bak", "$b.retired.new", "station-v2.bin"),
            listOf("otp-pager-$b-device", "otp-pager-$b-draft-device")))
        assertEquals(setOf(b), ContactInventory.discover(emptyList(), listOf("otp-pager-$b-device")))
    }
    @Test fun nearMatchesPathsAndUnrelatedAppKeysAreNeverTreatedAsContactRoots() {
        assertTrue(ContactInventory.discover(
            listOf("../$a.bin", "$a.bin/other", "${a}0.bin", "$a-private.bin", "chat-index.bin", "chat-index-claims.bin", "$a.retired.fake", "$a.bin.bak.new"),
            listOf("other-$a", "otp-pager-$a-extra", "otp-pager-chat-index", "otp-pager-${a.uppercase()}-device")).isEmpty())
    }
    @Test fun capacityFailureDoesNotReturnAPartialSuccessfulScope() {
        try {
            ContactInventory.discover(List(ContactInventory.MAX_ENTRIES) { "$a.bin" }, listOf("otp-pager-$b"))
            fail("Inventory silently truncated")
        } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("capacity")) }
    }
    @Test fun partialFileEnumerationStillKeepsKnownRootsAndTriesKeyDiscovery() {
        val broken = Iterable { sequence { yield("$a.bin"); throw java.io.IOException("directory unavailable") }.iterator() }
        val result = ContactInventory.collect(broken, listOf("otp-pager-$b-device"))
        assertFalse(result.complete); assertEquals(setOf(a, b), result.roots)
        assertTrue("inventory-files" in result.failures)
    }

}
