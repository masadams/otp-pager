package org.example.otppager

import org.json.JSONObject

/** Small independent encrypted vault: typing never rewrites or allocates pad material. */
class ChatDraft(private val store: SecureStore) {
    data class Entry(val id: String, val text: String)
    companion object {
        const val MAX_BYTES = 32768
        const val RETENTION = 86400000L
        fun validId(id: String) = Regex("[a-f0-9-]{36}").matches(id)
    }
    fun read(room: String, acceptedId: String = "", now: Long = System.currentTimeMillis()): Entry? {
        val saved = store.load() ?: return null
        val id = saved.getString("id"); val text = saved.getString("text"); val time = saved.getLong("time")
        require(validId(id) && text.toByteArray(Charsets.UTF_8).size in 1..MAX_BYTES && time >= 0)
        if (saved.getString("room") != room || id == acceptedId || now - time >= RETENTION || time > now + 300000L) {
            clear(); return null
        }
        return Entry(id, text)
    }
    fun save(room: String, id: String, text: String, now: Long = System.currentTimeMillis()) {
        require(Regex("[a-f0-9]{64}").matches(room) && validId(id) && now >= 0)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        if (text.isEmpty()) { clear(); return }
        val old = store.load()
        if (old != null && old.optString("id") == id && old.optString("text") == text && old.optString("room") == room) return
        store.save(JSONObject().put("version", 2).put("id", id).put("text", text).put("room", room).put("time", now))
    }
    fun clear() = store.forget()
}
