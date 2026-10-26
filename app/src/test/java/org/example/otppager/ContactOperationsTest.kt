package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ContactOperationsTest {
    private fun key() = UUID.randomUUID().toString()
    @Test fun cancellationIsImmediateIsolatedAndPreventsReopening() {
        val root = key(); val other = key()
        val a = ContactOperations.open(root) {}; val b = ContactOperations.open(root) {}
        val neighbor = ContactOperations.open(other) {}
        val closed = CountDownLatch(2)
        a.onCancel { closed.countDown() }; b.onCancel { closed.countDown() }
        try {
            ContactOperations.cancel(root)
            assertTrue(a.cancelled); assertTrue(b.cancelled); assertFalse(neighbor.cancelled)
            assertFalse(a.publish { fail("Cancelled data published") }); neighbor.check()
            try { ContactOperations.open(root) {}; fail("Retired work reopened") } catch (_: IllegalStateException) {}
            assertTrue(closed.await(5, TimeUnit.SECONDS))
        } finally { a.close(); b.close(); neighbor.close() }
    }
    @Test fun callbacksRegisteredAfterCancellationStillCloseAndNormalCompletionUnregisters() {
        val root = key(); val lease = ContactOperations.open(root) {}
        val closed = CountDownLatch(1)
        ContactOperations.cancel(root); lease.onCancel { closed.countDown() }
        assertTrue(closed.await(5, TimeUnit.SECONDS)); lease.close()
        val normalRoot = key(); val normal = ContactOperations.open(normalRoot) {}
        var ran = false
        normal.onCancel { ran = true }; normal.close(); ContactOperations.cancel(normalRoot)
        assertFalse(ran)
    }
    @Test fun aBlockedCloseNeverHoldsTheLifecycleLockOrDelaysCancellationOfOtherContacts() {
        val root = key(); val lease = ContactOperations.open(root) {}
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        lease.onCancel { entered.countDown(); try { release.await(5, TimeUnit.SECONDS) } finally { done.countDown() } }
        try {
            ContactOperations.cancel(root)
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(lease.cancelled)
            synchronized(VaultLifecycle.lock) {
                val other = key(); ContactOperations.open(other) {}.use { it.check() }
                ContactOperations.cancel(other)
            }
        } finally { release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS)); lease.close() }
    }
}
