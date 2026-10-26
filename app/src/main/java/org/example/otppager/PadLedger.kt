package org.example.otppager

import org.json.JSONObject

/** Never deleted by Forget pairing. Reservations precede encryption; interrupted sends waste slots. */
class PadLedger(private val store: SecureStore) {
    companion object { private val reservationLock = VaultLifecycle.lock }
    private var failed = false
    private fun save(s: JSONObject) {
        try { store.save(s) } catch (e: Exception) { failed = true; throw e }
    }
    private fun load(): JSONObject { check(!failed) { "Pad ledger write failed; restart station before retrying" }; return store.load() ?: JSONObject().put("version", 2).put("pads", JSONObject()) }
    fun register(id: String, capacity: Int = PadProtocol.SLOTS) = synchronized(reservationLock) {
        val s = load(); val pads = s.getJSONObject("pads")
        require(!pads.has(id)) { "This pad was already installed. Exchange a fresh pad; re-import is blocked." }
        require(capacity in listOf(PadProtocol.SLOTS, 2 * PadProtocol.MATERIAL_BYTES / 2 / PadProtocol.STRIDE, PadProtocol.MAX_SLOTS))
        val capacities = s.optJSONObject("capacities") ?: JSONObject().also { s.put("capacities", it) }
        capacities.put(id, capacity)
        pads.put(id, 0); save(s)
    }
    fun high(id: String): Long {
        val pads = load().getJSONObject("pads")
        check(pads.has(id)) { "Pad reservation ledger missing; exchange a fresh pad" }
        val capacity = load().optJSONObject("capacities")?.optInt(id, PadProtocol.SLOTS) ?: PadProtocol.SLOTS
        return pads.getLong(id).also { check(capacity in 1..PadProtocol.MAX_SLOTS && it in 0..capacity.toLong()) }
    }
    fun reserve(id: String): Long = synchronized(reservationLock) {
        val s = load(); val pads = s.getJSONObject("pads")
        check(pads.has(id)) { "Pad reservation ledger missing" }
        val next = pads.getLong(id) + 1
        val capacity = s.optJSONObject("capacities")?.optInt(id, PadProtocol.SLOTS) ?: PadProtocol.SLOTS
        check(capacity in 1..PadProtocol.MAX_SLOTS && next <= capacity) { "Pad exhausted; exchange a fresh pad" }
        pads.put(id, next); save(s)
        next
    }
}
