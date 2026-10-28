package org.example.otppager

/** Main-thread discovery state. Worker completions must return through [post].
 * Transfer failure is terminal here: only the pairing protocol may decide pad recovery.
 */
internal class UsbPairDiscovery(
    private val clock: () -> Long,
    private val live: () -> Boolean,
    private val execute: (() -> Unit) -> Unit,
    private val post: (() -> Unit) -> Unit,
    private val note: (String) -> Unit,
    lifetimeMillis: Long = 180_000
) {
    enum class State { SEARCHING, PERMISSION, DENIED, NEGOTIATING, TRANSFER, EXPIRED, STOPPED }
    var state = State.SEARCHING
        private set
    private val deadline = clock() + lifetimeMillis

    fun inspectable(): Boolean {
        if (!live() || state != State.SEARCHING) return false
        if (clock() >= deadline) {
            state = State.EXPIRED
            note("No compatible USB data connection found. Check cable/USB host direction, then close and retry. QR setup remains available.")
            return false
        }
        return true
    }
    fun requestPermission(request: () -> Unit) {
        if (!inspectable()) return
        state = State.PERMISSION
        try { request() } catch (_: Exception) {
            state = State.DENIED
            note("USB permission could not be requested. Tap Retry USB permission when ready.")
        }
    }
    fun permissionResult(granted: Boolean): Boolean {
        if (!live() || state != State.PERMISSION) return false
        state = if (granted) State.SEARCHING else State.DENIED
        if (!granted) note("USB permission declined. Tap Retry USB permission when ready.")
        return granted && inspectable()
    }
    fun retryPermission(): Boolean {
        if (!live() || state != State.DENIED) return false
        state = State.SEARCHING
        return inspectable() // A retry never extends the original discovery deadline.
    }
    fun negotiate(switchMode: () -> Unit) {
        if (!inspectable()) return
        state = State.NEGOTIATING
        note("Checking USB accessory support…")
        fun complete(failed: Boolean) = post {
            if (live() && state == State.NEGOTIATING) {
                state = State.SEARCHING
                if (inspectable()) note(if (failed)
                    "Could not establish Android accessory mode. Check cable/USB host direction; retrying…"
                else "USB mode changed · waiting for reconnection/permission…")
            }
        }
        try {
            execute {
                val failed = try { switchMode(); false } catch (_: Exception) { true }
                complete(failed)
            }
        } catch (_: Exception) { complete(true) }
    }
    fun startTransfer(): Boolean {
        if (!inspectable()) return false
        state = State.TRANSFER
        return true
    }
    fun stop() { state = State.STOPPED }
}
