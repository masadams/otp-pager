package org.example.otppager

import org.json.JSONArray
import org.json.JSONObject

/** Call only from the single station worker. Save snapshot before publishing it in memory. */
class StationEngine(private val store: SecureStore, private val relay: RelayTransport = RelayClient) {
    var state: JSONObject? = store.load()
        private set
    private val padLedger = PadLedger(store.sibling("pads"))
    private val identity = store.identity()
    private var cachedView: View? = null
    private var cacheExpiry = 0L
    init { state?.let { validate(it); transaction { next -> if (next.has("pad") && next.getInt("version") == 2) next.put("version", 3).put("cursor", 0L).put("epoch", ""); prune(next) } } }
    private fun validate(s: JSONObject) {
        check(s.getInt("version") in 2..4)
        check(s.getInt("version") != 3 || s.has("pad"))
        if (s.has("ownDevice")) {
            check(s.getInt("version") == 4 && s.has("pad"))
            identity.check(s.getString("ownDevice"))
            if (s.has("peerDevice")) {
                IdentityProtocol.publicKey(s.getString("peerDevice"))
                check(s.getString("peerDevice") != s.getString("ownDevice"))
            }
        }
        check(!s.has("peerDevice") || s.has("ownDevice"))
        Protocol.endpoint(s.getString("endpoint")); Protocol.token(s.getString("token"))
        Protocol.master(s.getString("secret")).fill(0)
        check(s.getString("role") in listOf("A", "B"))
        check(s.getLong("counter") >= 0 && s.getLong("cursor") >= 0)
        window(s)
        if (s.has("pad")) {
            val pad = java.util.Base64.getDecoder().decode(s.getString("pad"))
            try { check(PadProtocol.hex(PadProtocol.id(pad)) == s.getString("padId")); check(padLedger.high(s.getString("padId")) >= s.getLong("counter")) }
            finally { pad.fill(0) }
        }
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
    private var stagedCable: JSONObject? = null
    fun requireCableEligible() {
        val s = state ?: return
        check(!s.has("pad") && s.getLong("counter") == 0L && s.getLong("high") == 0L &&
            s.getJSONArray("outbox").length() == 0 && s.getJSONArray("inbox").length() == 0) {
            "This station already has a pairing or traffic. Preserve it; start a fresh pairing on both devices before replacing it."
        }
    }
    fun cableIdentity(): String { requireCableEligible(); return identity.card(create = true) }
    fun signCable(bytes: ByteArray) = identity.sign(bytes)
    fun stageCable(setup: JSONObject, role: String, peer: String) {
        requireCableEligible(); check(stagedCable == null)
        val endpoint = Protocol.endpoint(setup.getString("endpoint")); val token = Protocol.token(setup.getString("token"))
        val secret = setup.getString("secret"); val master = Protocol.master(secret)
        require(setup.getString("pad").length <= 1400000)
        val bundle = java.util.Base64.getDecoder().decode(setup.getString("pad"))
        val pad = try { PadProtocol.material(bundle, PadProtocol.binding(master)) } finally { master.fill(0); bundle.fill(0) }
        try {
            require(role in listOf("A", "B")); IdentityProtocol.publicKey(peer)
            val own = identity.card(); require(peer != own)
            val id = PadProtocol.hex(PadProtocol.id(pad)); padLedger.register(id)
            stagedCable = JSONObject().put("version", 4).put("endpoint", endpoint).put("token", token).put("secret", secret)
                .put("role", role).put("counter", 0L).put("cursor", 0L).put("high", 0L).put("seen", JSONArray())
                .put("outbox", JSONArray()).put("inbox", JSONArray()).put("epoch", "")
                .put("pad", java.util.Base64.getEncoder().encodeToString(pad)).put("padId", id)
                .put("ownDevice", own).put("peerDevice", peer).put("padVerified", true).put("peerReady", true)
                .put("cableComplete", false)
        } finally { pad.fill(0) }
    }
    fun commitCable(complete: Boolean) {
        requireCableEligible()
        val next = checkNotNull(stagedCable) { "No staged pairing" }.put("cableComplete", complete)
        validate(next); store.save(next); state = next; stagedCable = null; cachedView = null
    }
    fun finishCable() = transaction { it.put("cableComplete", true) }
    fun pairingInvite(): PairingInvite {
        val s = checkNotNull(state) { "Save this station's link first" }
        return PairingInvite(s.getString("endpoint"), s.getString("token"), s.getString("secret"), if (s.getString("role") == "A") "B" else "A")
    }
    fun installPad(bundle: ByteArray) {
        val current = checkNotNull(state) { "Save the link settings first" }
        check(!current.has("pad")) { "Forget pairing before installing a fresh pad" }
        check(current.getJSONArray("outbox").length() == 0 && current.getJSONArray("inbox").length() == 0 && current.getLong("counter") == 0L && current.getLong("high") == 0L) { "Start a fresh pairing on both stations before installing pads" }
        val master = Protocol.master(current.getString("secret"))
        val pad = try { PadProtocol.material(bundle, PadProtocol.binding(master)) } finally { master.fill(0) }
        try {
            val id = PadProtocol.hex(PadProtocol.id(pad))
            padLedger.register(id) // A failed installation retires the bundle, never makes it reusable.
            transaction { it.put("version", 3).put("pad", java.util.Base64.getEncoder().encodeToString(pad)).put("padId", id).put("cursor", 0L).put("epoch", "") }
        } finally { pad.fill(0) }
    }
    fun generatePad(): ByteArray {
        val s = checkNotNull(state); require(s.getString("role") == "A") { "Generate on Station A; import on Station B" }
        val master = Protocol.master(s.getString("secret"))
        val bundle = try { PadProtocol.generate(PadProtocol.binding(master)) } finally { master.fill(0) }
        try { installPad(bundle); return bundle } catch (e: Exception) { bundle.fill(0); throw e }
    }
    /** Local identity exchange uses public cards only. No relay, pad slots, or liveness claim. */
    fun prepareIdentity() {
        val s = checkNotNull(state)
        check(s.has("pad") && s.optBoolean("padVerified") && s.optBoolean("peerReady")) { "Finish pad verification on both stations first" }
        check(items(s.getJSONArray("outbox")).none { it.getString("status") == "queued" }) { "Sync queued packets before starting device binding" }
        if (s.has("ownDevice")) { identity.check(s.getString("ownDevice")); return }
        val card = identity.card(create = true)
        transaction { it.put("version", 4).put("ownDevice", card) }
    }
    fun pinDevice(card: String, comparedSuffix: String) {
        val canonical = card.trim()
        val fingerprint = IdentityProtocol.fingerprint(canonical)
        require(comparedSuffix.trim().lowercase() == fingerprint.takeLast(8)) { "Compare the FULL fingerprint with the other station in person" }
        transaction { s ->
            check(s.has("ownDevice")) { "Create this station's identity first" }
            require(canonical != s.getString("ownDevice")) { "That is this station's own identity card" }
            check(!s.has("peerDevice") || s.getString("peerDevice") == canonical) { "Device already pinned. A different key requires a fresh physical pairing" }
            s.put("peerDevice", canonical).put("cursor", 0L).put("epoch", "")
        }
    }
    private fun signatureData(s: JSONObject, role: Char, counter: Long, body: String): ByteArray {
        val master = Protocol.master(s.getString("secret"))
        val own = role == s.getString("role")[0]
        return try { IdentityProtocol.transcript(Protocol.room(master), s.getString("padId"), role, counter,
            s.getString(if (own) "ownDevice" else "peerDevice"), s.getString(if (own) "peerDevice" else "ownDevice"), body) }
        finally { master.fill(0) }
    }
    private fun deviceBody(s: JSONObject, decoded: Protocol.Opened, role: Char, enforce: Boolean): String {
        if (!IdentityProtocol.isSigned(decoded.payload)) {
            check(!enforce || !s.has("ownDevice")) { "Unsigned packet rejected: device binding is active" }
            return decoded.payload
        }
        check(s.has("peerDevice")) { "Verify the peer identity before receiving device-bound packets" }
        val card = s.getString(if (role == s.getString("role")[0]) "ownDevice" else "peerDevice")
        return IdentityProtocol.body(decoded.payload) { body, sig -> IdentityProtocol.verify(card, signatureData(s, role, decoded.counter, body), sig) }
    }
    /** Full code must be compared in person; suffix entry is an additional typing check only. */
    fun confirmPad(peerSuffix: String) {
        transaction { s ->
            check(!s.has("ownDevice")) { "Pad setup is complete; device binding uses public cards without readiness heartbeats" }
            check(s.has("pad")) { "Install a pad first" }
            require(peerSuffix.trim().lowercase() == s.getString("padId").takeLast(8)) { "Code does not match. Compare the FULL pad check on both devices." }
            if (!s.optBoolean("padVerified")) {
                queue(s, JSONObject().put("kind", "ready").put("protocol", 4), "ready")
                val ready = items(s.getJSONArray("outbox")).last { it.getString("kind") == "ready" }
                s.put("readyPacket", ready.getString("packet")).put("readyId", ready.getString("id")).put("padVerified", true)
            } else {
                // Reannounce after relay expiry using exactly the original ciphertext, never new pad bytes.
                val out = s.getJSONArray("outbox")
                val existing = items(out).firstOrNull { it.getString("id") == s.getString("readyId") }
                if (existing == null || existing.getString("status") != "queued") {
                    check(items(out).count { it.getString("status") == "queued" } < 50) { "Outbox full; sync first" }
                    if (existing != null) existing.put("status", "queued").put("time", System.currentTimeMillis())
                    else out.put(JSONObject().put("id", s.getString("readyId")).put("packet", s.getString("readyPacket"))
                        .put("kind", "ready").put("status", "queued").put("time", System.currentTimeMillis()))
                }
            }
        }
    }
    fun markRead() { if (state?.let { items(it.getJSONArray("inbox")).any { entry -> !entry.optBoolean("read", false) } } == true)
        transaction { s -> items(s.getJSONArray("inbox")).forEach { it.put("read", true) } } }
    private fun packetRole(s: JSONObject, wire: String) = if (s.has("pad")) PadProtocol.role(wire) else Protocol.role(wire)
    private fun open(s: JSONObject, master: ByteArray, wire: String, role: Char): Protocol.Opened {
        if (!s.has("pad")) return Protocol.open(master, wire, role)
        val pad = java.util.Base64.getDecoder().decode(s.getString("pad"))
        return try { PadProtocol.open(pad, wire, role) } finally { pad.fill(0) }
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
        if (s.has("ownDevice")) {
            check(s.has("peerDevice")) { "Verify the other device identity first" }
            val bytes = payload.toString().toByteArray(Charsets.UTF_8).size
            require(4 + ((bytes + 2) / 3) * 4 + 1 + 108 <= PadProtocol.BLOCK - 2) { "Encoded signed message too large for a pad slot" }
        }
        val n = s.getLong("counter"); check(n < Long.MAX_VALUE) { "Pairing counter exhausted; create a new pairing" }
        val master = Protocol.master(s.getString("secret"))
        val counter: Long
        val sealed: Pair<String, String>
        try {
            if (s.has("pad")) {
                require(payload.toString().toByteArray(Charsets.UTF_8).size <= PadProtocol.BLOCK - 2) { "Encoded message too large for a pad slot" }
                val pad = java.util.Base64.getDecoder().decode(s.getString("pad"))
                try {
                    cachedView = null
                    counter = padLedger.reserve(s.getString("padId")) // Durable burn BEFORE encryption or network output.
                    val body = if (s.has("ownDevice")) {
                        check(s.has("peerDevice")) { "Verify the other device identity first" }
                        identity.check(s.getString("ownDevice"))
                        IdentityProtocol.signed(payload.toString()) { identity.sign(signatureData(s, s.getString("role")[0], counter, it)) }
                    } else payload.toString()
                    sealed = PadProtocol.seal(pad, s.getString("role")[0], counter, body)
                } finally { pad.fill(0) }
            } else { counter = n + 1; sealed = Protocol.seal(master, s.getString("role")[0], counter, payload.toString()) }
        } finally { master.fill(0) }
        val (id, wire) = sealed
        s.put("counter", counter)
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
            check(s.optBoolean("cableComplete", true)) { "Cable pairing incomplete; arrange a fresh pairing on both devices" }
            check(!s.has("pad") || (s.optBoolean("padVerified") && s.optBoolean("peerReady"))) { "Open Pad: compare codes and wait for the other station to verify and sync" }
            check(!s.has("ownDevice") || s.has("peerDevice")) { "Finish physical device verification before sending" }
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
        check(state?.optBoolean("cableComplete", true) != false) { "Cable pairing incomplete; arrange a fresh pairing on both devices" }
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
                    if (packetRole(next, wire) == inbound) {
                        // Authentication and payload validation finish before touching replay state.
                        val decoded = try { open(next, master, wire, inbound) } catch (_: Exception) { ignored++; null }
                        if (decoded != null && window(next).accepts(decoded.counter)) {
                            // Hold signed packets while the physical key exchange is incomplete.
                            if (next.has("ownDevice") && !next.has("peerDevice") && IdentityProtocol.isSigned(decoded.payload)) break
                            val payload = try { JSONObject(deviceBody(next, decoded, inbound, enforce = true)) } catch (_: Exception) { null }
                            val kind = payload?.optString("kind")
                            if (kind == "text" && payload?.opt("text") is String && payload.getString("text").toByteArray(Charsets.UTF_8).size in 1..2048) {
                                if (items(next.getJSONArray("outbox")).count { it.getString("status") == "queued" } >= 50) break
                                next.getJSONArray("inbox").put(JSONObject().put("packet", wire).put("id", decoded.id).put("read", false).put("time", System.currentTimeMillis()))
                                // Exhaustion must not prevent receiving. Last available slots can acknowledge only a finite number of messages.
                                if (!next.has("pad") || padLedger.high(next.getString("padId")) < PadProtocol.SLOTS)
                                    queue(next, JSONObject().put("kind", "receipt").put("for", decoded.id), "receipt")
                                setWindow(next, window(next).accept(decoded.counter))
                            } else if (kind == "ready" && next.has("pad") && payload?.optInt("protocol") == 4) {
                                next.put("peerReady", true)
                                setWindow(next, window(next).accept(decoded.counter))
                            } else if (kind == "receipt" && payload?.opt("for") is String && Regex("[a-f0-9]{32}").matches(payload.getString("for"))) {
                                items(next.getJSONArray("outbox")).filter { it.getString("id") == payload.getString("for") && it.getString("kind") == "text" }
                                    .forEach { it.put("status", "delivered") }
                                setWindow(next, window(next).accept(decoded.counter))
                            } else { ignored++ }
                        }
                    } else if (packetRole(next, wire) == null) { ignored++ }
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
    data class Message(val incoming: Boolean, val text: String, val status: String, val time: Long, val unread: Boolean = false, val deviceVerified: Boolean = false)
    data class View(val role: String, val fingerprint: String, val messages: List<Message>, val pending: Int, val unread: Int = 0, val padRemaining: Int? = null, val padCheck: String? = null, val padVerified: Boolean = false, val peerReady: Boolean = false, val ownDevice: String? = null, val peerDevice: String? = null, val keyProtection: String? = null, val pairingComplete: Boolean = true) {
        val canSend: Boolean get() = pairingComplete && role != "—" && padRemaining != 0 && (padCheck == null || (padVerified && peerReady)) && (ownDevice == null || peerDevice != null)
    }
    fun view(): View {
        cachedView?.let { if (System.currentTimeMillis() < cacheExpiry) return it }
        val s = state ?: return View("—", "NOT PAIRED", emptyList(), 0)
        val master = Protocol.master(s.getString("secret"))
        return try {
            val own = s.getString("role")[0]; val peer = if (own == 'A') 'B' else 'A'
            val rows = mutableListOf<Message>()
            for (entry in items(s.getJSONArray("inbox"))) {
                if (System.currentTimeMillis() - entry.getLong("time") >= 86400000) continue
                val decoded = open(s, master, entry.getString("packet"), peer)
                val text = JSONObject(deviceBody(s, decoded, peer, enforce = false)).getString("text")
                rows.add(Message(true, text, "received", entry.getLong("time"), !entry.optBoolean("read", false), IdentityProtocol.isSigned(decoded.payload)))
            }
            for (entry in items(s.getJSONArray("outbox")).filter { it.getString("kind") == "text" }) {
                if (System.currentTimeMillis() - entry.getLong("time") >= 86400000) continue
                val decoded = open(s, master, entry.getString("packet"), own)
                val text = JSONObject(deviceBody(s, decoded, own, enforce = false)).getString("text")
                rows.add(Message(false, text, entry.getString("status"), entry.getLong("time"), deviceVerified = IdentityProtocol.isSigned(decoded.payload)))
            }
            cacheExpiry = rows.minOfOrNull { it.time + 86400000L } ?: Long.MAX_VALUE
            View(own.toString(), Protocol.fingerprint(master), rows.sortedBy { it.time }.takeLast(100),
                items(s.getJSONArray("outbox")).count { it.getString("status") == "queued" },
                items(s.getJSONArray("inbox")).count { !it.optBoolean("read", false) },
                if (s.has("pad")) PadProtocol.SLOTS - padLedger.high(s.getString("padId")).toInt() else null,
                s.optString("padId").takeIf { it.isNotEmpty() }, s.optBoolean("padVerified"), s.optBoolean("peerReady"),
                s.optString("ownDevice").takeIf { it.isNotEmpty() }, s.optString("peerDevice").takeIf { it.isNotEmpty() },
                if (s.has("ownDevice")) identity.protection() else null, s.optBoolean("cableComplete", true)).also { cachedView = it }
        } finally { master.fill(0) }
    }
}
