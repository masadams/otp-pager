package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PeerWipeRecipientTest {
    @Test fun blockedTransportCannotDelayLocalRetirement() {
        val retired = CountDownLatch(1); val posting = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val task = pool.submit<Boolean> {
                PeerWipeRecipient.retire({}, { retired.countDown(); true }, { assertTrue(it) }, {
                    assertEquals(0L, retired.count)
                    posting.countDown(); check(release.await(5, TimeUnit.SECONDS))
                })
            }
            assertTrue(posting.await(5, TimeUnit.SECONDS))
            assertEquals(0L, retired.count)
            assertFalse(task.isDone)
            release.countDown(); assertTrue(task.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun acknowledgementStorageAndTransportFailuresCannotVetoDestruction() {
        val events = mutableListOf<String>()
        val result = PeerWipeRecipient.retire(
            { events.add("prepare"); error("Disk full") },
            { events.add("destroy"); "local evidence" },
            { events.add("finish"); error("Cannot persist ACK") },
            { events.add("dispatch"); error("Offline") })
        assertEquals("local evidence", result)
        assertEquals(listOf("prepare", "destroy", "finish", "dispatch"), events)
    }
    @Test fun incompleteRetirementIsNeverPromotedToSuccess() {
        var final: Boolean? = null
        val result = PeerWipeRecipient.retire({}, { false }, { final = it }, {})
        assertFalse(result); assertEquals(false, final)
    }
}
