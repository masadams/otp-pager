package org.example.otppager

import java.util.concurrent.Executors

/**
 * User-initiated, local-only wipe of one conversation on this device.
 *
 * Runs on its own thread so it never queues behind [StationRuntime.worker], which can be
 * blocked by network I/O or pairing. Destruction itself is [VaultRetirement]: in-process
 * denial and cancellation first, then a durable terminal marker, then independent key and
 * file deletion. Nothing here sends a packet or claims anything about the peer device.
 * A submitted wipe is never cancelled, including when the requesting screen goes away.
 */
internal object LocalWipe {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pager-wipe-contact").apply { isDaemon = true }
    }

    /**
     * Wipe every chat on this phone. The freeze latch is set synchronously inside
     * [GlobalRetirement.submit]; this thread only waits for the independent cleanup.
     */
    fun submitAll(context: android.content.Context, book: ChatBook?, done: (Result<GlobalRetirementMachine.Result>) -> Unit) {
        executor.execute {
            val outcome = runCatching {
                // Best effort: an empty registry lets a never-used phone produce complete wipe evidence.
                try { book?.ensureClaimsRegistry() } catch (_: Exception) {}
                try { book?.preparePeerWipes() } catch (_: Exception) {}
                try { book?.prepareShareDeletions() } catch (_: Exception) {}
                GlobalRetirement.submit(context.applicationContext).get()
            }
            try { done(outcome) } catch (_: Exception) {}
            sendPeerRequests(context)
        }
    }
    private val sender = Executors.newSingleThreadExecutor { task -> Thread(task, "pager-peer-wipe").apply { isDaemon = true } }
    /** Network is never on the wipe thread: destruction must not wait on the relay (S05). */
    fun sendPeerRequests(context: android.content.Context) {
        val app = context.applicationContext
        sender.execute { try { PeerWipe.pump(app) } catch (_: Exception) {} } // Also strict share deletions, which are always on.
    }

    /** [done] runs on the wipe thread; post to the UI thread before touching views. */
    fun submit(book: ChatBook, id: String, done: (Result<VaultRetirement.Result>) -> Unit) {
        executor.execute {
            val outcome = runCatching { book.wipeLocal(id) }
            try { done(outcome) } catch (_: Exception) { /* A closed screen never affects destruction. */ }
            sendPeerRequests(book.context)
        }
    }
}
