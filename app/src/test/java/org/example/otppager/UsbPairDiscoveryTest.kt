package org.example.otppager

import org.junit.Assert.*
import org.junit.Test

class UsbPairDiscoveryTest {
    private class Harness {
        var now = 0L
        var live = true
        val workers = ArrayDeque<() -> Unit>()
        val callbacks = ArrayDeque<() -> Unit>()
        val notes = mutableListOf<String>()
        val discovery = UsbPairDiscovery({ now }, { live }, { workers.addLast(it) }, { callbacks.addLast(it) }, notes::add)
        fun finishWorker() { workers.removeFirst()(); callbacks.removeFirst()() }
    }
    @Test fun failedNegotiationCanRetryWithoutOverlappingWorkers() {
        val h = Harness(); var attempts = 0
        h.discovery.negotiate { attempts++; error("AOA unavailable") }
        h.discovery.negotiate { fail("Overlapping negotiation") }
        assertEquals(1, h.workers.size)
        h.finishWorker()
        assertTrue(h.discovery.inspectable())
        h.discovery.negotiate { attempts++ }
        h.finishWorker()
        assertEquals(2, attempts)
        assertTrue(h.discovery.inspectable())
        assertTrue(h.notes.last().contains("waiting for reconnection"))
    }
    @Test fun denialRequiresExplicitRetryAndUnsolicitedGrantDoesNothing() {
        val h = Harness(); var prompts = 0
        assertFalse(h.discovery.permissionResult(true))
        h.discovery.requestPermission { prompts++ }
        h.discovery.requestPermission { prompts++ }
        assertFalse(h.discovery.permissionResult(false))
        repeat(3) { h.discovery.requestPermission { prompts++ } }
        assertEquals(1, prompts)
        assertFalse(h.discovery.permissionResult(true))
        assertTrue(h.discovery.retryPermission())
        h.discovery.requestPermission { prompts++ }
        assertTrue(h.discovery.permissionResult(true))
        assertEquals(2, prompts)
    }
    @Test fun stoppedOrRetiredOwnerCannotPublishLateWorkerResults() {
        for (stop in listOf(false, true)) {
            val h = Harness()
            h.discovery.negotiate { error("Detached") }
            val before = h.notes.toList()
            if (stop) h.discovery.stop() else h.live = false
            h.finishWorker()
            assertEquals(before, h.notes)
            assertFalse(h.discovery.inspectable())
            assertFalse(h.discovery.startTransfer())
            assertFalse(h.discovery.permissionResult(true))
        }
    }
    @Test fun negotiationAndPermissionRetriesCannotExtendDeadline() {
        val h = Harness()
        h.discovery.negotiate { }
        h.now = 180_000
        h.finishWorker()
        assertEquals(UsbPairDiscovery.State.EXPIRED, h.discovery.state)
        assertFalse(h.discovery.inspectable())
        val denied = Harness()
        denied.discovery.requestPermission { }
        denied.discovery.permissionResult(false)
        denied.now = 180_000
        assertFalse(denied.discovery.retryPermission())
        assertEquals(UsbPairDiscovery.State.EXPIRED, denied.discovery.state)
    }
    @Test fun transferCannotBeRestartedByDiscoveryOrPermissionCallbacks() {
        val h = Harness()
        assertTrue(h.discovery.startTransfer())
        assertFalse(h.discovery.startTransfer())
        assertFalse(h.discovery.permissionResult(true))
        assertFalse(h.discovery.retryPermission())
        h.discovery.negotiate { fail("Transfer restarted") }
        assertTrue(h.workers.isEmpty())
    }
    @Test fun permissionAndExecutorExceptionsDoNotStrandDiscovery() {
        val h = Harness()
        h.discovery.requestPermission { error("Request failed") }
        assertEquals(UsbPairDiscovery.State.DENIED, h.discovery.state)
        assertTrue(h.discovery.retryPermission())
        val callbacks = ArrayDeque<() -> Unit>()
        val d = UsbPairDiscovery({ 0L }, { true }, { error("Executor unavailable") }, { callbacks.addLast(it) }, {})
        d.negotiate { fail("Must not run") }
        callbacks.removeFirst()()
        assertTrue(d.inspectable())
    }
}
