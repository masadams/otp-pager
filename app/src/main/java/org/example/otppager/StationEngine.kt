package org.example.otppager

import org.json.JSONArray
import org.json.JSONObject

/** Call only from the single station worker. Save snapshot before publishing it in memory. */
class StationEngine(private val store: SecureStore, relay: RelayTransport = RelayClient, private val claims: PairingClaims? = null) {
    private val relay = relay.forContact(store)
    companion object {
        // Weak owners avoid retaining every short-lived background receiver engine.
        private val live = java.util.WeakHashMap<StationEngine, String>()
        internal fun invalidateDirectory(directory: java.io.File): Set<String> = synchronized(VaultLifecycle.lock) {
            val keys = live.values.filter { java.io.File(it).parentFile == directory.absoluteFile }.toSet()
            keys.forEach(::invalidateRetired)
            keys.map { java.io.File(it).name.removeSuffix(".retired") }.filter(VaultLifecycle::isContact).toSet()
        }
        internal fun invalidateRetired(key: String) = synchronized(VaultLifecycle.lock) {
            live.entries.filter { it.value == key }.map { it.key }.forEach { engine ->
                engine.state = null; engine.cachedView = null; engine.stagedCable = null; engine.stagedRenewal = false
            }
        }
    }
    @Volatile private var snapshot: JSONObject? = store.load()
    var state: JSONObject?
        get() = snapshot
        private set(value) = synchronized(VaultLifecycle.lock) {
            if (value != null) store.requireActive()
            snapshot = value
        }
    private val padLedger = PadLedger(store.sibling("pads"))
    private val identity = store.identity()
    private var cached: View? = null
    private var cachedView: View?
        get() = cached
        set(value) = synchronized(VaultLifecycle.lock) {
            if (value != null) store.requireActive()
            cached = value
        }
    private var cacheExpiry = 0L
    init { state?.let { validate(it); claims?.migrate(store); transaction { next -> if (next.has("pad") && next.getInt("version") == 2) next.put("version", 3).put("cursor", 0L).put("epoch", ""); prune(next) } } }
    private fun validate(s: JSONObject) {
        check(s.getInt("version") in 2..6)
        check(s.getInt("version") !in listOf(3, 5, 6) || s.has("pad"))
        if (s.has("ownDevice")) {
            check(s.getInt("version") in 4..6 && s.has("pad"))
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
            try {
                check(PadProtocol.validSize(pad.size))
                check(Regex("[a-f0-9]{32}").matches(s.getString("padId")))
                // Schema 5's original ID is authenticated by the vault; retired material no longer hashes to it.
                if (s.getInt("version") < 5) check(PadProtocol.hex(PadProtocol.id(pad)) == s.getString("padId"))
                check(padLedger.high(s.getString("padId")) >= s.getLong("counter"))
            }
            finally { pad.fill(0) }
        }
        check((s.optJSONObject("acceptedPackets")?.length() ?: 0) <= PadProtocol.MAX_SLOTS)
        check(s.getJSONArray("outbox").length() <= 150 && s.getJSONArray("inbox").length() <= 100)
        check(!s.has("lastSentDraft") || ChatDraft.validId(s.getString("lastSentDraft")))
    }
    private fun transaction(rekey: Boolean = false, change: (JSONObject) -> Unit) = synchronized(VaultLifecycle.lock) {
        store.requireActive(); transactionActive(rekey, change)
    }
    private fun transactionActive(rekey: Boolean = false, change: (JSONObject) -> Unit) {
        val next = JSONObject(checkNotNull(state).toString())
        change(next); retireUsed(next); validate(next)
        if (next.toString() == state!!.toString()) return
        store.save(next, rekey); state = next; cachedView = null
    }
    fun configure(endpoint: String, token: String, secret: String, role: String) {
        store.requireActive()
        val url = Protocol.endpoint(endpoint); val access = Protocol.token(token)
        Protocol.master(secret).fill(0); require(role == "A" || role == "B")
        val previous = state
        require(previous == null || (previous.getString("secret") == secret && previous.getString("role") == role)) {
            "Forget the existing pairing before changing its secret or station role. Use a NEW secret after forgetting."
        }
        val next = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("version", 2).put("secret", secret).put("role", role).put("counter", 0L).put("cursor", 0L)
            .put("high", 0L).put("seen", JSONArray()).put("outbox", JSONArray()).put("inbox", JSONArray()).put("epoch", "")
        if (previous != null && previous.getString("endpoint") != url) {
            next.put("cursor", 0L).put("epoch", "").remove("relayScoped")
            next.remove("relayRevoked")
        }
        next.put("endpoint", url).put("token", access)
        claims?.room(secret, store.namespace, existing = previous != null)
        store.save(next); state = next; cachedView = null
    }
    /** Explicit QR review may replace unused link settings, never pads, counters or traffic. */
    fun acceptInvite(invite: PairingInvite) {
        requireCableEligible()
        val old = state
        if (old?.getString("secret") == invite.secret && old.getString("role") == invite.role) {
            configure(invite.endpoint, invite.token, invite.secret, invite.role); return
        }
        val next = JSONObject().put("version", 2).put("endpoint", invite.endpoint).put("token", invite.token)
            .put("secret", invite.secret).put("role", invite.role).put("counter", 0L).put("cursor", 0L)
            .put("high", 0L).put("seen", JSONArray()).put("outbox", JSONArray()).put("inbox", JSONArray()).put("epoch", "")
        claims?.room(invite.secret, store.namespace, existing = false)
        validate(next); store.save(next); state = next; cachedView = null
    }
    private var staged: JSONObject? = null
    private var stagedCable: JSONObject?
        get() = staged
        set(value) = synchronized(VaultLifecycle.lock) {
            if (value != null) store.requireActive()
            staged = value
        }
    private var stagedRenewal = false
    init { synchronized(VaultLifecycle.lock) { store.requireActive(); live[this] = store.operationKey } }
    /** Only after the user explicitly approves replacing the current conversation. */
    fun resetForCable() {
        val previous = checkNotNull(state)
        var secret: String
        do { secret = Protocol.randomSecret() } while (secret == previous.getString("secret"))
        val next = JSONObject().put("version", 2).put("endpoint", previous.getString("endpoint")).put("token", previous.getString("token"))
            .put("secret", secret).put("role", "A").put("counter", 0L).put("cursor", 0L).put("high", 0L)
            .put("seen", JSONArray()).put("outbox", JSONArray()).put("inbox", JSONArray()).put("epoch", "")
        validate(next); store.save(next); state = next; stagedCable = null; cachedView = null
        // The separate retirement/reservation ledger and non-exportable device key remain intact.
    }
    fun requireCableEligible() {
        store.requireActive()
        val s = state ?: return
        check(!s.has("pad") && s.getLong("counter") == 0L && s.getLong("high") == 0L &&
            s.getJSONArray("outbox").length() == 0 && s.getJSONArray("inbox").length() == 0) {
            "This station already has a pairing or traffic. Preserve it; start a fresh pairing on both devices before replacing it."
        }
    }
    fun requireRenewalIdentity() {
        store.requireActive()
        val s = checkNotNull(state) { "Pair this chat first" }
        check(s.has("pad") && s.has("ownDevice") && s.has("peerDevice") && !s.optBoolean("relayRevoked")) { "A verified paired chat is required" }
        identity.check(s.getString("ownDevice"))
    }
    fun requireRenewalEligible() {
        requireRenewalIdentity()
        check(items(state!!.getJSONArray("outbox")).none { it.getString("kind") == "text" && it.getString("status") in listOf("queued", "relayed") && System.currentTimeMillis() - it.getLong("time") < 86400000 }) { "Confirm outstanding messages over USB before renewing" }
    }
    private fun packetHash(wire: String) = PadProtocol.hex(java.security.MessageDigest.getInstance("SHA-256").digest(wire.toByteArray(Charsets.US_ASCII)))
    private fun rememberPacket(s: JSONObject, id: String, wire: String) {
        val hashes = s.optJSONObject("acceptedPackets") ?: JSONObject().also { s.put("acceptedPackets", it) }
        hashes.put(id, packetHash(wire))
    }
    fun renewalTraffic(): JSONArray {
        store.requireActive()
        requireRenewalIdentity(); transaction(change = ::prune)
        return JSONArray(items(state!!.getJSONArray("outbox")).filter { it.getString("kind") == "text" && it.getString("status") in listOf("queued", "relayed") }.map { it.getString("packet") })
    }
    /** Existing device-authenticated USB session only. No receipt/pad allocation is needed. */
    fun receiveRenewalTraffic(packets: JSONArray): JSONArray {
        requireRenewalIdentity(); require(packets.length() <= 150)
        val acknowledged = JSONArray()
        transaction { next ->
            val role = if (next.getString("role") == "A") 'B' else 'A'
            val master = Protocol.master(next.getString("secret"))
            try {
                for (i in 0 until packets.length()) {
                    val wire = packets.getString(i); require(wire.length <= 5600 && packetRole(next, wire) == role)
                    val known = items(next.getJSONArray("inbox")).firstOrNull { it.getString("packet") == wire }
                    val hash = packetHash(wire)
                    val hashes = next.optJSONObject("acceptedPackets")
                    val knownId = known?.getString("id") ?: hashes?.let { map -> map.keys().asSequence().firstOrNull { map.getString(it) == hash } }
                    if (knownId != null) { acknowledged.put(knownId); continue }
                    val decoded = open(next, master, wire, role)
                    val payload = JSONObject(deviceBody(next, decoded, role, enforce = true))
                    require(payload.optString("kind") == "text" && payload.opt("text") is String && payload.getString("text").toByteArray(Charsets.UTF_8).size in 1..2048)
                    check(window(next).accepts(decoded.counter)) { "Saved evidence for this old packet is unavailable; keep the current pad and sync or let the pending message expire" }
                    next.getJSONArray("inbox").put(JSONObject().put("packet", wire).put("id", decoded.id).put("read", false).put("time", System.currentTimeMillis()))
                    cacheOpened(next, wire, decoded); rememberPacket(next, decoded.id, wire)
                    setWindow(next, window(next).accept(decoded.counter)); acknowledged.put(decoded.id)
                }
                prune(next)
            } finally { master.fill(0) }
        }
        return acknowledged
    }
    fun acknowledgeRenewalTraffic(ack: JSONArray, sent: JSONArray) {
        require(ack.length() <= 150 && sent.length() <= 150)
        val wires = (0 until sent.length()).map { sent.getString(it) }.toSet()
        val expected = items(state!!.getJSONArray("outbox")).filter { it.getString("kind") == "text" && it.getString("packet") in wires }.map { it.getString("id") }.toSet()
        val received = (0 until ack.length()).map { ack.getString(it) }.toSet()
        check(expected == received && ack.length() == received.size && expected.size == wires.size) { "USB message confirmation did not match" }
        transaction { next -> items(next.getJSONArray("outbox")).filter { it.getString("id") in received && it.getString("kind") == "text" }.forEach { it.put("status", "delivered") } }
    }
    fun beginRenewal() { requireRenewalEligible(); transaction { it.put("version", 6).put("renewalPaused", true) } }
    fun cableIdentity(renew: Boolean = false): String {
        if (renew) { requireRenewalIdentity(); return state!!.getString("ownDevice") }
        requireCableEligible(); return identity.card(create = true)
    }
    fun stageRenewal(setup: JSONObject, peer: String) {
        requireRenewalEligible()
        val old = checkNotNull(state)
        check(old.optBoolean("renewalPaused") && peer == old.getString("peerDevice"))
        check(setup.getString("secret") == old.getString("secret") && setup.getString("endpoint") == old.getString("endpoint"))
        val history = JSONArray(view().messages.map { m -> JSONObject().put("incoming", m.incoming).put("text", m.text).put("status", m.status)
            .put("time", m.time).put("unread", m.unread).put("deviceVerified", m.deviceVerified) })
        val master = Protocol.master(old.getString("secret"))
        val bundle = java.util.Base64.getDecoder().decode(setup.getString("pad"))
        val pad = try { PadProtocol.material(bundle, PadProtocol.binding(master)) } finally { bundle.fill(0); master.fill(0) }
        try {
            val id = PadProtocol.hex(PadProtocol.id(pad))
            check(id != old.getString("padId"))
            claims?.pad(id, store.namespace); padLedger.register(id, PadProtocol.slots(pad))
            stagedCable = JSONObject(old.toString()).put("version", 6).put("history", history).put("padId", id)
                .put("pad", java.util.Base64.getEncoder().encodeToString(pad)).put("counter", 0).put("high", 0).put("seen", JSONArray())
                .put("inbox", JSONArray()).put("outbox", JSONArray()).put("opened", JSONObject()).put("acceptedPackets", JSONObject()).put("cableComplete", false)
            stagedCable!!.remove("readyPacket"); stagedCable!!.remove("readyId")
            stagedRenewal = true
        } finally { pad.fill(0) }
    }
    fun signCable(bytes: ByteArray) = identity.sign(bytes)
    fun stageCable(setup: JSONObject, role: String, peer: String) {
        requireCableEligible(); check(stagedCable == null)
        val endpoint = Protocol.endpoint(setup.getString("endpoint")); val token = Protocol.token(setup.getString("token"))
        val secret = setup.getString("secret"); val master = Protocol.master(secret)
        require(setup.getString("pad").length <= 5600000)
        val bundle = java.util.Base64.getDecoder().decode(setup.getString("pad"))
        val pad = try { PadProtocol.material(bundle, PadProtocol.binding(master)) } finally { master.fill(0); bundle.fill(0) }
        try {
            require(role in listOf("A", "B")); IdentityProtocol.publicKey(peer)
            val own = identity.card(); require(peer != own)
            val id = PadProtocol.hex(PadProtocol.id(pad))
            claims?.room(secret, store.namespace, existing = false); claims?.pad(id, store.namespace)
            padLedger.register(id, PadProtocol.slots(pad))
            stagedCable = JSONObject().put("version", if (pad.size > PadProtocol.MATERIAL_BYTES) 6 else 4).put("endpoint", endpoint).put("token", token).put("secret", secret)
                .put("role", role).put("counter", 0L).put("cursor", 0L).put("high", 0L).put("seen", JSONArray())
                .put("outbox", JSONArray()).put("inbox", JSONArray()).put("epoch", "")
                .put("pad", java.util.Base64.getEncoder().encodeToString(pad)).put("padId", id)
                .put("ownDevice", own).put("peerDevice", peer).put("padVerified", true).put("peerReady", true)
                .put("cableComplete", false)
        } finally { pad.fill(0) }
    }
    fun commitCable(complete: Boolean) {
        if (stagedRenewal) requireRenewalEligible() else requireCableEligible()
        val next = checkNotNull(stagedCable) { "No staged pairing" }.put("cableComplete", complete)
        validate(next); store.save(next); state = next; stagedCable = null; stagedRenewal = false; cachedView = null
    }
    fun finishCable() = transaction { it.put("cableComplete", true).remove("renewalPaused") }
    fun pairingInvite(): PairingInvite {
        store.requireActive()
        val s = checkNotNull(state) { "Save this station's link first" }
        return PairingInvite(s.getString("endpoint"), s.getString("token"), s.getString("secret"), if (s.getString("role") == "A") "B" else "A")
    }
    fun installPad(bundle: ByteArray) {
        store.requireActive()
        val current = checkNotNull(state) { "Save the link settings first" }
        check(!current.has("pad")) { "Forget pairing before installing a fresh pad" }
        check(current.getJSONArray("outbox").length() == 0 && current.getJSONArray("inbox").length() == 0 && current.getLong("counter") == 0L && current.getLong("high") == 0L) { "Start a fresh pairing on both stations before installing pads" }
        val master = Protocol.master(current.getString("secret"))
        val pad = try { PadProtocol.material(bundle, PadProtocol.binding(master)) } finally { master.fill(0) }
        try {
            val id = PadProtocol.hex(PadProtocol.id(pad))
            claims?.pad(id, store.namespace)
            padLedger.register(id, PadProtocol.slots(pad)) // A failed installation retires the bundle, never makes it reusable.
            transaction { it.put("version", if (pad.size > PadProtocol.MATERIAL_BYTES) 6 else 3).put("pad", java.util.Base64.getEncoder().encodeToString(pad)).put("padId", id).put("cursor", 0L).put("epoch", "") }
        } finally { pad.fill(0) }
    }
    fun generatePad(): ByteArray = generatePad(PadProtocol.MATERIAL_BYTES)
    fun generatePad(size: Int): ByteArray {
        val s = checkNotNull(state); require(s.getString("role") == "A") { "Generate on Station A; import on Station B" }
        val master = Protocol.master(s.getString("secret"))
        val bundle = try { PadProtocol.generate(PadProtocol.binding(master), size) } finally { master.fill(0) }
        try { installPad(bundle); return bundle } catch (e: Exception) { bundle.fill(0); throw e }
    }
    /** Local identity exchange uses public cards only. No relay, pad slots, or liveness claim. */
    fun prepareIdentity() {
        val s = checkNotNull(state)
        check(s.has("pad") && s.optBoolean("padVerified") && s.optBoolean("peerReady")) { "Finish pad verification on both stations first" }
        check(items(s.getJSONArray("outbox")).none { it.getString("status") == "queued" }) { "Sync queued packets before starting device binding" }
        if (s.has("ownDevice")) { identity.check(s.getString("ownDevice")); return }
        val card = identity.card(create = true)
        transaction { it.put("ownDevice", card) }
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
    fun markRead() { if ((state?.let { items(it.getJSONArray("inbox")).any { entry -> !entry.optBoolean("read", false) } || items(it.optJSONArray("history") ?: JSONArray()).any { entry -> entry.optBoolean("unread") } }) == true)
        transaction { s -> items(s.getJSONArray("inbox")).forEach { it.put("read", true) }; items(s.optJSONArray("history") ?: JSONArray()).forEach { it.put("unread", false) } } }
    private fun packetRole(s: JSONObject, wire: String) = if (s.has("pad")) PadProtocol.role(wire) else Protocol.role(wire)
    private fun open(s: JSONObject, master: ByteArray, wire: String, role: Char): Protocol.Opened {
        if (!s.has("pad")) return Protocol.open(master, wire, role)
        val pad = java.util.Base64.getDecoder().decode(s.getString("pad"))
        return try { PadProtocol.open(pad, wire, role, padIdentity(s)) } finally { pad.fill(0) }
    }
    private fun padIdentity(s: JSONObject): ByteArray = s.getString("padId").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun cacheOpened(s: JSONObject, wire: String, opened: Protocol.Opened) {
        val cache = s.optJSONObject("opened") ?: JSONObject().also { s.put("opened", it) }
        cache.put(wire, JSONObject().put("counter", opened.counter).put("id", opened.id).put("payload", opened.payload))
    }
    private fun retained(s: JSONObject, master: ByteArray, wire: String, role: Char): Protocol.Opened {
        val cached = s.optJSONObject("opened")?.optJSONObject(wire)
        if (cached != null && s.getInt("version") >= 5)
            return Protocol.Opened(cached.getLong("counter"), cached.getString("id"), cached.getString("payload"))
        return open(s, master, wire, role)
    }
    /** Cache retained records before atomically saving the snapshot with consumed material removed. */
    private fun retireUsed(s: JSONObject) {
        if (!s.has("pad")) return
        val pad = java.util.Base64.getDecoder().decode(s.getString("pad"))
        try {
            val wires = linkedSetOf<String>()
            for (entry in items(s.getJSONArray("outbox")) + items(s.getJSONArray("inbox"))) {
                wires.add(entry.getString("packet"))
                entry.optJSONObject("receipt")?.let { wires.add(it.getString("packet")) }
            }
            if (s.has("readyPacket")) wires.add(s.getString("readyPacket"))
            for (entry in items(s.getJSONArray("inbox"))) rememberPacket(s, entry.getString("id"), entry.getString("packet"))
            val previous = if (s.getInt("version") >= 5) s.optJSONObject("opened") else null
            val kept = JSONObject()
            for (wire in wires) {
                val cached = previous?.optJSONObject(wire)
                val opened = if (cached != null) Protocol.Opened(cached.getLong("counter"), cached.getString("id"), cached.getString("payload"))
                    else PadProtocol.open(pad, wire, checkNotNull(PadProtocol.role(wire)), padIdentity(s))
                kept.put(wire, JSONObject().put("counter", opened.counter).put("id", opened.id).put("payload", opened.payload))
            }
            val own = s.getString("role")[0]
            for (counter in 1..padLedger.high(s.getString("padId"))) PadProtocol.retire(pad, own, counter)
            val peer = if (own == 'A') 'B' else 'A'
            for (counter in window(s).received) PadProtocol.retire(pad, peer, counter)
            s.put("opened", kept).put("pad", java.util.Base64.getEncoder().encodeToString(pad)).put("version", maxOf(5, s.getInt("version")))
        } finally { pad.fill(0) }
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
    private fun queue(s: JSONObject, payload: JSONObject, kind: String): JSONObject {
        // Legacy receipt/retry support must never become a way to create new AES texts.
        check(kind != "text" || s.has("pad")) { "Physically pair message keys before sending" }
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
        var savedBody: String? = null
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
                    savedBody = body
                    sealed = PadProtocol.seal(pad, s.getString("role")[0], counter, body, padIdentity(s))
                } finally { pad.fill(0) }
            } else { counter = n + 1; sealed = Protocol.seal(master, s.getString("role")[0], counter, payload.toString()) }
        } finally { master.fill(0) }
        val (id, wire) = sealed
        s.put("counter", counter)
        val entry = JSONObject().put("id", id).put("packet", wire).put("kind", kind)
            .put("status", "queued").put("time", System.currentTimeMillis())
        savedBody?.let { cacheOpened(s, wire, Protocol.Opened(counter, id, it)) }
        s.getJSONArray("outbox").put(entry)
        prune(s)
        return entry
    }
    private fun prune(s: JSONObject) {
        val now = System.currentTimeMillis()
        if (s.has("history")) s.put("history", JSONArray(items(s.getJSONArray("history")).filter { now - it.getLong("time") < 86400000 }.takeLast(100)))
        val out = items(s.getJSONArray("outbox"))
        out.filter { it.getString("status") == "queued" && now - it.getLong("time") >= 86400000 }
            .forEach { it.put("status", "expired") }
        val pending = out.filter { it.getString("status") == "queued" }
        val final = out.filter { it.getString("status") != "queued" && it.getString("kind") == "text" && now - it.getLong("time") < 86400000 }.takeLast(100)
        s.put("outbox", JSONArray((final + pending).sortedBy { it.getLong("time") }))
        s.put("inbox", JSONArray(items(s.getJSONArray("inbox")).filter { now - it.getLong("time") < 86400000 }.takeLast(100)))
    }
    fun send(text: String) = sendText(text, null)
    /** Accepted draft ID commits with its ciphertext, so failed draft cleanup cannot resurrect a sent draft. */
    fun sendDraft(text: String, draftId: String) {
        require(ChatDraft.validId(draftId)); sendText(text, draftId)
    }
    private fun sendText(text: String, draftId: String?) {
        require(text.toByteArray(Charsets.UTF_8).size in 1..2048) { "Message must be 1–2048 UTF-8 bytes" }
        transaction { s ->
            check(s.has("pad")) { "Physically pair message keys before sending" }
            check(!s.optBoolean("renewalPaused")) { "Finish USB pad renewal on both devices first" }
            check(!s.optBoolean("relayRevoked")) { "Chat access revoked; pair a fresh chat on both devices" }
            check(s.optBoolean("cableComplete", true)) { "Cable pairing incomplete; arrange a fresh pairing on both devices" }
            check(!s.has("pad") || (s.optBoolean("padVerified") && s.optBoolean("peerReady"))) { "Open Pad: compare codes and wait for the other station to verify and sync" }
            check(!s.has("ownDevice") || s.has("peerDevice")) { "Finish physical device verification before sending" }
            prune(s)
            queue(s, JSONObject().put("kind", "text").put("text", text), "text")
            if (draftId != null) s.put("lastSentDraft", draftId)
        }
    }
    /** Repost retained ciphertext, preserving IDs, pad reservations and original expiry. */
    private fun retryUndelivered(s: JSONObject): Int {
        prune(s)
        val out = items(s.getJSONArray("outbox"))
        var room = 50 - out.count { it.getString("status") == "queued" }
        var count = 0
        for (entry in out) {
            if (room == 0) break
            if (entry.getString("kind") == "text" && entry.getString("status") == "relayed") {
                entry.put("status", "queued"); count++; room--
            }
        }
        return count
    }
    fun retryDelivery(): Int {
        var count = 0
        transaction { s ->
            check(s.optBoolean("cableComplete", true)) { "Finish physical pairing first" }
            count = retryUndelivered(s)
            retrySavedReceipts(s)
            // Re-read retained receipts too. The receive replay window is deliberately untouched.
            s.put("cursor", 0L)
        }
        return count
    }
    private fun retrySavedReceipts(s: JSONObject) {
        for (entry in items(s.getJSONArray("inbox")))
            recoverReceipt(s, entry.getString("id"), entry.getString("packet"), explicitRecovery = true)
    }
    private fun recoverReceipt(s: JSONObject, id: String, wire: String, explicitRecovery: Boolean = false) {
        val received = items(s.getJSONArray("inbox")).firstOrNull {
            it.getString("id") == id && it.getString("packet") == wire
        } ?: return
        val saved = received.optJSONObject("receipt") ?: return // Old history has no receipt cache.
        val now = System.currentTimeMillis()
        if (now - saved.getLong("time") >= 86400000 ||
            (!explicitRecovery && received.has("receiptRetryAt") && now - received.getLong("receiptRetryAt") < 60000)) return
        val out = items(s.getJSONArray("outbox"))
        if (out.any { it.getString("id") == saved.getString("id") } ||
            out.count { it.getString("status") == "queued" } >= 50) return
        s.getJSONArray("outbox").put(JSONObject(saved.toString()).put("status", "queued"))
        received.put("receiptRetryAt", now)
    }
    /** Rewrites the vault under a fresh key, so copies of the removed history stop being decryptable. */
    fun clearHistory() = transaction(rekey = true) { s ->
        s.remove("history")
        s.put("inbox", JSONArray())
        s.put("outbox", JSONArray(items(s.getJSONArray("outbox")).filter { it.getString("status") == "queued" }))
        // Do not clear the counter or replay window when removing message history.
    }
    fun revokeRelayAccess() {
        store.requireActive()
        val s = checkNotNull(state)
        check(s.optBoolean("relayScoped")) { "Sync with an updated relay before revoking chat access" }
        val master = Protocol.master(s.getString("secret"))
        try {
            try { relay.request(s.getString("endpoint"), Protocol.room(master), Protocol.relayCapability(master), "DELETE") }
            catch (e: RelayHttpException) { if (e.status != 410) throw e }
            transaction { it.put("relayRevoked", true) }
        } finally { master.fill(0) }
    }
    fun sync(): String {
        store.requireActive()
        check(state?.optBoolean("renewalPaused") != true) { "USB renewal paused; reconnect both devices to recover" }
        check(state?.optBoolean("relayRevoked") != true) { "Chat access revoked; pair a fresh chat on both devices" }
        check(state?.optBoolean("cableComplete", true) != false) { "Cable pairing incomplete; arrange a fresh pairing on both devices" }
        if (state != null) transaction(change = ::prune)
        val s = checkNotNull(state) { "Save your link settings first" }
        val master = Protocol.master(s.getString("secret"))
        var ignored = 0
        try {
            val room = Protocol.room(master)
            if (s.has("pad") && !s.optBoolean("relayScoped") &&
                relay.scope(s.getString("endpoint"), room, s.getString("token"), Protocol.relayCapability(master))) {
                // Persist protection before traffic. A later failure must never fall back to shared access.
                transaction { it.put("relayScoped", true) }
            }
            val credential = if (state!!.optBoolean("relayScoped")) Protocol.relayCapability(master) else s.getString("token")
            val response = JSONObject(try {
                relay.request(s.getString("endpoint"), room, credential, "GET", cursor = s.getLong("cursor"))
            } catch (e: RelayHttpException) {
                if (e.status != 401 || !state!!.optBoolean("relayScoped")) throw e
                // Lost relay volume: restore this same claim, never read/write with the setup token.
                check(relay.scope(s.getString("endpoint"), room, s.getString("token"), credential)) { "Update the relay; protected chat access cannot be downgraded" }
                relay.request(s.getString("endpoint"), room, credential, "GET", cursor = s.getLong("cursor"))
            })
            if (state!!.optBoolean("relayScoped")) check(response.optString("access") == "chat") { "Relay chat protection unavailable; access was not downgraded" }
            check(response.optInt("protocol") == 2) { "Update the relay container before using this APK" }
            val epoch = response.getString("epoch")
            if (s.getString("epoch").isNotEmpty() && s.getString("epoch") != epoch) {
                var recovered = 0
                transaction { next -> next.put("epoch", epoch).put("cursor", 0L); recovered = retryUndelivered(next); retrySavedReceipts(next) }
                return "RELAY STORAGE CHANGED // $recovered saved messages queued for recovery"
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
                        // A retired slot cannot authenticate again. Only the exact previously accepted
                        // ciphertext may recover its saved receipt; altered packets still fail closed.
                        val known = items(next.getJSONArray("inbox")).firstOrNull { it.getString("packet") == wire }
                        if (known != null) {
                            recoverReceipt(next, known.getString("id"), wire)
                            cursor = seq; next.put("cursor", cursor); continue
                        }
                        // Authentication and payload validation finish before touching replay state.
                        val decoded = try { open(next, master, wire, inbound) } catch (_: Exception) { ignored++; null }
                        if (decoded != null) {
                            // Hold signed packets while the physical key exchange is incomplete.
                            if (next.has("ownDevice") && !next.has("peerDevice") && IdentityProtocol.isSigned(decoded.payload)) break
                            val payload = try { JSONObject(deviceBody(next, decoded, inbound, enforce = true)) } catch (_: Exception) { null }
                            val kind = payload?.optString("kind")
                            if (!window(next).accepts(decoded.counter)) {
                                // Authenticate the device signature before allowing even a cached receipt retry.
                                if (kind == "text") recoverReceipt(next, decoded.id, wire)
                            } else if (kind == "text" && payload?.opt("text") is String && payload.getString("text").toByteArray(Charsets.UTF_8).size in 1..2048) {
                                if (items(next.getJSONArray("outbox")).count { it.getString("status") == "queued" } >= 50) break
                                val received = JSONObject().put("packet", wire).put("id", decoded.id).put("read", false).put("time", System.currentTimeMillis())
                                if (next.has("pad")) cacheOpened(next, wire, decoded)
                                next.getJSONArray("inbox").put(received)
                                // Exhaustion must not prevent receiving. Last available slots can acknowledge only a finite number of messages.
                                if (!next.has("pad") || padLedger.high(next.getString("padId")) < padCapacity(next))
                                    received.put("receipt", JSONObject(queue(next, JSONObject().put("kind", "receipt").put("for", decoded.id), "receipt").toString()))
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
                store.requireActive()
                relay.request(s.getString("endpoint"), room, credential, "POST", JSONObject().put("packet", entry.getString("packet")).toString())
                transaction { next ->
                    items(next.getJSONArray("outbox")).firstOrNull { it.getString("id") == entry.getString("id") }?.put("status", "relayed")
                    prune(next)
                }
            }
            return "Connected" + if (ignored > 0) " · $ignored invalid/legacy packets skipped" else ""
        } catch (e: RelayHttpException) {
            if (e.status == 410 && state?.has("pad") == true) transaction { it.put("relayScoped", true).put("relayRevoked", true) }
            throw e
        } finally { master.fill(0) }
    }
    data class Message(val incoming: Boolean, val text: String, val status: String, val time: Long, val unread: Boolean = false, val deviceVerified: Boolean = false)
    data class View(val role: String, val fingerprint: String, val messages: List<Message>, val pending: Int, val unread: Int = 0, val padRemaining: Int? = null, val padCheck: String? = null, val padVerified: Boolean = false, val peerReady: Boolean = false, val ownDevice: String? = null, val peerDevice: String? = null, val keyProtection: String? = null, val pairingComplete: Boolean = true, val cableEligible: Boolean = true, val awaitingReceipt: Int = 0, val relayScoped: Boolean = false, val relayRevoked: Boolean = false) {
        val setupComplete: Boolean get() = pairingComplete && padCheck != null && padVerified && peerReady && peerDevice != null
        val canSend: Boolean get() = !relayRevoked && pairingComplete && role != "—" && padRemaining != null && padRemaining > 0 && padCheck != null && padVerified && peerReady && (ownDevice == null || peerDevice != null)
    }
    private fun padCapacity(s: JSONObject): Int {
        val pad = java.util.Base64.getDecoder().decode(s.getString("pad"))
        return try { PadProtocol.slots(pad) } finally { pad.fill(0) }
    }
    fun view(): View {
        store.requireActive()
        cachedView?.let { if (System.currentTimeMillis() < cacheExpiry) return it }
        val s = state ?: return View("—", "NOT PAIRED", emptyList(), 0)
        val master = Protocol.master(s.getString("secret"))
        return try {
            val own = s.getString("role")[0]; val peer = if (own == 'A') 'B' else 'A'
            val rows = mutableListOf<Message>()
            for (entry in items(s.optJSONArray("history") ?: JSONArray()).filter { System.currentTimeMillis() - it.getLong("time") < 86400000 }) rows.add(Message(entry.getBoolean("incoming"), entry.getString("text"), entry.getString("status"), entry.getLong("time"), entry.optBoolean("unread"), entry.optBoolean("deviceVerified")))
            for (entry in items(s.getJSONArray("inbox"))) {
                if (System.currentTimeMillis() - entry.getLong("time") >= 86400000) continue
                val decoded = retained(s, master, entry.getString("packet"), peer)
                val text = JSONObject(deviceBody(s, decoded, peer, enforce = false)).getString("text")
                rows.add(Message(true, text, "received", entry.getLong("time"), !entry.optBoolean("read", false), IdentityProtocol.isSigned(decoded.payload)))
            }
            for (entry in items(s.getJSONArray("outbox")).filter { it.getString("kind") == "text" }) {
                if (System.currentTimeMillis() - entry.getLong("time") >= 86400000) continue
                val decoded = retained(s, master, entry.getString("packet"), own)
                val text = JSONObject(deviceBody(s, decoded, own, enforce = false)).getString("text")
                rows.add(Message(false, text, entry.getString("status"), entry.getLong("time"), deviceVerified = IdentityProtocol.isSigned(decoded.payload)))
            }
            cacheExpiry = rows.minOfOrNull { it.time + 86400000L } ?: Long.MAX_VALUE
            View(own.toString(), Protocol.fingerprint(master), rows.sortedBy { it.time }.takeLast(100),
                items(s.getJSONArray("outbox")).count { it.getString("status") == "queued" },
                items(s.getJSONArray("inbox")).count { !it.optBoolean("read", false) } + items(s.optJSONArray("history") ?: JSONArray()).count { it.optBoolean("unread") },
                if (s.has("pad")) padCapacity(s) - padLedger.high(s.getString("padId")).toInt() else null,
                s.optString("padId").takeIf { it.isNotEmpty() }, s.optBoolean("padVerified"), s.optBoolean("peerReady"),
                s.optString("ownDevice").takeIf { it.isNotEmpty() }, s.optString("peerDevice").takeIf { it.isNotEmpty() },
                if (s.has("ownDevice")) identity.protection() else null, s.optBoolean("cableComplete", true) && !s.optBoolean("renewalPaused"),
                !s.has("pad") && s.getLong("counter") == 0L && s.getLong("high") == 0L && s.getJSONArray("outbox").length() == 0 && s.getJSONArray("inbox").length() == 0,
                rows.count { !it.incoming && it.status == "relayed" }, s.optBoolean("relayScoped"), s.optBoolean("relayRevoked")).also { cachedView = it }
        } finally { master.fill(0) }
    }
}
