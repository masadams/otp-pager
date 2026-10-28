package org.example.otppager

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Strict chats (docs/STRICT_CHATS.md): the contact vault gets an inner AES-256-GCM layer whose
 * key is derived from a 32-byte share held only by the relay (and in this process's memory
 * once fetched). The phone must check in with the relay before the share's deadline, or the
 * relay discards it and this chat can never be opened again on this phone, even from a full
 * storage copy. A wipe deletes the share. The share alone decrypts nothing: the outer vault
 * layer still needs this phone's Keystore key.
 */
internal object StrictChat {
    class Locked : IllegalStateException("This strict chat is locked. Connect to the server to open it.")
    class Gone : IllegalStateException("This strict chat can no longer be opened: the server no longer holds its key share.")
    val DEADLINES = listOf(12 * 3600_000L, 3 * 86_400_000L, 14 * 86_400_000L)
    fun label(deadline: Long) = when (deadline) { 0L -> "Normal chat"; 12 * 3600_000L -> "Strict · check in every 12 hours"
        else -> "Strict · check in every ${deadline / 86_400_000L} days" }
    private val b64e = Base64.getUrlEncoder().withoutPadding(); private val b64d = Base64.getUrlDecoder()
    private val cache = mutableMapOf<String, ByteArray>()
    @Synchronized fun cached(root: String): ByteArray? = cache[root]?.copyOf()
    @Synchronized fun remember(root: String, share: ByteArray) { require(share.size == 32); cache[root]?.fill(0); cache[root] = share.copyOf() }
    @Synchronized fun forget(root: String) { cache.remove(root)?.fill(0) }

    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    /** One share room per phone of the pair, derived from the control credential. */
    fun room(credential: ByteArray, role: String): String {
        require(credential.size == 32 && role in listOf("A", "B"))
        val prk = hmac(ByteArray(32), credential)
        try { return PadProtocol.hex(hmac(prk, "OTP Pager strict share v1 / $role".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))) }
        finally { prk.fill(0) }
    }
    private fun key(share: ByteArray, root: String) = hmac(share, "OTP Pager strict vault v1\u0000$root".toByteArray(Charsets.UTF_8))
    fun newShare(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
    fun encode(share: ByteArray): String = b64e.encodeToString(share)
    fun decode(value: String): ByteArray = b64d.decode(value).also { require(it.size == 32) }

    fun isWrapped(stored: JSONObject?) = stored?.has("strict") == true
    /** Routing kept outside the inner layer so a locked chat can still ask for its share. */
    fun wrap(root: String, state: JSONObject, share: ByteArray): JSONObject {
        val s = state.getJSONObject("strictShare")
        val key = key(share, root)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD("OTP Pager strict vault v1\u0000$root".toByteArray(Charsets.UTF_8))
            val clear = state.toString().toByteArray(Charsets.UTF_8)
            val sealed = try { cipher.doFinal(clear) } finally { clear.fill(0) }
            return JSONObject().put("version", 6).put("strict", JSONObject().put("v", 1)
                .put("endpoint", state.getString("endpoint")).put("room", s.getString("room")).put("capability", s.getString("capability"))
                .put("iv", b64e.encodeToString(iv)).put("ct", Base64.getEncoder().encodeToString(sealed)))
        } finally { key.fill(0) }
    }
    fun unwrap(root: String, stored: JSONObject, share: ByteArray): JSONObject {
        val w = stored.getJSONObject("strict"); require(w.getInt("v") == 1)
        val key = key(share, root)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64d.decode(w.getString("iv"))))
            cipher.updateAAD("OTP Pager strict vault v1\u0000$root".toByteArray(Charsets.UTF_8))
            val clear = cipher.doFinal(Base64.getDecoder().decode(w.getString("ct")))
            return try { JSONObject(String(clear, Charsets.UTF_8)) } finally { clear.fill(0) }
        } finally { key.fill(0) }
    }
    /** Engine construction: the inner state, or [Locked] when the share is not in memory yet. */
    fun open(root: String, stored: JSONObject?): JSONObject? {
        if (!isWrapped(stored)) return stored
        val share = cached(root) ?: throw Locked()
        return try { unwrap(root, stored!!, share) } finally { share.fill(0) }
    }

    /** Fetches the share (a relay check-in) for a locked strict chat and keeps it in memory. */
    fun unlock(context: Context, root: String, relay: RelayTransport = RelayClient) {
        val stored = checkNotNull(SecureStore(context, root).load()) { "Unknown chat" }
        val w = stored.getJSONObject("strict")
        val share = fetch(relay, w.getString("endpoint"), w.getString("room"), w.getString("capability"))
        try { unwrap(root, stored, share).let { } ; remember(root, share) } finally { share.fill(0) }
    }
    fun fetch(relay: RelayTransport, endpoint: String, room: String, capability: String): ByteArray = try {
        decode(JSONObject(relay.share(endpoint, room, capability, "GET")).getString("share"))
    } catch (e: RelayHttpException) { if (e.status == 404) throw Gone() else throw e }
}
