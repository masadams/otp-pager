package org.example.otppager

/** Only call after authenticating a command for the current pairing generation.
 * Preparing/persisting an acknowledgement must not veto local retirement. Transport
 * is invoked only after retirement; its failure cannot change local evidence.
 */
internal object PeerWipeRecipient {
    fun <T> retire(prepare: () -> Unit, destroy: () -> T, finish: (T) -> Unit, dispatch: () -> Unit): T {
        try { prepare() } catch (_: Exception) { /* Remote outcome may be unknown. */ }
        val result = destroy()
        try { finish(result) } catch (_: Exception) { /* No final ACK if persistence failed. */ }
        try { dispatch() } catch (_: Exception) { /* Best-effort retained dispatch. */ }
        return result
    }
}
