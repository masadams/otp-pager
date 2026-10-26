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
        private val invalidators = mutableListOf<() -> Unit>()
        fun check() = synchronized(VaultLifecycle.lock) {
            check(!cancelled && !closed) { "Pairing operation cancelled" }; requireActive()
        }
        /** Local publication only; never perform network I/O or wait for a worker in this callback. */
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
        /** Short nonblocking memory invalidation or UI posting only. No I/O, waits or direct UI work. */
        fun onInvalidate(action: () -> Unit) = synchronized(VaultLifecycle.lock) {
            if (cancelled) notifyInvalidation(action) else if (!closed) invalidators.add(action)
        }
        private fun notifyInvalidation(action: () -> Unit) { try { action() } catch (_: Exception) {} }
        internal fun cancelLocked(): List<() -> Unit> {
            cancelled = true
            invalidators.forEach(::notifyInvalidation); invalidators.clear()
            return callbacks.toList().also { callbacks.clear() }
        }
        override fun close() { synchronized(VaultLifecycle.lock) {
            closed = true; callbacks.clear(); invalidators.clear()
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
