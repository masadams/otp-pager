package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Strict chats: locked vault at rest, device-signed unlock, signed 60 s grant, lapse/wipe removes the chat. */
@RunWith(AndroidJUnit4::class)
class StrictChatTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private val b64 = Base64.getEncoder(); private val d64 = Base64.getDecoder()

    /** Behaves like relay/server.mjs for claims, shares and unlocks; mailbox traffic is in memory. */
    private class Relay : RelayTransport {
        val authority = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val card: String = Base64.getEncoder().encodeToString(authority.public.encoded)
        val shares = mutableMapOf<String, JSONObject>()
        /** Tombstones like the relay's: room -> enrolled device key. */
        val retired = mutableMapOf<String, String>()
        @Synchronized fun retire(room: String) { shares.remove(room)?.let { retired[room] = it.getString("device") } }
        val claims = mutableMapOf<String, String>()
        val nonces = mutableSetOf<String>()
        var unlocks = 0
        val rooms = mutableMapOf<String, MutableList<String>>()
        @Synchronized override fun scope(endpoint: String, room: String, bootstrap: String, capability: String): Boolean {
            val held = claims.getOrPut(room) { capability }; check(held == capability); return true
        }
        override fun authority(endpoint: String) = card
        /** Post-quantum half, as relay/server.mjs on Node with ML-DSA; off models an older relay. */
        var pqSeed: ByteArray? = null
        /** Drops the ML-DSA signature from responses (a downgrade attempt). */
        var stripPq = false
        fun enablePq() { pqSeed = PqSignature.newSeed() }
        override fun pqAuthority(endpoint: String): Pair<String, String>? = pqSeed?.let { seed ->
            val spki = PqSignature.publicKey(seed)
            val binding = Signature.getInstance("SHA256withECDSA").run { initSign(authority.private); update("OTP Pager PQ authority v1\u0000".toByteArray(Charsets.ISO_8859_1) + spki); sign() }
            Base64.getEncoder().encodeToString(spki) to Base64.getEncoder().encodeToString(binding)
        }
        private fun signed(frame: ByteArray): JSONObject = JSONObject().put("frame", Base64.getEncoder().encodeToString(frame))
            .put("signature", Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(authority.private); update(frame); sign() }))
            .apply { val seed = pqSeed; if (seed != null && !stripPq) put("pqSignature", Base64.getEncoder().encodeToString(PqSignature.sign(seed, frame))) }
        /** Like the relay: a pinned device ML-DSA key must sign; an offered one is pinned on first use. */
        private fun devicePq(held: JSONObject?, request: JSONObject, frame: ByteArray) {
            if (pqSeed == null) return
            val pinned = held?.optString("devicePq")?.takeIf { it.isNotEmpty() }
            val key = pinned ?: request.optString("devicePq").takeIf { it.isNotEmpty() } ?: return
            if (!PqSignature.verify(Base64.getDecoder().decode(key), frame, Base64.getDecoder().decode(request.optString("pqSignature")))) throw RelayHttpException(403)
            if (pinned == null) held?.put("devicePq", key)
        }
        @Synchronized override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            val list = rooms.getOrPut(room) { mutableListOf() }
            if (method == "POST") { list.add(JSONObject(body!!).getString("packet")); return "{}" }
            return JSONObject().put("protocol", 2).put("access", if (claims[room] == token) "chat" else "shared").put("epoch", "strict-test")
                .put("messages", JSONArray(list.mapIndexedNotNull { i, p -> if (i + 1 > cursor) JSONObject().put("seq", i + 1).put("packet", p) else null })).toString()
        }
        @Synchronized override fun share(endpoint: String, room: String, capability: String, method: String, body: String?, unlock: Boolean): String {
            if (claims[room] != capability) throw RelayHttpException(403)
            when {
                method == "DELETE" -> { retire(room); return "{}" }
                method == "PUT" -> { if (room in retired) throw RelayHttpException(410); check(shares.putIfAbsent(room, JSONObject(body!!)) == null); return "{}" }
            }
            check(method == "POST" && unlock)
            val request = JSONObject(body!!)
            val frame = Base64.getDecoder().decode(request.getString("frame"))
            val held = shares[room] ?: run {
                val device = retired[room] ?: throw RelayHttpException(404)
                if (!IdentityProtocol.verify(device, frame, Base64.getDecoder().decode(request.getString("signature")))) throw RelayHttpException(403)
                val revoked = StrictChatProtocol.encode(StrictChatProtocol.decode(frame).copy(kind = StrictChatProtocol.Kind.REVOKED, issued = 0, expires = 0))
                throw RelayHttpException(410, signed(revoked).toString())
            }
            if (!IdentityProtocol.verify(held.getString("device"), frame, Base64.getDecoder().decode(request.getString("signature")))) throw RelayHttpException(403)
            devicePq(held, request, frame)
            val decoded = StrictChatProtocol.decode(frame)
            check(decoded.kind == StrictChatProtocol.Kind.UNLOCK && nonces.add(PadProtocol.hex(decoded.nonce))) { "Replay" }
            unlocks++
            val share = Base64.getUrlDecoder().decode(held.getString("share"))
            val grant = StrictChatProtocol.encode(decoded.copy(kind = StrictChatProtocol.Kind.GRANT, share = share))
            return signed(grant).toString()
        }
    }
    private fun pair(a: StationEngine, b: StationEngine, strictA: Long, strictB: Long = strictA) {
        val pool = Executors.newFixedThreadPool(2)
        val ain = PipedInputStream(65536); val bout = PipedOutputStream(ain)
        val bin = PipedInputStream(65536); val aout = PipedOutputStream(bin)
        try {
            val ready = CountDownLatch(2)
            val review: (String, String) -> Unit = { _, _ -> ready.countDown(); check(ready.await(20, TimeUnit.SECONDS)) }
            val af = pool.submit { CableProtocol.run(true, ain, aout, a, review, {}, false, PadProtocol.MATERIAL_BYTES, strictA) }
            val bf = pool.submit { CableProtocol.run(false, bin, bout, b, review, {}, false, PadProtocol.MATERIAL_BYTES, strictB) }
            try { af.get(60, TimeUnit.SECONDS) } finally { try { bf.get(60, TimeUnit.SECONDS) } catch (_: Exception) {} }
        } finally { listOf(ain, bin, aout, bout).forEach { try { it.close() } catch (_: Exception) {} }; pool.shutdownNow() }
    }
    private fun cleanup(roots: List<String>) {
        StrictChat.lockAll()
        for (id in roots) {
            listOf(id, "$id-draft", "$id-pads").forEach { try { SecureStore(context, it).forget() } catch (_: Exception) {} }
            VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, id)).forEach { it.delete() }
            PadMaterial.deleteAll(context.noBackupFilesDir, id)
            File(context.noBackupFilesDir, "peerwipe").listFiles()?.filter { it.readText().contains(id) }?.forEach { it.delete() }
        }
    }

    @Test fun strictVaultIsUnreadableWithoutAFreshSignedGrantAndALapsedShareRemovesTheChat() {
        val ra = root(); val rb = root(); val relay = Relay()
        try {
            val a = StationEngine(SecureStore(context, ra), relay); val b = StationEngine(SecureStore(context, rb), relay)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "B")
            pair(a, b, StrictChat.DEADLINES[1])
            assertTrue(a.isStrict && b.isStrict)
            assertEquals(2, relay.shares.size)
            // At rest: only routing and public keys are readable outside the share-keyed layer.
            val stored = SecureStore(context, ra).load()!!
            assertTrue(stored.has("strict"))
            for (secret in listOf("secret", "token", "padKeys", "control", "outbox")) assertFalse("$secret outside the strict layer", stored.has(secret))
            val inner = a.state!!
            assertFalse(stored.toString().contains(inner.getString("secret")))
            assertFalse(stored.toString().contains(inner.getString("padKeys").take(40)))

            // Leaving the app locks it: live engines drop decrypted state and a new owner cannot open it.
            StrictChat.lockAll()
            assertNull(a.state)
            try { StationEngine(SecureStore(context, ra), relay); fail("Opened a strict chat without a grant") } catch (_: StrictChat.Locked) {}
            StrictChat.unlock(context, ra, relay)
            val reopened = StationEngine(SecureStore(context, ra), relay)
            assertEquals(inner.getString("secret"), reopened.state!!.getString("secret"))
            // Every foreground check renews the lease with a new signed unlock.
            val before = relay.unlocks
            try { reopened.sync() } catch (_: Exception) {}
            assertEquals(before + 1, relay.unlocks)

            // A grant signed by a different authority is refused.
            StrictChat.lockAll()
            val impostor = Relay().also { it.shares.putAll(relay.shares); it.claims.putAll(relay.claims) }
            try { StrictChat.unlock(context, ra, impostor); fail("Accepted a grant from an unpinned authority") } catch (_: Exception) {}
            try { StationEngine(SecureStore(context, ra), relay); fail("Impostor grant unlocked the chat") } catch (_: StrictChat.Locked) {}

            // A relay that simply lacks the share (reset, wrong route, hostile) is never grounds to delete.
            StrictChat.unlock(context, ra, relay)
            val room = StrictChat.route(stored).room
            val held = relay.shares.remove(room)!!
            try { StrictChat.unlock(context, ra, relay); fail("Unlocked without a share") } catch (_: StrictChat.Unavailable) {}
            try { StationEngine(SecureStore(context, ra), relay).sync() } catch (_: Exception) {}
            assertFalse(VaultLifecycle.isRetired(context.noBackupFilesDir, ra))
            relay.shares[room] = held

            // A reply that lands after the app left the foreground never caches the share.
            StrictChat.lockAll()
            val startedAt = StrictChat.epoch(); StrictChat.lockAll()
            try { StrictChat.remember(ra, ByteArray(32), 1000, "late", startedAt); fail("Late unlock reply cached") } catch (_: StrictChat.Locked) {}
            try { StationEngine(SecureStore(context, ra), relay); fail("Late reply opened the chat") } catch (_: StrictChat.Locked) {}

            // Lapse or wipe elsewhere: the relay retires the share and answers with a signed
            // REVOKED, so the next check removes the chat here.
            StrictChat.unlock(context, ra, relay)
            val live = StationEngine(SecureStore(context, ra), relay)
            relay.retire(room)
            val notice = live.sync()
            assertTrue(notice.contains("key share is gone"))
            assertTrue(VaultLifecycle.isRetired(context.noBackupFilesDir, ra))
            try { StrictChat.unlock(context, ra, relay); fail("Retired strict chat unlocked") } catch (_: Exception) {}

            // Wiping the other phone queues deletion of its own share; the pump sends it.
            val bStored = SecureStore(context, rb).load()!!
            assertTrue(PeerWipe.prepareShareDeletion(context, rb, bStored))
            PeerWipe.pump(context, relay)
            assertTrue(relay.shares.isEmpty())
        } finally { cleanup(listOf(ra, rb)) }
    }

    @Test fun hybridMlDsaGrantsArePinnedAtPairingOrLaterAndNeverDowngraded() {
        val ra = root(); val rb = root(); val pqRelay = Relay().apply { enablePq() }
        val rc = root(); val rd = root(); val oldRelay = Relay()
        try {
            // Paired against a relay with ML-DSA: both keys pinned at pairing, both signatures on every grant.
            val a = StationEngine(SecureStore(context, ra), pqRelay); val b = StationEngine(SecureStore(context, rb), pqRelay)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "B")
            pair(a, b, StrictChat.DEADLINES[1])
            val route = StrictChat.route(SecureStore(context, ra).load()!!)
            assertNotNull(route.authorityPq); assertNotNull(route.devicePqSeed)
            assertTrue(pqRelay.shares.values.all { it.has("devicePq") })
            StrictChat.lockAll()
            pqRelay.stripPq = true
            try { StrictChat.unlock(context, ra, pqRelay); fail("Accepted a grant without its ML-DSA signature") } catch (_: IllegalArgumentException) {}
            try { StationEngine(SecureStore(context, ra), pqRelay); fail("Downgraded grant opened the chat") } catch (_: StrictChat.Locked) {}
            pqRelay.stripPq = false
            StrictChat.unlock(context, ra, pqRelay)
            assertNotNull(StationEngine(SecureStore(context, ra), pqRelay).state)

            // Paired against an older relay: classical only, until the relay gains ML-DSA. The next
            // check pins it (bound by the pinned P-256 key) and from then on requires it.
            val c = StationEngine(SecureStore(context, rc), oldRelay); val d = StationEngine(SecureStore(context, rd), oldRelay)
            c.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            pair(c, d, StrictChat.DEADLINES[1])
            assertNull(StrictChat.route(SecureStore(context, rc).load()!!).authorityPq)
            oldRelay.enablePq()
            try { c.sync() } catch (_: Exception) {}
            assertNotNull(c.state!!.getJSONObject("strictShare").optString("authorityPq").takeIf { it.isNotEmpty() })
            assertNotNull(StrictChat.route(SecureStore(context, rc).load()!!).authorityPq)
            StrictChat.lockAll()
            oldRelay.stripPq = true
            try { StrictChat.unlock(context, rc, oldRelay); fail("Accepted a downgraded grant after pinning") } catch (_: IllegalArgumentException) {}
            oldRelay.stripPq = false
            StrictChat.unlock(context, rc, oldRelay)
        } finally { cleanup(listOf(ra, rb, rc, rd)) }
    }

    @Test fun bothPhonesMustChooseTheSameChatType() {
        val ra = root(); val rb = root(); val relay = Relay()
        try {
            val a = StationEngine(SecureStore(context, ra), relay); val b = StationEngine(SecureStore(context, rb), relay)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "B")
            try { pair(a, b, StrictChat.DEADLINES[0], 0L); fail("Mismatched strict mode paired") } catch (_: Exception) {}
            assertFalse(StationEngine.hasPad(a.state!!)); assertNull(b.state)
            assertTrue(relay.shares.isEmpty())
        } finally { cleanup(listOf(ra, rb)) }
    }
}
