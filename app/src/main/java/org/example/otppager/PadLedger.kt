package org.example.otppager

import org.json.JSONObject

/** Never deleted by Forget pairing. Reservations precede encryption; interrupted sends waste slots. */
class PadLedger(private val store: SecureStore) {
    private var failed = false
    private fun save(s: JSONObject) {
        try { store.save(s) } catch (e: Exception) { failed = true; throw e }
    }
    private fun load(): JSONObject { check(!failed) { "Pad ledger write failed; restart station before retrying" }; return store.load() ?: JSONObject().put("version", 2).put("pads", JSONObject()) }
    fun register(id: String) {
        val s = load(); val pads = s.getJSONObject("pads")
        require(!pads.has(id)) { "This pad was already installed. Exchange a fresh pad; re-import is blocked." }
        pads.put(id, 0); save(s)
    }
    fun high(id: String): Long {
        val pads = load().getJSONObject("pads")
        check(pads.has(id)) { "Pad reservation ledger missing; exchange a fresh pad" }
        return pads.getLong(id).also { check(it in 0..PadProtocol.SLOTS.toLong()) }
    }
    fun reserve(id: String): Long {
        val s = load(); val pads = s.getJSONObject("pads")
        check(pads.has(id)) { "Pad reservation ledger missing" }
        val next = pads.getLong(id) + 1
        check(next <= PadProtocol.SLOTS) { "Pad exhausted; exchange a fresh pad" }
        pads.put(id, next); save(s)
        return next
    }
}
