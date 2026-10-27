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

    /** [done] runs on the wipe thread; post to the UI thread before touching views. */
    fun submit(book: ChatBook, id: String, done: (Result<VaultRetirement.Result>) -> Unit) {
        executor.execute {
            val outcome = runCatching { book.wipeLocal(id) }
            try { done(outcome) } catch (_: Exception) { /* A closed screen never affects destruction. */ }
        }
    }
}
