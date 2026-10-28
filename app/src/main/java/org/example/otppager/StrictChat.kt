package org.example.otppager

import android.content.Context
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Strict chats (docs/STRICT_CHAT_PROTOCOL.md, owner decision 2026-10-28: combine a signed short
 * lease with a check-in deadline).
 *
 * The contact vault gets an inner AES-256-GCM layer keyed from a 32-byte share that only the
 * relay keeps. To open the chat the phone signs an OTPSTR01 UNLOCK with its non-exportable
 * device key; the relay answers with a GRANT signed by its pinned authority key, valid for at
 * most 60 s. The share then lives only in this process's memory until that lease ends or the
 * app leaves the foreground, and is renewed by each foreground check. Every unlock is also a
 * check-in: a phone that stays silent past its deadline loses the share for good, as does a wipe.
 * The share alone decrypts nothing: the outer vault layer still needs this phone's Keystore key.
 */
internal object StrictChat {
    class Locked : IllegalStateException("This strict chat is locked. Connect to the server to open it.")
    class Gone : IllegalStateException("This strict chat can no longer be opened: the server no longer holds its key share.")
    val DEADLINES = listOf(12 * 3600_000L, 3 * 86_400_000L, 14 * 86_400_000L)
    fun label(deadline: Long) = when (deadline) { 0L -> "Normal chat (works offline)"; 12 * 3600_000L -> "Strict · check in every 12 hours"
        else -> "Strict · check in every ${deadline / 86_400_000L} days" }
    private val b64e = Base64.getUrlEncoder().withoutPadding(); private val b64d = Base64.getUrlDecoder()

    private class Lease(val share: ByteArray, val key: String, val expiry: ScheduledFuture<*>)
    private val leases = mutableMapOf<String, Lease>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pager-strict-lease").apply { isDaemon = true } }
    @Synchronized fun cached(root: String): ByteArray? = leases[root]?.share?.copyOf()
    /** Keep [share] for [millis] (≤ 60 s). [key] names the live engines to lock when it ends. */
    @Synchronized fun remember(root: String, share: ByteArray, millis: Long, key: String) {
        require(share.size == 32 && millis in 1..StrictChatProtocol.MAX_LEASE_MS)
        drop(root)
        val expiry = timer.schedule({ lock(root) }, millis, TimeUnit.MILLISECONDS)
        leases[root] = Lease(share.copyOf(), key, expiry)
    }
    @Synchronized private fun drop(root: String): String? = leases.remove(root)?.let { it.share.fill(0); it.expiry.cancel(false); it.key }
    /** Lease ended, app backgrounded, wipe or forget: zero the share and clear decrypted engine state. */
    fun lock(root: String) { drop(root)?.let(StationEngine::lockStrict) }
    fun lockAll() { synchronized(this) { leases.keys.toList() }.forEach(::lock) }
    fun forget(root: String) = lock(root)

    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    /** One share room per phone of the pair, derived from the control credential. */
    fun room(credential: ByteArray, role: String): String {
        require(credential.size == 32 && role in listOf("A", "B"))
        val prk = hmac(ByteArray(32), credential)
        try { return PadProtocol.hex(hmac(prk, "OTP Pager strict share v1 / $role".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))) }
        finally { prk.fill(0) }
    }
    private fun aad(root: String) = "OTP Pager strict vault v1\u0000$root".toByteArray(Charsets.UTF_8)
    private fun key(share: ByteArray, root: String) = hmac(share, aad(root))
    fun newShare(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
    fun encode(share: ByteArray): String = b64e.encodeToString(share)

    fun isWrapped(stored: JSONObject?) = stored?.has("strict") == true
    /** Only routing and public keys stay outside the inner layer, so a locked chat can ask to unlock. */
    fun wrap(root: String, state: JSONObject, share: ByteArray): JSONObject {
        val s = state.getJSONObject("strictShare")
        val key = key(share, root)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(root))
            val clear = state.toString().toByteArray(Charsets.UTF_8)
            val sealed = try { cipher.doFinal(clear) } finally { clear.fill(0) }
            return JSONObject().put("version", 6).put("strict", JSONObject().put("v", 1)
                .put("endpoint", state.getString("endpoint")).put("room", s.getString("room")).put("capability", s.getString("capability"))
                .put("authority", s.getString("authority")).put("role", state.getString("role"))
                .put("generation", state.getJSONObject("control").getString("generation"))
                .put("iv", b64e.encodeToString(iv)).put("ct", Base64.getEncoder().encodeToString(sealed)))
        } finally { key.fill(0) }
    }
    fun unwrap(root: String, stored: JSONObject, share: ByteArray): JSONObject {
        val w = stored.getJSONObject("strict"); require(w.getInt("v") == 1)
        val key = key(share, root)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64d.decode(w.getString("iv"))))
            cipher.updateAAD(aad(root))
            val clear = cipher.doFinal(Base64.getDecoder().decode(w.getString("ct")))
            return try { JSONObject(String(clear, Charsets.UTF_8)) } finally { clear.fill(0) }
        } finally { key.fill(0) }
    }
    /** Engine construction: the inner state, or [Locked] when no lease is held. */
    fun open(root: String, stored: JSONObject?): JSONObject? {
        if (!isWrapped(stored)) return stored
        val share = cached(root) ?: throw Locked()
        return try { unwrap(root, stored!!, share) } finally { share.fill(0) }
    }

    class Route(val endpoint: String, val room: String, val capability: String, val authority: String, val role: Char, val generation: ByteArray)
    fun route(wrapper: JSONObject): Route = wrapper.getJSONObject("strict").let { w ->
        Route(w.getString("endpoint"), w.getString("room"), w.getString("capability"), w.getString("authority"),
            w.getString("role").single(), PadProtocol.hexBytes(w.getString("generation")))
    }
    /**
     * One signed unlock round trip. Returns the share and how long it may be held. The grant must
     * echo our exact request and be signed by the authority pinned at pairing.
     */
    fun grant(relay: RelayTransport, route: Route, identity: DeviceIdentity): Pair<ByteArray, Long> {
        val card = identity.card()
        val now = System.currentTimeMillis(); val started = android.os.SystemClock.elapsedRealtime()
        val request = StrictChatProtocol.Frame(StrictChatProtocol.Kind.UNLOCK, route.role, route.generation,
            StrictChatProtocol.fingerprint(card), StrictChatProtocol.fingerprint(route.authority), 1,
            ByteArray(32).also(SecureRandom()::nextBytes), now, now + StrictChatProtocol.MAX_LEASE_MS, ByteArray(32))
        val bytes = StrictChatProtocol.encode(request)
        val pending = StrictChatProtocol.PendingUnlock(request, started)
        val body = JSONObject().put("frame", Base64.getEncoder().encodeToString(bytes))
            .put("signature", Base64.getEncoder().encodeToString(identity.sign(bytes))).toString()
        val reply = try { JSONObject(relay.share(route.endpoint, route.room, route.capability, "POST", body, unlock = true)) }
            catch (e: RelayHttpException) { if (e.status == 404) throw Gone() else throw e }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val share = pending.accept(Base64.getDecoder().decode(reply.getString("frame")), Base64.getDecoder().decode(reply.getString("signature")),
            route.authority, System.currentTimeMillis(), elapsed)
        return share to (StrictChatProtocol.MAX_LEASE_MS - (elapsed - started)).coerceAtLeast(1)
    }
    /** Unlocks a locked strict chat for one lease. Network; never on the UI thread. */
    fun unlock(context: Context, root: String, relay: RelayTransport = RelayClient) {
        val store = SecureStore(context, root)
        val stored = checkNotNull(store.load()) { "Unknown chat" }
        val (share, millis) = grant(relay, route(stored), store.identity())
        try { unwrap(root, stored, share); remember(root, share, millis, store.operationKey) } finally { share.fill(0) }
    }
}
