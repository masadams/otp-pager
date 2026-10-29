package org.example.otppager

import org.json.JSONArray
import org.json.JSONObject

/** Call only from the single station worker. Save snapshot before publishing it in memory. */
class StationEngine(private val store: SecureStore, relay: RelayTransport = RelayClient, private val claims: PairingClaims? = null) {
    /** Unscoped transport for best-effort peer-wipe acknowledgements sent after this chat is retired. */
    private val baseRelay = relay
    private val relay = relay.forContact(store)
    companion object {
        /** Paired with pad material, either in the vault (pre-migration "pad") or separate storage ("padKeys"). */
        internal fun hasPad(s: JSONObject) = s.has("pad") || s.has("padKeys")
        const val MAX_BACKUPS = 2
        const val MAX_GROUPS = 16
        const val MAX_GROUP_MEMBERS = 8
        private val GROUP_ID = Regex("[a-f0-9]{32}")
        /** Canonical `[{endpoint, token}]`, validated; order is the fallback order. */
        internal fun parseBackups(list: JSONArray): JSONArray {
            require(list.length() <= MAX_BACKUPS) { "At most $MAX_BACKUPS backup relays" }
            return JSONArray((0 until list.length()).map { i -> list.getJSONObject(i).let {
                JSONObject().put("endpoint", Protocol.endpoint(it.getString("endpoint"))).put("token", Protocol.token(it.getString("token")))
            } }.also { l -> require(l.map { it.getString("endpoint") }.distinct().size == l.size) })
        }
        /** A strict lease ended: live engines for that chat drop their decrypted state. */
        internal fun lockStrict(key: String) = synchronized(VaultLifecycle.lock) {
            live.filterValues { it == key }.keys.forEach { it.snapshot = null; it.cached = null }
        }
        private val b64e = java.util.Base64.getEncoder()
        private val b64d = java.util.Base64.getDecoder()
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
    /** A strict chat's inner state, opened with the in-memory share; [StrictChat.Locked] otherwise. */
    @Volatile private var snapshot: JSONObject? = StrictChat.open(VaultLifecycle.root(store.namespace), store.load())
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
    private val materialRoot = VaultLifecycle.root(store.namespace).also { require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(it)) }
    private fun materialFile(padId: String) = PadMaterial.file(store.directory, materialRoot, padId)
    private fun writeMaterial(padId: String, pad: ByteArray): String {
        val keys = PadMaterial.write(materialFile(padId), padId, pad, VaultLifecycle::syncDirectory)
        return try { b64e.encodeToString(keys) } finally { keys.fill(0) }
    }
    init { state?.let { validate(it); claims?.migrate(store); transaction { next -> if (hasPad(next) && next.getInt("version") == 2) next.put("version", 3).put("cursor", 0L).put("epoch", ""); prune(next) }; migratePadStorage() } }
    /**
     * Moves an in-vault pad into separate storage once (docs/PAD_STORAGE.md). The in-vault pad stays
     * authoritative until the forced-rotation save that replaces it commits; a crash before that
     * only leaves an unreferenced material file, removed on the next open.
     */
    private fun migratePadStorage() = synchronized(VaultLifecycle.lock) {
        val s = state ?: return@synchronized
        if (s.has("pad")) {
            val id = s.getString("padId")
            val pad = b64d.decode(s.getString("pad"))
            materialFile(id).delete() // Only an interrupted earlier migration could have left it; the vault never referenced it.
            val keys = try { writeMaterial(id, pad) } finally { pad.fill(0) }
            transaction(rekey = true) { it.remove("pad"); it.put("padKeys", keys) }
        }
        val current = checkNotNull(state)
        if (current.has("padKeys")) {
            val file = materialFile(current.getString("padId"))
            val slots = padCapacity(current)
            check(file.length() == PadMaterial.HEADER.toLong() + 2L * slots * PadMaterial.RECORD) { "Pad storage missing or damaged; arrange a fresh pairing" }
        }
        // Another live owner of this chat may hold a staged renewal or pairing whose material is not
        // yet referenced by the vault; only an owner that is alone may discard unreferenced files.
        if (live.values.none { it == store.operationKey })
            PadMaterial.prune(store.directory, materialRoot, if (current.has("padKeys")) setOf(current.getString("padId")) else emptySet())
    }
    private fun validate(s: JSONObject) {
        check(s.getInt("version") in 2..6)
        check(s.getInt("version") !in listOf(3, 5, 6) || hasPad(s))
        if (s.has("ownDevice")) {
            check(s.getInt("version") in 4..6 && hasPad(s))
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
        check(!(s.has("pad") && s.has("padKeys")))
        s.optJSONObject("groups")?.let { g ->
            check(g.length() in 1..MAX_GROUPS && g.keys().asSequence().all { id -> GROUP_ID.matches(id) &&
                g.getJSONObject(id).getJSONArray("members").length() in 1..MAX_GROUP_MEMBERS })
        }
        s.optJSONArray("backups")?.let { list ->
            check(list.length() in 1..MAX_BACKUPS)
            for (i in 0 until list.length()) list.getJSONObject(i).let {
                Protocol.endpoint(it.getString("endpoint")); Protocol.token(it.getString("token"))
                check(it.getLong("cursor") >= 0 && it.has("epoch") && it.getString("endpoint") != s.getString("endpoint"))
            }
        }
        s.optJSONObject("control")?.let { c ->
            check(Regex("[a-f0-9]{32}").matches(c.getString("generation")))
            for (name in listOf("credential", "ctlOut", "ctlIn", "ackOut", "ackIn")) check(Regex("[a-f0-9]{64}").matches(c.getString(name)))
            check(c.getString("ctlOut") != c.getString("ctlIn") && c.getString("ackOut") != c.getString("ackIn"))
            check(c.optLong("cursor", 0) >= 0)
        }
        if (s.has("padKeys")) {
            val keys = b64d.decode(s.getString("padKeys"))
            try {
                check(PadProtocol.validSlots(PadMaterial.slots(keys)))
                check(Regex("[a-f0-9]{32}").matches(s.getString("padId")))
                check(padLedger.high(s.getString("padId")) >= s.getLong("counter"))
            } finally { keys.fill(0) }
        }
        if (s.has("pad")) {
            val pad = java.util.Base64.getDecoder().decode(s.getString("pad"))
            try {
                check(PadProtocol.validMemorySize(pad.size))
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
        // A forced rekey always rewrites: older snapshots may still hold content already removed here.
        if (!rekey && next.toString() == state!!.toString()) return
        persist(next, rekey); state = next; cachedView = null
    }
    private val strictRoot get() = VaultLifecycle.root(store.namespace)
    /** Every vault write goes through here: strict chats are re-sealed under their share. */
    private fun persist(next: JSONObject, rekey: Boolean = false) {
        if (!next.has("strictShare")) { store.save(next, rekey); return }
        val share = StrictChat.cached(strictRoot) ?: throw StrictChat.Locked()
        try { store.save(StrictChat.wrap(strictRoot, next, share), rekey) } finally { share.fill(0) }
    }
    internal val isStrict: Boolean get() = state?.has("strictShare") == true
    /** Pairing: mark the staged chat strict. The share is created and stored at commit. */
    internal fun stageStrict(deadline: Long) {
        val staged = checkNotNull(stagedCable) { "No staged pairing" }
        if (stagedRenewal) return // Renewal keeps the chat's existing mode and share.
        if (deadline == 0L) { staged.remove("strictPending"); return }
        require(deadline in StrictChat.DEADLINES) { "Unknown strict deadline" }
        check(staged.has("control")) { "Strict chats need control keys from this pairing version" }
        staged.put("strictPending", deadline)
    }
    /**
     * Creates this phone's share, claims its room, pins the relay's unlock authority and enrolls
     * the share bound to this phone's device key, then seals the vault under it (one lease).
     */
    private fun establishStrict(next: JSONObject) {
        val deadline = next.optLong("strictPending", 0L).takeIf { it > 0 } ?: return
        val c = next.getJSONObject("control")
        val credential = unhex(c.getString("credential"))
        val (room, capability) = try { StrictChat.room(credential, next.getString("role")) to PeerWipe.capability(credential) } finally { credential.fill(0) }
        check(relay.scope(next.getString("endpoint"), room, next.getString("token"), capability)) { "This server does not support strict chats. Update the relay or pair a normal chat." }
        val authority = relay.authority(next.getString("endpoint"))
        val share = StrictChat.newShare()
        try {
            relay.share(next.getString("endpoint"), room, capability, "PUT", JSONObject().put("share", StrictChat.encode(share))
                .put("deadline", deadline).put("device", identity.card(create = true)).toString())
            StrictChat.remember(strictRoot, share, StrictChatProtocol.MAX_LEASE_MS, store.operationKey)
        } finally { share.fill(0) }
        next.remove("strictPending")
        val peerRoom = unhex(c.getString("credential")).let { try { StrictChat.room(it, if (next.getString("role") == "A") "B" else "A") } finally { it.fill(0) } }
        next.put("strictShare", JSONObject().put("room", room).put("peerRoom", peerRoom).put("capability", capability).put("deadline", deadline).put("authority", authority))
    }
    /**
     * Every foreground check renews the lease with a fresh signed unlock (which is also the
     * check-in). A deleted share means the chat was wiped elsewhere or lapsed: retire it here too.
     */
    private fun checkInStrict(): String? {
        val s = state ?: return null
        val strict = s.optJSONObject("strictShare") ?: return null
        val route = StrictChat.Route(s.getString("endpoint"), strict.getString("room"), strict.getString("capability"),
            strict.getString("authority"), s.getString("role").single(), unhex(s.getJSONObject("control").getString("generation")))
        try {
            val (share, millis) = StrictChat.grant(relay, route, identity)
            try { StrictChat.remember(strictRoot, share, millis, store.operationKey) } finally { share.fill(0) }
            return null
        } catch (_: StrictChat.Gone) {
        } catch (_: Exception) { return null } // Offline: the current lease simply runs out.
        val root = strictRoot
        StrictChat.forget(root)
        VaultRetirement.retireContact(store.context, root)
        return "This strict chat's key share is gone from the server, so it was removed from this phone."
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
        persist(next); state = next; cachedView = null
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
        validate(next); persist(next); state = next; cachedView = null
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
        validate(next); persist(next); state = next; stagedCable = null; cachedView = null
        PadMaterial.prune(store.directory, materialRoot, emptySet())
        // The separate retirement/reservation ledger and non-exportable device key remain intact.
    }
    fun requireCableEligible() {
        store.requireActive()
        val s = state ?: return
        check(!hasPad(s) && s.getLong("counter") == 0L && s.getLong("high") == 0L &&
            s.getJSONArray("outbox").length() == 0 && s.getJSONArray("inbox").length() == 0) {
            "This station already has a pairing or traffic. Preserve it; start a fresh pairing on both devices before replacing it."
        }
    }
    fun requireRenewalIdentity() {
        store.requireActive()
        val s = checkNotNull(state) { "Pair this chat first" }
        check(hasPad(s) && s.has("ownDevice") && s.has("peerDevice") && !s.optBoolean("relayRevoked")) { "A verified paired chat is required" }
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
    /** Pairing-time destination for a pad streamed over the encrypted channel (never whole in memory). */
    internal fun padWriter(size: Int): PadMaterial.Writer {
        store.requireActive()
        return PadMaterial.Writer(PadMaterial.directory(store.directory, materialRoot), size, VaultLifecycle::syncDirectory)
    }
    /** In-memory bundle form (pad file, tests, pre-v5 peers): installed through the same staging. */
    private fun installBundle(setup: JSONObject, secret: String): PadMaterial.Installed {
        require(setup.getString("pad").length <= 5600000)
        val master = Protocol.master(secret)
        val bundle = java.util.Base64.getDecoder().decode(setup.getString("pad"))
        val pad = try { PadProtocol.material(bundle, PadProtocol.binding(master)) } finally { bundle.fill(0); master.fill(0) }
        return try { padWriter(pad.size).use { it.write(pad); it.finish() } } finally { pad.fill(0) }
    }
    fun stageRenewal(setup: JSONObject, peer: String) = stageRenewal(setup, peer, installBundle(setup, checkNotNull(state).getString("secret")))
    internal fun stageRenewal(setup: JSONObject, peer: String, pad: PadMaterial.Installed) {
        try {
            requireRenewalEligible()
            val old = checkNotNull(state)
            check(old.optBoolean("renewalPaused") && peer == old.getString("peerDevice"))
            check(setup.getString("secret") == old.getString("secret") && setup.getString("endpoint") == old.getString("endpoint"))
            val history = JSONArray(view().messages.map { m -> JSONObject().put("incoming", m.incoming).put("text", m.text).put("status", m.status)
                .put("time", m.time).put("unread", m.unread).put("deviceVerified", m.deviceVerified) })
            val id = pad.padId
            check(id != old.getString("padId"))
            claims?.pad(id, store.namespace); padLedger.register(id, PadMaterial.slots(pad.keys))
            stagedCable = JSONObject(old.toString()).put("version", 6).put("history", history).put("padId", id)
                .put("padKeys", b64e.encodeToString(pad.keys)).put("counter", 0).put("high", 0).put("seen", JSONArray())
                .put("inbox", JSONArray()).put("outbox", JSONArray()).put("opened", JSONObject()).put("acceptedPackets", JSONObject()).put("cableComplete", false)
            stagedCable!!.remove("readyPacket"); stagedCable!!.remove("readyId"); stagedCable!!.remove("pad")
            if (setup.has("backups")) stageBackups(stagedCable!!, setup)
            stagedRenewal = true
        } catch (failure: Exception) { pad.file.delete(); throw failure }
        finally { pad.keys.fill(0) }
    }
    fun signCable(bytes: ByteArray) = identity.sign(bytes)
    /**
     * Peer-wipe control material (docs/PEER_WIPE_PROTOCOL.md §1), generated during in-person
     * pairing and exchanged inside the authenticated pairing channel. Role A also names the
     * pairing generation and the control-mailbox credential. Never derived from pad bytes.
     */
    internal fun controlOffer(host: Boolean): JSONObject {
        fun random(n: Int) = PadProtocol.hex(ByteArray(n).also { java.security.SecureRandom().nextBytes(it) })
        val offer = JSONObject().put("kind", "control").put("ctl", random(32)).put("ack", random(32))
        if (host) offer.put("generation", random(16)).put("credential", random(32))
        return offer
    }
    internal fun stageControl(own: JSONObject, peer: JSONObject, host: Boolean) {
        val staged = checkNotNull(stagedCable) { "No staged pairing" }
        require(own.getString("kind") == "control" && peer.getString("kind") == "control")
        val origin = if (host) own else peer
        staged.put("control", JSONObject().put("generation", origin.getString("generation")).put("credential", origin.getString("credential"))
            .put("ctlOut", own.getString("ctl")).put("ctlIn", peer.getString("ctl"))
            .put("ackOut", own.getString("ack")).put("ackIn", peer.getString("ack")).put("cursor", 0L))
        validate(staged)
    }
    internal val hasControl: Boolean get() = state?.has("control") == true
    private fun unhex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    /**
     * UNREVIEWED peer wipe, recipient side (§5). Only with the setting on. Verifies a WIPE for this
     * pairing's own generation and the peer's direction, precomputes the acknowledgements, then
     * retires THIS chat only with the ordinary local primitive. Returns a status line, or null.
     */
    private fun pollControl(): String? {
        val s = state ?: return null
        val c = s.optJSONObject("control") ?: return null
        if (!PeerWipe.enabled(store.context)) return null
        val credential = unhex(c.getString("credential"))
        val generation = unhex(c.getString("generation"))
        val peer = if (s.getString("role") == "A") ControlProtocol.Direction.B_TO_A else ControlProtocol.Direction.A_TO_B
        val requested: Boolean
        try {
            val room = ControlProtocol.mailbox(credential); val capability = PeerWipe.capability(credential)
            if (!c.optBoolean("scoped") && relay.scope(s.getString("endpoint"), room, s.getString("token"), capability))
                transaction { it.getJSONObject("control").put("scoped", true) }
            val access = if (state!!.getJSONObject("control").optBoolean("scoped")) capability else s.getString("token")
            val page = JSONObject(relay.request(s.getString("endpoint"), room, access, "GET", cursor = c.optLong("cursor")))
            val messages = page.getJSONArray("messages"); require(messages.length() <= 50) { "Relay returned an oversized page" }
            var cursor = c.optLong("cursor"); var found = false
            val epoch = page.optString("epoch")
            if (c.optString("epoch").isNotEmpty() && epoch != c.optString("epoch")) cursor = 0
            for (i in 0 until messages.length()) {
                val item = messages.getJSONObject(i); cursor = maxOf(cursor, item.getLong("seq"))
                val frame = try { java.util.Base64.getDecoder().decode(item.getString("packet")) } catch (_: Exception) { continue }
                if (frame.size != ControlProtocol.WIPE_BYTES) continue
                val key = unhex(c.getString("ctlIn"))
                try { ControlProtocol.openWipe(key, frame, peer, generation); found = true } catch (_: Exception) {} finally { key.fill(0) }
            }
            transaction { it.getJSONObject("control").put("cursor", cursor).put("epoch", epoch) }
            requested = found
        } catch (_: Exception) { return null } // Control polling never blocks ordinary sync.
        finally { credential.fill(0); generation.fill(0) }
        if (!requested) return null
        val snapshot = JSONObject(checkNotNull(state).toString())
        val root = VaultLifecycle.root(store.namespace)
        val result = PeerWipeRecipient.retire(
            prepare = { PeerWipe.acknowledge(store.context, root, snapshot); store.load()?.let { PeerWipe.prepareShareDeletion(store.context, root, it) } },
            destroy = { VaultRetirement.retireContact(store.context, root) },
            finish = { PeerWipe.finish(store.context, snapshot.getJSONObject("control").getString("generation"), it.completed) },
            dispatch = { PeerWipe.pump(store.context, baseRelay) })
        return if (result.completed) "Your friend's phone asked to remove this chat. It was removed from this phone."
            else "Your friend's phone asked to remove this chat. Local removal did not finish; retry cleanup from recovery."
    }
    fun stageCable(setup: JSONObject, role: String, peer: String) {
        requireCableEligible(); check(stagedCable == null)
        stageCable(setup, role, peer, installBundle(setup, setup.getString("secret")))
    }
    internal fun stageCable(setup: JSONObject, role: String, peer: String, pad: PadMaterial.Installed) {
        try {
            requireCableEligible(); check(stagedCable == null)
            val endpoint = Protocol.endpoint(setup.getString("endpoint")); val token = Protocol.token(setup.getString("token"))
            val secret = setup.getString("secret"); Protocol.master(secret).fill(0)
            require(role in listOf("A", "B")); IdentityProtocol.publicKey(peer)
            val own = identity.card(); require(peer != own)
            val id = pad.padId; val slots = PadMaterial.slots(pad.keys)
            claims?.room(secret, store.namespace, existing = false); claims?.pad(id, store.namespace)
            padLedger.register(id, slots)
            stagedCable = JSONObject().put("version", if (slots > PadProtocol.SLOTS) 6 else 4).put("endpoint", endpoint).put("token", token).put("secret", secret)
                .put("role", role).put("counter", 0L).put("cursor", 0L).put("high", 0L).put("seen", JSONArray())
                .put("outbox", JSONArray()).put("inbox", JSONArray()).put("epoch", "")
                .put("padKeys", b64e.encodeToString(pad.keys)).put("padId", id)
                .put("ownDevice", own).put("peerDevice", peer).put("padVerified", true).put("peerReady", true)
                .put("cableComplete", false)
            stageBackups(stagedCable!!, setup)
        } catch (failure: Exception) { pad.file.delete(); throw failure }
        finally { pad.keys.fill(0) }
    }
    fun commitCable(complete: Boolean) {
        if (stagedRenewal) requireRenewalEligible() else requireCableEligible()
        val next = checkNotNull(stagedCable) { "No staged pairing" }.put("cableComplete", complete)
        establishStrict(next)
        validate(next); persist(next); state = next; stagedCable = null; stagedRenewal = false; cachedView = null
        PadMaterial.prune(store.directory, materialRoot, setOf(next.getString("padId"))) // Replaced pad material is never needed again.
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
        check(!hasPad(current)) { "Forget pairing before installing a fresh pad" }
        check(current.getJSONArray("outbox").length() == 0 && current.getJSONArray("inbox").length() == 0 && current.getLong("counter") == 0L && current.getLong("high") == 0L) { "Start a fresh pairing on both stations before installing pads" }
        val master = Protocol.master(current.getString("secret"))
        val pad = try { PadProtocol.material(bundle, PadProtocol.binding(master)) } finally { master.fill(0) }
        try {
            val id = PadProtocol.hex(PadProtocol.id(pad))
            claims?.pad(id, store.namespace)
            padLedger.register(id, PadProtocol.slots(pad)) // A failed installation retires the bundle, never makes it reusable.
            val keys = writeMaterial(id, pad)
            transaction { it.put("version", if (pad.size > PadProtocol.MATERIAL_BYTES) 6 else 3).put("padKeys", keys).put("padId", id).put("cursor", 0L).put("epoch", "") }
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
        check(hasPad(s) && s.optBoolean("padVerified") && s.optBoolean("peerReady")) { "Finish pad verification on both stations first" }
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
            check(hasPad(s)) { "Install a pad first" }
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
    private fun packetRole(s: JSONObject, wire: String) = if (hasPad(s)) PadProtocol.role(wire) else Protocol.role(wire)
    private fun open(s: JSONObject, master: ByteArray, wire: String, role: Char): Protocol.Opened {
        if (!hasPad(s)) return Protocol.open(master, wire, role)
        return openPad(s, wire, role)
    }
    private fun openPad(s: JSONObject, wire: String, role: Char): Protocol.Opened {
        val id = padIdentity(s)
        if (s.has("pad")) {
            val pad = b64d.decode(s.getString("pad"))
            return try { PadProtocol.open(pad, wire, role, id) } finally { pad.fill(0) }
        }
        val slot = padSlot(s, role, PadProtocol.counter(wire, role, id))
        return try { PadProtocol.openSlot(slot, wire, role, id) } finally { slot.fill(0) }
    }
    private fun padSlot(s: JSONObject, role: Char, counter: Long): ByteArray {
        val keys = b64d.decode(s.getString("padKeys"))
        return try { PadMaterial.slot(materialFile(s.getString("padId")), s.getString("padId"), keys, role, counter) } finally { keys.fill(0) }
    }
    private fun sealPad(s: JSONObject, role: Char, counter: Long, body: String): Pair<String, String> {
        val id = padIdentity(s)
        if (s.has("pad")) {
            val pad = b64d.decode(s.getString("pad"))
            return try { PadProtocol.seal(pad, role, counter, body, id) } finally { pad.fill(0) }
        }
        val slot = padSlot(s, role, counter)
        return try { PadProtocol.sealSlot(slot, role, counter, body, id) } finally { slot.fill(0) }
    }
    /** Test and diagnostics only: the pad as the legacy layout, with every used or retired slot zeroed. */
    internal fun padSnapshot(): ByteArray {
        val s = checkNotNull(state)
        if (s.has("pad")) return b64d.decode(s.getString("pad"))
        val slots = padCapacity(s)
        val size = PadProtocol.SIZES.first { it / 2 / PadProtocol.STRIDE == slots }
        val pad = ByteArray(size)
        val keys = b64d.decode(s.getString("padKeys"))
        try {
            for (role in listOf('A', 'B')) for (counter in 1..slots.toLong()) if (PadMaterial.available(keys, role, counter)) {
                val slot = padSlot(s, role, counter)
                slot.copyInto(pad, (if (role == 'A') 0 else size / 2) + (counter.toInt() - 1) * PadProtocol.STRIDE); slot.fill(0)
            }
        } finally { keys.fill(0) }
        return pad
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
        if (!hasPad(s)) return
        if (s.has("padKeys")) { retireUsedSlots(s); return }
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
    /** Separate storage: same retained-record caching, then zero each consumed slot's key in this save. */
    private fun retireUsedSlots(s: JSONObject) {
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
                else openPad(s, wire, checkNotNull(PadProtocol.role(wire)))
            kept.put(wire, JSONObject().put("counter", opened.counter).put("id", opened.id).put("payload", opened.payload))
        }
        val keys = b64d.decode(s.getString("padKeys"))
        try {
            val slots = PadMaterial.slots(keys)
            val own = s.getString("role")[0]; val peer = if (own == 'A') 'B' else 'A'
            var changed = false
            for (counter in 1..minOf(padLedger.high(s.getString("padId")), slots.toLong())) changed = PadMaterial.retire(keys, own, counter) || changed
            for (counter in window(s).received) if (counter in 1..slots.toLong()) changed = PadMaterial.retire(keys, peer, counter) || changed
            if (changed) s.put("padKeys", b64e.encodeToString(keys))
            s.put("opened", kept).put("version", maxOf(5, s.getInt("version")))
        } finally { keys.fill(0) }
    }
    fun forget() { store.forget(); state = null; cachedView = null; PadMaterial.deleteAll(store.directory, materialRoot); StrictChat.forget(strictRoot) }
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
        check(kind != "text" || hasPad(s)) { "Physically pair message keys before sending" }
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
            if (hasPad(s)) {
                require(payload.toString().toByteArray(Charsets.UTF_8).size <= PadProtocol.BLOCK - 2) { "Encoded message too large for a pad slot" }
                run {
                    cachedView = null
                    counter = padLedger.reserve(s.getString("padId")) // Durable burn BEFORE encryption or network output.
                    val body = if (s.has("ownDevice")) {
                        check(s.has("peerDevice")) { "Verify the other device identity first" }
                        identity.check(s.getString("ownDevice"))
                        IdentityProtocol.signed(payload.toString()) { identity.sign(signatureData(s, s.getString("role")[0], counter, it)) }
                    } else payload.toString()
                    savedBody = body
                    sealed = sealPad(s, s.getString("role")[0], counter, body)
                }
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
    /**
     * Group chats (pipeline item 7, docs/GROUP_CHATS.md): a group is a label on ordinary pairwise
     * messages. Sending to a group sends one separately pad-encrypted copy per member chat
     * (ChatBook.sendGroup). Membership lives in each member chat's own vault under "groups",
     * so wiping a chat also removes it from every group.
     */
    internal fun groups(): List<Group> = state?.optJSONObject("groups")?.let { g -> g.keys().asSequence().map { id -> g.getJSONObject(id).let {
        Group(id, it.getString("name"), (0 until it.getJSONArray("members").length()).map { i -> it.getJSONArray("members").getString(i) }) } }.toList() }.orEmpty()
    private fun putGroup(s: JSONObject, id: String, name: String, members: List<String>) {
        require(GROUP_ID.matches(id) && name.trim().length in 1..32 && members.size in 1..MAX_GROUP_MEMBERS && members.all { it.trim().length in 1..32 })
        val groups = s.optJSONObject("groups") ?: JSONObject().also { s.put("groups", it) }
        check(groups.has(id) || groups.length() < MAX_GROUPS) { "Too many groups in this chat" }
        groups.put(id, JSONObject().put("name", name.trim()).put("members", JSONArray(members.map { it.trim() })))
    }
    /** Links this chat to a group (creator, announce, or a member the user matched by hand). */
    internal fun linkGroup(id: String, name: String, members: List<String>) = transaction { putGroup(it, id, name, members) }
    internal fun leaveGroup(id: String) = transaction { s -> s.optJSONObject("groups")?.remove(id); if (s.optJSONObject("groups")?.length() == 0) s.remove("groups") }
    /** The creator's first message to each member names the group and its members. */
    internal fun announceGroup(id: String, name: String, members: List<String>) {
        linkGroup(id, name, members)
        sendText("Started the group “${name.trim()}” with ${members.joinToString(", ")}", null,
            JSONObject().put("group", id).put("groupName", name.trim()).put("members", JSONArray(members)))
    }
    /** [message] is shared by every member copy, so the group thread shows one row. */
    internal fun sendGroup(text: String, id: String, message: String) {
        check(groups().any { it.id == id }) { "This chat is not in that group" }
        require(GROUP_ID.matches(message))
        sendText(text, null, JSONObject().put("group", id).put("gm", message))
    }
    /** Receive: an announce links its group; any other tag counts only for an already linked group. */
    private fun learnGroup(s: JSONObject, payload: JSONObject) {
        val id = payload.optString("group").takeIf { GROUP_ID.matches(it) } ?: return
        val members = payload.optJSONArray("members") ?: return
        val name = payload.optString("groupName")
        try { putGroup(s, id, name, (0 until members.length()).map { members.getString(it) }) } catch (_: Exception) {}
    }
    private fun groupOf(s: JSONObject, payload: JSONObject): String? =
        payload.optString("group").takeIf { it.isNotEmpty() && s.optJSONObject("groups")?.has(it) == true }
    private fun sendText(text: String, draftId: String?, extra: JSONObject? = null) {
        require(text.toByteArray(Charsets.UTF_8).size in 1..2048) { "Message must be 1–2048 UTF-8 bytes" }
        transaction { s ->
            check(hasPad(s)) { "Physically pair message keys before sending" }
            check(!s.optBoolean("renewalPaused")) { "Finish USB pad renewal on both devices first" }
            check(!s.optBoolean("relayRevoked")) { "Chat access revoked; pair a fresh chat on both devices" }
            check(s.optBoolean("cableComplete", true)) { "Cable pairing incomplete; arrange a fresh pairing on both devices" }
            check(!hasPad(s) || (s.optBoolean("padVerified") && s.optBoolean("peerReady"))) { "Open Pad: compare codes and wait for the other station to verify and sync" }
            check(!s.has("ownDevice") || s.has("peerDevice")) { "Finish physical device verification before sending" }
            prune(s)
            val payload = JSONObject().put("kind", "text").put("text", text)
            extra?.let { e -> e.keys().forEach { payload.put(it, e.get(it)) } }
            queue(s, payload, "text")
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
    /**
     * Backup relays (pipeline item 6): up to [MAX_BACKUPS] extra relays, each with its own setup
     * token, cursor, storage epoch and claim. Every check reads all reachable relays; queued
     * packets go to the primary and, only if it fails, to the first backup that accepts them.
     * Duplicates are harmless: inbox matching and the replay window already make delivery
     * idempotent. Strict-chat unlocks stay with the primary relay only (one authority).
     */
    internal fun setBackups(relays: List<Pair<String, String>>) {
        require(relays.size <= MAX_BACKUPS) { "At most $MAX_BACKUPS backup relays" }
        val endpoints = relays.map { Protocol.endpoint(it.first) }
        relays.forEach { Protocol.token(it.second) }
        transaction { next ->
            require(endpoints.distinct().size == endpoints.size && next.getString("endpoint") !in endpoints) { "Each relay may appear once" }
            val previous = items(next.optJSONArray("backups") ?: JSONArray()).associateBy { it.getString("endpoint") }
            next.put("backups", JSONArray(endpoints.zip(relays).map { (url, pair) ->
                previous[url]?.takeIf { it.getString("token") == pair.second }
                    ?: JSONObject().put("endpoint", url).put("token", Protocol.token(pair.second)).put("cursor", 0L).put("epoch", "")
            }))
            if (endpoints.isEmpty()) next.remove("backups")
        }
    }
    internal fun backupsForSetup(): JSONArray = parseBackups(JSONArray(items(state?.optJSONArray("backups") ?: JSONArray()).map {
        JSONObject().put("endpoint", it.getString("endpoint")).put("token", it.getString("token")) }))
    /** Staging: adopt the signed setup's backup list with fresh cursors. */
    private fun stageBackups(staged: JSONObject, setup: JSONObject) {
        val list = parseBackups(setup.optJSONArray("backups") ?: JSONArray())
        require((0 until list.length()).none { list.getJSONObject(it).getString("endpoint") == staged.getString("endpoint") }) { "Each relay may appear once" }
        if (list.length() == 0) { staged.remove("backups"); return }
        staged.put("backups", JSONArray((0 until list.length()).map { list.getJSONObject(it).put("cursor", 0L).put("epoch", "") }))
    }
    /**
     * Read-only connection test for the main relay and every backup, using the credential sync
     * would use. Never claims, writes, advances cursors or changes access. Network; worker only.
     */
    internal fun testConnection(): List<RelayProbe.Report> {
        val s = checkNotNull(state) { "Save server details first" }
        val master = Protocol.master(s.getString("secret"))
        try {
            val room = Protocol.room(master); val capability = Protocol.relayCapability(master)
            val targets = listOf(Triple("Main server", s.getString("endpoint"), if (s.optBoolean("relayScoped")) capability else s.getString("token"))) +
                items(s.optJSONArray("backups") ?: JSONArray()).mapIndexed { index, backup ->
                    Triple("Backup ${index + 1}", backup.getString("endpoint"), if (backup.optBoolean("scoped")) capability else backup.getString("token"))
                }
            return targets.map { (label, url, credential) ->
                RelayProbe.check(label, url) { relay.request(url, room, credential, "GET", cursor = RelayProbe.PROBE_CURSOR) }
            }
        } finally { master.fill(0) }
    }
    internal val backups: List<String> get() = items(state?.optJSONArray("backups") ?: JSONArray()).map { it.getString("endpoint") }
    fun sync(): String {
        store.requireActive()
        // Control first: a wipe request must work after pad exhaustion and relay revocation (S11).
        pollControl()?.let { return it }
        checkInStrict()?.let { return it }
        check(state?.optBoolean("renewalPaused") != true) { "USB renewal paused; reconnect both devices to recover" }
        check(state?.optBoolean("relayRevoked") != true) { "Chat access revoked; pair a fresh chat on both devices" }
        check(state?.optBoolean("cableComplete", true) != false) { "Cable pairing incomplete; arrange a fresh pairing on both devices" }
        if (state != null) transaction(change = ::prune)
        val s = checkNotNull(state) { "Save your link settings first" }
        val master = Protocol.master(s.getString("secret"))
        var ignored = 0
        try {
            val room = Protocol.room(master)
            val capability = Protocol.relayCapability(master)
            val inbound = if (s.getString("role") == "A") 'B' else 'A'
            /** Applies one page from any relay; [cursorOf] selects where that relay's cursor lives. */
            fun ingest(messages: JSONArray, cursorOf: (JSONObject) -> JSONObject, cursorKey: String, epochKey: String, epoch: String) {
                require(messages.length() <= 50) { "Relay returned an oversized page" }
                transaction { next ->
                    cursorOf(next).put(epochKey, epoch); prune(next)
                    var cursor = cursorOf(next).getLong(cursorKey)
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
                            cursor = seq; cursorOf(next).put(cursorKey, cursor); continue
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
                                if (hasPad(next)) cacheOpened(next, wire, decoded)
                                next.getJSONArray("inbox").put(received)
                        learnGroup(next, payload)
                                // Exhaustion must not prevent receiving. Last available slots can acknowledge only a finite number of messages.
                                if (!hasPad(next) || padLedger.high(next.getString("padId")) < padCapacity(next))
                                    received.put("receipt", JSONObject(queue(next, JSONObject().put("kind", "receipt").put("for", decoded.id), "receipt").toString()))
                                setWindow(next, window(next).accept(decoded.counter))
                            } else if (kind == "ready" && hasPad(next) && payload?.optInt("protocol") == 4) {
                                next.put("peerReady", true)
                                setWindow(next, window(next).accept(decoded.counter))
                            } else if (kind == "receipt" && payload?.opt("for") is String && Regex("[a-f0-9]{32}").matches(payload.getString("for"))) {
                                items(next.getJSONArray("outbox")).filter { it.getString("id") == payload.getString("for") && it.getString("kind") == "text" }
                                    .forEach { it.put("status", "delivered") }
                                setWindow(next, window(next).accept(decoded.counter))
                            } else { ignored++ }
                        }
                    } else if (packetRole(next, wire) == null) { ignored++ }
                    cursor = seq; cursorOf(next).put(cursorKey, cursor)
                }
                    prune(next)
                }
            }
            // Primary relay: unchanged rules (claims, lost-volume restore, storage-epoch recovery).
            var primaryFailure: Exception? = null
            val primaryCredential: String? = try {
                if (hasPad(s) && !s.optBoolean("relayScoped") &&
                    relay.scope(s.getString("endpoint"), room, s.getString("token"), capability)) {
                    // Persist protection before traffic. A later failure must never fall back to shared access.
                    transaction { it.put("relayScoped", true) }
                }
                val credential = if (state!!.optBoolean("relayScoped")) capability else s.getString("token")
                val response = JSONObject(try {
                    relay.request(s.getString("endpoint"), room, credential, "GET", cursor = state!!.getLong("cursor"))
                } catch (e: RelayHttpException) {
                    if (e.status != 401 || !state!!.optBoolean("relayScoped")) throw e
                    // Lost relay volume: restore this same claim, never read/write with the setup token.
                    check(relay.scope(s.getString("endpoint"), room, s.getString("token"), credential)) { "Update the relay; protected chat access cannot be downgraded" }
                    relay.request(s.getString("endpoint"), room, credential, "GET", cursor = state!!.getLong("cursor"))
                })
                if (state!!.optBoolean("relayScoped")) check(response.optString("access") == "chat") { "Relay chat protection unavailable; access was not downgraded" }
                check(response.optInt("protocol") == 2) { "Update the relay container before using this APK" }
                val epoch = response.getString("epoch")
                if (state!!.getString("epoch").isNotEmpty() && state!!.getString("epoch") != epoch) {
                    var recovered = 0
                    transaction { next -> next.put("epoch", epoch).put("cursor", 0L); recovered = retryUndelivered(next); retrySavedReceipts(next) }
                    return "RELAY STORAGE CHANGED // $recovered saved messages queued for recovery"
                }
                ingest(response.getJSONArray("messages"), { it }, "cursor", "epoch", epoch)
                credential
            } catch (e: RelayHttpException) {
                if (e.status == 410) throw e // Revocation is final; never route around it.
                primaryFailure = e; null
            } catch (e: java.io.IOException) { primaryFailure = e; null }

            // Backups: read every reachable one. A storage-epoch change only restarts that relay's cursor.
            val reachable = mutableListOf<Pair<String, String>>()
            for ((index, backup) in items(state!!.optJSONArray("backups") ?: JSONArray()).withIndex()) try {
                val url = backup.getString("endpoint")
                fun entry(next: JSONObject) = next.getJSONArray("backups").getJSONObject(index).also { check(it.getString("endpoint") == url) }
                if (hasPad(s) && !backup.optBoolean("scoped") && relay.scope(url, room, backup.getString("token"), capability))
                    transaction { entry(it).put("scoped", true) }
                val credential = if (entry(state!!).optBoolean("scoped")) capability else backup.getString("token")
                val response = JSONObject(relay.request(url, room, credential, "GET", cursor = entry(state!!).getLong("cursor")))
                if (entry(state!!).optBoolean("scoped")) check(response.optString("access") == "chat") { "Backup relay protection unavailable" }
                check(response.optInt("protocol") == 2) { "Update the backup relay container" }
                val epoch = response.getString("epoch")
                if (entry(state!!).getString("epoch").let { it.isNotEmpty() && it != epoch }) {
                    transaction { entry(it).put("epoch", epoch).put("cursor", 0L) }
                } else ingest(response.getJSONArray("messages"), ::entry, "cursor", "epoch", epoch)
                reachable.add(url to credential)
            } catch (e: RelayHttpException) { if (e.status == 410) throw e } catch (_: java.io.IOException) {}
            if (primaryCredential == null && reachable.isEmpty()) throw checkNotNull(primaryFailure)

            // Retry the exact durable ciphertext; a timeout never creates a new message ID/counter.
            val pending = items(checkNotNull(state).getJSONArray("outbox")).filter { it.getString("status") == "queued" }.take(10)
            val routes = (listOfNotNull(primaryCredential?.let { s.getString("endpoint") to it }) + reachable)
            for (entry in pending) {
                store.requireActive()
                var sent = false; var failure: Exception? = null
                for ((url, credential) in routes) {
                    try { relay.request(url, room, credential, "POST", RelayClient.packetBody(entry.getString("packet"))); sent = true; break }
                    catch (e: RelayHttpException) { if (e.status == 410) throw e; failure = e } catch (e: java.io.IOException) { failure = e }
                }
                // No relay took it: report the failure exactly as before backups existed. The packet stays queued.
                if (!sent) throw checkNotNull(failure)
                transaction { next ->
                    items(next.getJSONArray("outbox")).firstOrNull { it.getString("id") == entry.getString("id") }?.put("status", "relayed")
                    prune(next)
                }
            }
            val via = if (primaryCredential == null) " · via backup relay" else ""
            return "Connected$via" + if (ignored > 0) " · $ignored invalid/legacy packets skipped" else ""
        } catch (e: RelayHttpException) {
            if (e.status == 410 && state?.let(::hasPad) == true) transaction { it.put("relayScoped", true).put("relayRevoked", true) }
            throw e
        } finally { master.fill(0) }
    }
    /** [group] is set only for a group this chat is linked to; otherwise a group tag is shown as a normal message. */
    data class Message(val incoming: Boolean, val text: String, val status: String, val time: Long, val unread: Boolean = false, val deviceVerified: Boolean = false, val group: String? = null, val groupMessage: String? = null)
    data class Group(val id: String, val name: String, val members: List<String>)
    data class View(val role: String, val fingerprint: String, val messages: List<Message>, val pending: Int, val unread: Int = 0, val padRemaining: Int? = null, val padCheck: String? = null, val padVerified: Boolean = false, val peerReady: Boolean = false, val ownDevice: String? = null, val peerDevice: String? = null, val keyProtection: String? = null, val pairingComplete: Boolean = true, val cableEligible: Boolean = true, val awaitingReceipt: Int = 0, val relayScoped: Boolean = false, val relayRevoked: Boolean = false) {
        val setupComplete: Boolean get() = pairingComplete && padCheck != null && padVerified && peerReady && peerDevice != null
        val canSend: Boolean get() = !relayRevoked && pairingComplete && role != "—" && padRemaining != null && padRemaining > 0 && padCheck != null && padVerified && peerReady && (ownDevice == null || peerDevice != null)
    }
    private fun padCapacity(s: JSONObject): Int {
        if (s.has("padKeys")) { val keys = b64d.decode(s.getString("padKeys")); return try { PadMaterial.slots(keys) } finally { keys.fill(0) } }
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
                val payload = JSONObject(deviceBody(s, decoded, peer, enforce = false))
                rows.add(Message(true, payload.getString("text"), "received", entry.getLong("time"), !entry.optBoolean("read", false), IdentityProtocol.isSigned(decoded.payload), groupOf(s, payload), payload.optString("gm").takeIf { GROUP_ID.matches(it) }))
            }
            for (entry in items(s.getJSONArray("outbox")).filter { it.getString("kind") == "text" }) {
                if (System.currentTimeMillis() - entry.getLong("time") >= 86400000) continue
                val decoded = retained(s, master, entry.getString("packet"), own)
                val payload = JSONObject(deviceBody(s, decoded, own, enforce = false))
                rows.add(Message(false, payload.getString("text"), entry.getString("status"), entry.getLong("time"), deviceVerified = IdentityProtocol.isSigned(decoded.payload), group = groupOf(s, payload), groupMessage = payload.optString("gm").takeIf { GROUP_ID.matches(it) }))
            }
            cacheExpiry = rows.minOfOrNull { it.time + 86400000L } ?: Long.MAX_VALUE
            View(own.toString(), Protocol.fingerprint(master), rows.sortedBy { it.time }.takeLast(100),
                items(s.getJSONArray("outbox")).count { it.getString("status") == "queued" },
                items(s.getJSONArray("inbox")).count { !it.optBoolean("read", false) } + items(s.optJSONArray("history") ?: JSONArray()).count { it.optBoolean("unread") },
                if (hasPad(s)) padCapacity(s) - padLedger.high(s.getString("padId")).toInt() else null,
                s.optString("padId").takeIf { it.isNotEmpty() }, s.optBoolean("padVerified"), s.optBoolean("peerReady"),
                s.optString("ownDevice").takeIf { it.isNotEmpty() }, s.optString("peerDevice").takeIf { it.isNotEmpty() },
                if (s.has("ownDevice")) identity.protection() else null, s.optBoolean("cableComplete", true) && !s.optBoolean("renewalPaused"),
                !hasPad(s) && s.getLong("counter") == 0L && s.getLong("high") == 0L && s.getJSONArray("outbox").length() == 0 && s.getJSONArray("inbox").length() == 0,
                rows.count { !it.incoming && it.status == "relayed" }, s.optBoolean("relayScoped"), s.optBoolean("relayRevoked")).also { cachedView = it }
        } finally { master.fill(0) }
    }
}
