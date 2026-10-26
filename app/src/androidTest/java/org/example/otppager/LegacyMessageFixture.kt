package org.example.otppager

import org.json.JSONObject

/** A pre-upgrade AES outbox snapshot. Production send APIs must never create this. */
internal fun legacyQueuedText(store: SecureStore, text: String) {
    val state = checkNotNull(store.load())
    check(!state.has("pad"))
    val master = Protocol.master(state.getString("secret"))
    val counter = state.getLong("counter") + 1
    val packet = try {
        Protocol.seal(master, state.getString("role")[0], counter,
            JSONObject().put("kind", "text").put("text", text).toString())
    } finally { master.fill(0) }
    state.put("counter", counter)
    state.getJSONArray("outbox").put(JSONObject().put("id", packet.first).put("packet", packet.second)
        .put("kind", "text").put("status", "queued").put("time", System.currentTimeMillis()))
    store.save(state)
}
