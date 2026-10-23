package org.example.otppager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Encrypted contact index. Existing station-v2 data stays in place; keys are never copied. */
class ChatBook(context: Context, indexNamespace: String = "chat-index", legacyNamespace: String = "station-v2") {
    companion object { const val MAX_CHATS = 12 }
    private val context = context.applicationContext
    private val index = SecureStore(context, indexNamespace)
    private val claims = PairingClaims(SecureStore(context, "$indexNamespace-claims"))
    private var data = index.load() ?: JSONObject().put("version", 2).put("active", legacyNamespace)
        .put("chats", JSONArray().put(JSONObject().put("id", legacyNamespace).put("name", "Chat 1"))).also {
            // Bring all previous pad retirements into the cross-chat guard before enabling new chats.
            claims.migrate(SecureStore(context, legacyNamespace))
            index.save(it)
        }
    private var roundRobin = 0
    data class Chat(val id: String, val name: String)
    data class Summary(val unread: Int = 0, val queued: Int = 0, val problem: Boolean = false)
    private val summaries = mutableMapOf<String, Summary>()
    init { validate(data); refreshSummaries() }
    private fun validate(value: JSONObject) {
        val chats = value.getJSONArray("chats")
        check(chats.length() in 1..MAX_CHATS)
        val ids = mutableSetOf<String>(); val names = mutableSetOf<String>()
        for (i in 0 until chats.length()) {
            val chat = chats.getJSONObject(i)
            check(Regex("[a-z0-9-]{1,30}").matches(chat.getString("id")) && ids.add(chat.getString("id")))
            check(name(chat.getString("name")) == chat.getString("name") && names.add(chat.getString("name").lowercase(java.util.Locale.ROOT)))
        }
        check(value.getString("active") in ids)
    }
    private fun save(next: JSONObject) { validate(next); index.save(next); data = next }
    private fun name(value: String) = value.trim().also {
        require(it.length in 1..32 && it.none { c -> c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() }) { "Use a chat name of 1–32 characters without control characters" }
    }
    fun chats(): List<Chat> = data.getJSONArray("chats").let { list -> (0 until list.length()).map { Chat(list.getJSONObject(it).getString("id"), list.getJSONObject(it).getString("name")) } }
    fun active(): Chat = chats().first { it.id == data.getString("active") }
    fun select(id: String) { require(chats().any { it.id == id }); save(JSONObject(data.toString()).put("active", id)) }
    fun rename(value: String) {
        val clean = name(value)
        require(chats().none { it.id != active().id && it.name.equals(clean, ignoreCase = true) }) { "Another chat already uses that name" }
        val next = JSONObject(data.toString()); val entries = next.getJSONArray("chats")
        for (i in 0 until entries.length()) if (entries.getJSONObject(i).getString("id") == active().id) entries.getJSONObject(i).put("name", clean)
        save(next)
    }
    fun add(value: String, copyServerFrom: String? = active().id): Chat {
        check(chats().size < MAX_CHATS) { "This version supports up to $MAX_CHATS separate chats" }
        val clean = name(value)
        require(chats().none { it.name.equals(clean, ignoreCase = true) }) { "Another chat already uses that name" }
        val source = copyServerFrom?.let { id -> require(chats().any { it.id == id }); SecureStore(context, id).load() }
        val chat = Chat("chat-" + UUID.randomUUID().toString().replace("-", "").take(20), clean)
        val next = JSONObject(data.toString()); next.getJSONArray("chats").put(JSONObject().put("id", chat.id).put("name", chat.name))
        save(next) // A failure afterward leaves a recoverable empty chat, never a second owner of old key material.
        if (source != null) engine(chat.id).configure(source.getString("endpoint"), source.getString("token"), Protocol.randomSecret(), "A")
        refresh(chat.id); return chat
    }
    fun engine(id: String = active().id, relay: RelayTransport = RelayClient): StationEngine {
        require(chats().any { it.id == id }) { "Unknown chat" }
        return StationEngine(SecureStore(context, id), relay, claims)
    }
    fun forgetActive() { SecureStore(context, active().id).forget(); refresh() }
    fun summary(id: String) = summaries[id] ?: Summary()
    fun unreadElsewhere() = summaries.filterKeys { it != active().id }.values.sumOf { it.unread }
    fun refresh(id: String = active().id) {
        try {
            val s = SecureStore(context, id).load()
            val now = System.currentTimeMillis()
            fun live(entry: JSONObject) = now - entry.getLong("time") < 86400000
            val inbox = s?.getJSONArray("inbox"); val out = s?.getJSONArray("outbox")
            summaries[id] = Summary(
                if (inbox == null) 0 else (0 until inbox.length()).count { live(inbox.getJSONObject(it)) && !inbox.getJSONObject(it).optBoolean("read") },
                if (out == null) 0 else (0 until out.length()).count { live(out.getJSONObject(it)) && out.getJSONObject(it).getString("status") == "queued" })
        } catch (_: Exception) { summaries[id] = Summary(problem = true) }
    }
    private fun refreshSummaries() { chats().forEach { refresh(it.id) } }
    /** One inactive chat per foreground poll bounds battery/network cost. An offline chat does not block others. */
    fun syncAnother(relay: RelayTransport = RelayClient) {
        val others = chats().filter { it.id != active().id }
        if (others.isEmpty()) return
        val chat = others[roundRobin.mod(others.size)]; roundRobin = (roundRobin + 1).mod(others.size)
        try {
            val peer = engine(chat.id, relay)
            if (peer.state != null && peer.view().pairingComplete) peer.sync()
            refresh(chat.id)
        } catch (_: Exception) { summaries[chat.id] = summary(chat.id).copy(problem = true) }
    }
}

/** A pad or mailbox secret can never be assigned to two local chats. Retained on Forget pairing. */
class PairingClaims(private val store: SecureStore) {
    companion object { private val lock = Any() }
    private var failed = false
    private fun claim(kind: String, key: String, owner: String, existing: Boolean) = synchronized(lock) {
        check(!failed) { "Pairing registry write failed; restart the app" }
        val data = store.load() ?: JSONObject().put("version", 2).put("pads", JSONObject()).put("rooms", JSONObject())
        val entries = data.getJSONObject(kind)
        if (entries.has(key)) {
            require(existing && entries.getString(key) == owner) { "This pairing material was already used. Create a fresh pairing for each friend." }
        } else {
            entries.put(key, owner)
            try { store.save(data) } catch (e: Exception) { failed = true; throw e }
        }
    }
    fun room(secret: String, owner: String, existing: Boolean) {
        val master = Protocol.master(secret)
        try { claim("rooms", Protocol.room(master), owner, existing) } finally { master.fill(0) }
    }
    fun pad(id: String, owner: String) { require(Regex("[a-f0-9]{32}").matches(id)); claim("pads", id, owner, existing = true) }
    fun migrate(profile: SecureStore) {
        profile.load()?.let { room(it.getString("secret"), profile.namespace, existing = true) }
        profile.sibling("pads").load()?.getJSONObject("pads")?.keys()?.forEach { pad(it, profile.namespace) }
    }
}
