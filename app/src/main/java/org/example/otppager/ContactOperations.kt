package org.example.otppager

import java.util.concurrent.Executors

/** Cancellation flags are synchronous; potentially blocking close callbacks never delay key cleanup. */
internal object ContactOperations {
    private val stopped = mutableSetOf<String>()
    private val active = mutableMapOf<String, MutableSet<Lease>>()
    private val closer = Executors.newFixedThreadPool(2) { task -> Thread(task, "pager-close").apply { isDaemon = true } }
    class Lease internal constructor(private val key: String, private val requireActive: () -> Unit) : AutoCloseable {
        @Volatile var cancelled = false
            private set
        private var closed = false
        private val callbacks = mutableListOf<() -> Unit>()
        fun check() = synchronized(VaultLifecycle.lock) {
            check(!cancelled && !closed) { "Pairing operation cancelled" }; requireActive()
        }
        /** Only short local publication belongs here; never perform network I/O in this callback. */
        fun publish(action: () -> Unit): Boolean = synchronized(VaultLifecycle.lock) {
            if (cancelled || closed) return@synchronized false
            try { requireActive() } catch (_: IllegalStateException) { return@synchronized false }
            action(); true
        }
        fun onCancel(action: () -> Unit) {
            val run = synchronized(VaultLifecycle.lock) {
                if (cancelled) true else { if (!closed) callbacks.add(action); false }
            }
            if (run) dispatch(action)
        }
        internal fun cancelLocked(): List<() -> Unit> {
            cancelled = true
            return callbacks.toList().also { callbacks.clear() }
        }
        override fun close() { synchronized(VaultLifecycle.lock) {
            closed = true; callbacks.clear()
            active[key]?.let { it.remove(this); if (it.isEmpty()) active.remove(key) }
        } }
    }
    fun open(key: String, requireActive: () -> Unit): Lease = synchronized(VaultLifecycle.lock) {
        requireActive(); check(key !in stopped) { "Pairing operation cancelled" }
        Lease(key, requireActive).also { active.getOrPut(key) { mutableSetOf() }.add(it) }
    }
    fun cancel(key: String) {
        val callbacks = synchronized(VaultLifecycle.lock) {
            stopped.add(key)
            active.remove(key)?.flatMap { it.cancelLocked() } ?: emptyList()
        }
        callbacks.forEach(::dispatch)
    }
    fun isStopped(key: String) = synchronized(VaultLifecycle.lock) { key in stopped }
    private fun dispatch(action: () -> Unit) { closer.execute { try { action() } catch (_: Exception) {} } }
}
