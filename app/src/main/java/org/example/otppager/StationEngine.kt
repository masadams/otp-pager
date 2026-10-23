package org.example.otppager

import org.json.JSONArray
import org.json.JSONObject

/** Call only from the single station worker. Save snapshot before publishing it in memory. */
class StationEngine(private val store: SecureStore, private val relay: RelayTransport = RelayClient) {
    var state: JSONObject? = store.load()
        private set
    private var cachedView: View? = null
    private var cacheExpiry = 0L
    init { state?.let { validate(it); transaction(::prune) } }
    private fun validate(s: JSONObject) {
        check(s.getInt("version") == 2)
        Protocol.endpoint(s.getString("endpoint")); Protocol.token(s.getString("token"))
        Protocol.master(s.getString("secret")).fill(0)
        check(s.getString("role") in listOf("A", "B"))
        check(s.getLong("counter") >= 0 && s.getLong("cursor") >= 0)
        window(s)
        check(s.getJSONArray("outbox").length() <= 150 && s.getJSONArray("inbox").length() <= 100)
    }
    private fun transaction(change: (JSONObject) -> Unit) {
        val next = JSONObject(checkNotNull(state).toString())
        change(next); validate(next)
        if (next.toString() == state!!.toString()) return
        store.save(next); state = next; cachedView = null
    }
    fun configure(endpoint: String, token: String, secret: String, role: String) {
        val url = Protocol.endpoint(endpoint); val access = Protocol.token(token)
        Protocol.master(secret).fill(0); require(role == "A" || role == "B")
        val previous = state
        require(previous == null || (previous.getString("secret") == secret && previous.getString("role") == role)) {
            "Forget the existing pairing before changing its secret or station role. Use a NEW secret after forgetting."
        }
        val next = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("version", 2).put("secret", secret).put("role", role).put("counter", 0L).put("cursor", 0L)
            .put("high", 0L).put("seen", JSONArray()).put("outbox", JSONArray()).put("inbox", JSONArray()).put("epoch", "")
        if (previous != null && previous.getString("endpoint") != url) next.put("cursor", 0L).put("epoch", "")
        next.put("endpoint", url).put("token", access)
        store.save(next); state = next; cachedView = null
    }
    fun forget() { store.forget(); state = null; cachedView = null }
    private fun window(s: JSONObject): ReplayWindow {
        val seen = s.getJSONArray("seen")
        return ReplayWindow(s.getLong("high"), (0 until seen.length()).map { seen.getLong(it) }.toSet())
    }
    private fun setWindow(s: JSONObject, w: ReplayWindow) {
        s.put("high", w.high).put("seen", JSONArray(w.received.sorted()))
    }
    private fun items(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
    private fun queue(s: JSONObject, payload: JSONObject, kind: String) {
        require(items(s.getJSONArray("outbox")).count { it.getString("status") == "queued" } < 50) { "Outbox full; sync before sending more" }
        val n = s.getLong("counter"); check(n < Long.MAX_VALUE) { "Pairing counter exhausted; create a new pairing" }
        val master = Protocol.master(s.getString("secret"))
        val (id, wire) = try { Protocol.seal(master, s.getString("role")[0], n + 1, payload.toString()) } finally { master.fill(0) }
        s.put("counter", n + 1)
        s.getJSONArray("outbox").put(JSONObject().put("id", id).put("packet", wire).put("kind", kind)
            .put("status", "queued").put("time", System.currentTimeMillis()))
        prune(s)
    }
    private fun prune(s: JSONObject) {
        val now = System.currentTimeMillis()
        val out = items(s.getJSONArray("outbox"))
        out.filter { it.getString("status") == "queued" && now - it.getLong("time") >= 86400000 }
            .forEach { it.put("status", "expired") }
        val pending = out.filter { it.getString("status") == "queued" }
        val final = out.filter { it.getString("status") != "queued" && it.getString("kind") == "text" && now - it.getLong("time") < 86400000 }.takeLast(100)
        s.put("outbox", JSONArray((final + pending).sortedBy { it.getLong("time") }))
        s.put("inbox", JSONArray(items(s.getJSONArray("inbox")).filter { now - it.getLong("time") < 86400000 }.takeLast(100)))
    }
    fun send(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size in 1..2048) { "Message must be 1–2048 UTF-8 bytes" }
        transaction { s ->
            prune(s)
            queue(s, JSONObject().put("kind", "text").put("text", text), "text")
        }
    }
    fun clearHistory() = transaction { s ->
        s.put("inbox", JSONArray())
        s.put("outbox", JSONArray(items(s.getJSONArray("outbox")).filter { it.getString("status") == "queued" }))
        // Do not clear the counter or replay window when removing message history.
    }
    fun sync(): String {
        if (state != null) transaction(::prune)
        val s = checkNotNull(state) { "Save your link settings first" }
        val master = Protocol.master(s.getString("secret"))
        var ignored = 0
        try {
            val room = Protocol.room(master)
            val response = JSONObject(relay.request(s.getString("endpoint"), room, s.getString("token"), "GET", cursor = s.getLong("cursor")))
            check(response.optInt("protocol") == 2) { "Update the relay container before using this APK" }
            val epoch = response.getString("epoch")
            if (s.getString("epoch").isNotEmpty() && s.getString("epoch") != epoch) {
                transaction { it.put("epoch", epoch).put("cursor", 0L) }
                return "RELAY STORAGE CHANGED // resyncing; replay protection retained"
            }
            val messages = response.getJSONArray("messages")
            require(messages.length() <= 50) { "Relay returned an oversized page" }
            val inbound = if (s.getString("role") == "A") 'B' else 'A'
            transaction { next ->
                next.put("epoch", epoch); prune(next)
                var cursor = next.getLong("cursor")
                for (i in 0 until messages.length()) {
                    val item = messages.getJSONObject(i)
                    val seq = item.getLong("seq"); require(seq > cursor) { "Invalid relay ordering" }
                    val wire = item.getString("packet")
                    if (Protocol.role(wire) == inbound) {
                        // Authentication and payload validation finish before touching replay state.
                        val decoded = try { Protocol.open(master, wire, inbound) } catch (_: Exception) { ignored++; null }
                        if (decoded != null && window(next).accepts(decoded.counter)) {
                            val payload = try { JSONObject(decoded.payload) } catch (_: Exception) { null }
                            val kind = payload?.optString("kind")
                            if (kind == "text" && payload?.opt("text") is String && payload.getString("text").toByteArray(Charsets.UTF_8).size in 1..2048) {
                                if (items(next.getJSONArray("outbox")).count { it.getString("status") == "queued" } >= 50) break
                                next.getJSONArray("inbox").put(JSONObject().put("packet", wire).put("id", decoded.id).put("time", System.currentTimeMillis()))
                                queue(next, JSONObject().put("kind", "receipt").put("for", decoded.id), "receipt")
                                setWindow(next, window(next).accept(decoded.counter))
                            } else if (kind == "receipt" && payload?.opt("for") is String && Regex("[a-f0-9]{32}").matches(payload.getString("for"))) {
                                items(next.getJSONArray("outbox")).filter { it.getString("id") == payload.getString("for") && it.getString("kind") == "text" }
                                    .forEach { it.put("status", "delivered") }
                                setWindow(next, window(next).accept(decoded.counter))
                            } else { ignored++ }
                        }
                    } else if (Protocol.role(wire) == null) { ignored++ }
                    cursor = seq; next.put("cursor", cursor)
                }
                prune(next)
            }
            // Retry the exact durable ciphertext; a timeout never creates a new message ID/counter.
            val pending = items(checkNotNull(state).getJSONArray("outbox")).filter { it.getString("status") == "queued" }.take(10)
            for (entry in pending) {
                relay.request(s.getString("endpoint"), room, s.getString("token"), "POST", JSONObject().put("packet", entry.getString("packet")).toString())
                transaction { next ->
                    items(next.getJSONArray("outbox")).firstOrNull { it.getString("id") == entry.getString("id") }?.put("status", "relayed")
                    prune(next)
                }
            }
            return "Connected" + if (ignored > 0) " · $ignored invalid/legacy packets skipped" else ""
        } finally { master.fill(0) }
    }
    data class Message(val incoming: Boolean, val text: String, val status: String, val time: Long)
    data class View(val role: String, val fingerprint: String, val messages: List<Message>, val pending: Int)
    fun view(): View {
        cachedView?.let { if (System.currentTimeMillis() < cacheExpiry) return it }
        val s = state ?: return View("—", "NOT PAIRED", emptyList(), 0)
        val master = Protocol.master(s.getString("secret"))
        return try {
            val own = s.getString("role")[0]; val peer = if (own == 'A') 'B' else 'A'
            val rows = mutableListOf<Message>()
            for (entry in items(s.getJSONArray("inbox"))) {
                if (System.currentTimeMillis() - entry.getLong("time") >= 86400000) continue
                val text = JSONObject(Protocol.open(master, entry.getString("packet"), peer).payload).getString("text")
                rows.add(Message(true, text, "received", entry.getLong("time")))
            }
            for (entry in items(s.getJSONArray("outbox")).filter { it.getString("kind") == "text" }) {
                if (System.currentTimeMillis() - entry.getLong("time") >= 86400000) continue
                val text = JSONObject(Protocol.open(master, entry.getString("packet"), own).payload).getString("text")
                rows.add(Message(false, text, entry.getString("status"), entry.getLong("time")))
            }
            cacheExpiry = rows.minOfOrNull { it.time + 86400000L } ?: Long.MAX_VALUE
            View(own.toString(), Protocol.fingerprint(master), rows.sortedBy { it.time }.takeLast(100),
                items(s.getJSONArray("outbox")).count { it.getString("status") == "queued" }).also { cachedView = it }
        } finally { master.fill(0) }
    }
}
