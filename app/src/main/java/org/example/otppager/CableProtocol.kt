package org.example.otppager

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Physical cable transport; identity is authenticated by comparing the displayed transcript code. */
object CableProtocol {
    class UpgradeRequired : IllegalArgumentException("Upgrade both devices to the same latest version of OTP Pager for this pairing")
    /**
     * v5: the pad is streamed after setup as authenticated chunks, straight into pad storage.
     * v6: both phones also exchange peer-wipe control keys (docs/PEER_WIPE_PROTOCOL.md §1).
     * v7: both phones state the strict-chat mode; it must match and is bound into the code.
     * v8: the phone that supplies the server also supplies its backup relays (signed setup).
     */
    const val VERSION = 8
    private const val CHUNK = 32768
    private const val CHUNK_TAG: Byte = 0x50
    private const val LIMIT = 4096 // Public hello/approval frames only; encrypted material has a separate bound.
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private val b64 = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private fun send(output: OutputStream, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..LIMIT)
        try { DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() } } finally { bytes.fill(0) }
    }
    private fun read(input: InputStream): JSONObject {
        val source = DataInputStream(input); val size = source.readInt(); require(size in 1..LIMIT) { "Invalid cable frame" }
        val bytes = ByteArray(size); source.readFully(bytes)
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
    }
    private fun hello(engine: StationEngine, ephemeral: java.security.KeyPair, renew: Boolean, size: Int, strict: Long): JSONObject = JSONObject().put("kind", "hello").put("version", VERSION)
        .put("purpose", if (renew) "renew" else "pair").put("padSize", size).put("strict", if (renew) 0L else strict)
        .put("ephemeral", b64.encodeToString(ephemeral.public.encoded)).put("key", engine.cableIdentity(renew)).put("nonce", b64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        .also { h -> engine.state?.let {
            h.put("endpoint", it.getString("endpoint"))
            if (renew) {
                val master = Protocol.master(it.getString("secret"))
                try { h.put("room", Protocol.room(master)) } finally { master.fill(0) }
                h.put("role", it.getString("role")).put("padId", it.getString("padId"))
                    .put("paused", it.optBoolean("renewalPaused") || !it.optBoolean("cableComplete", true))
            }
        } }
    private fun checkHello(h: JSONObject) {
        require(h.getString("kind") == "hello")
        if (h.getInt("version") != VERSION) throw UpgradeRequired()
        require(h.getString("purpose") in listOf("pair", "renew") && PadProtocol.validSize(h.getInt("padSize")))
        IdentityProtocol.publicKey(h.getString("ephemeral")); IdentityProtocol.publicKey(h.getString("key")); require(decoder.decode(h.getString("nonce")).size == 32)
        if (h.has("endpoint")) { Protocol.endpoint(h.getString("endpoint")) }
    }
    private fun identityDigest(a: JSONObject, b: JSONObject, endpoint: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            fun part(bytes: ByteArray) { stream.writeInt(bytes.size); stream.write(bytes) }
            part("Field cable identity comparison v4".toByteArray(Charsets.US_ASCII))
            for (h in listOf(a, b)) { part(IdentityProtocol.publicKey(h.getString("key")).encoded); part(decoder.decode(h.getString("nonce"))); part(IdentityProtocol.publicKey(h.getString("ephemeral")).encoded) }
            part(endpoint.toByteArray(Charsets.UTF_8))
            for (h in listOf(a, b)) for (name in listOf("purpose", "padSize", "strict", "room", "role", "padId", "paused")) part(h.optString(name).toByteArray(Charsets.UTF_8))
        }
        return hash(out.toByteArray())
    }
    /** Binds both identities, the setup and the SHA-256 of the streamed pad. */
    private fun digest(a: JSONObject, b: JSONObject, setup: JSONObject, pad: ByteArray): ByteArray {
        require(pad.size == 32)
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            fun part(bytes: ByteArray) { stream.writeInt(bytes.size); stream.write(bytes) }
            part("Field physical cable material v5".toByteArray(Charsets.US_ASCII))
            for (h in listOf(a, b)) { part(IdentityProtocol.publicKey(h.getString("key")).encoded); part(decoder.decode(h.getString("nonce"))); part(IdentityProtocol.publicKey(h.getString("ephemeral")).encoded) }
            for (name in listOf("endpoint", "token", "secret")) part(setup.getString(name).toByteArray(Charsets.UTF_8))
            part((setup.optJSONArray("backups") ?: org.json.JSONArray()).toString().toByteArray(Charsets.UTF_8))
            part(setup.getInt("padSize").toString().toByteArray(Charsets.US_ASCII)); part(pad)
        }
        return hash(out.toByteArray())
    }
    /**
     * Host: generates the pad in chunks (random slots, zero tails past the last whole slot), writes
     * each chunk to its own pad storage and sends it. The whole pad is never in memory.
     */
    private fun sendPad(channel: CableSession, output: OutputStream, engine: StationEngine, size: Int, progress: (String) -> Unit): PadMaterial.Installed {
        val random = SecureRandom(); val half = size / 2; val used = PadProtocol.slotsFor(size) * PadProtocol.STRIDE
        val frame = ByteArray(1 + CHUNK)
        engine.padWriter(size).use { writer ->
            var at = 0; var shown = -1
            try {
                while (at < size) {
                    val n = minOf(CHUNK, size - at)
                    random.nextBytes(frame)
                    frame[0] = CHUNK_TAG
                    for (i in 0 until n) if ((at + i) % half >= used) frame[1 + i] = 0
                    writer.write(frame, 1, n)
                    channel.send(output, if (n == CHUNK) frame else frame.copyOf(1 + n))
                    at += n
                    (at * 10L / size).toInt().let { if (it != shown) { shown = it; progress("Sending message keys · ${it * 10}%") } }
                }
                return writer.finish()
            } finally { frame.fill(0) }
        }
    }
    private fun receivePad(channel: CableSession, input: InputStream, engine: StationEngine, size: Int, progress: (String) -> Unit): PadMaterial.Installed =
        engine.padWriter(size).use { writer ->
            var at = 0; var shown = -1
            while (at < size) {
                val frame = channel.read(input)
                try {
                    require(frame.size in 2..(1 + CHUNK) && frame[0] == CHUNK_TAG && at + frame.size - 1 <= size) { "Unexpected pad frame" }
                    writer.write(frame, 1, frame.size - 1); at += frame.size - 1
                } finally { frame.fill(0) }
                (at * 10L / size).toInt().let { if (it != shown) { shown = it; progress("Receiving message keys · ${it * 10}%") } }
            }
            writer.finish()
        }
    private fun proof(engine: StationEngine, digest: ByteArray, label: String): String = b64.encodeToString(engine.signCable(digest + label.toByteArray(Charsets.US_ASCII)))
    private fun verify(key: String, digest: ByteArray, label: String, proof: String) {
        require(proof.length <= 108 && IdentityProtocol.verify(key, digest + label.toByteArray(Charsets.US_ASCII), decoder.decode(proof))) { "Cable identity proof rejected" }
    }
    fun run(host: Boolean, input: InputStream, output: OutputStream, engine: StationEngine,
            review: (String, String) -> Unit, progress: (String) -> Unit) =
        run(host, input, output, engine, review, progress, false, PadProtocol.MATERIAL_BYTES)
    fun run(host: Boolean, input: InputStream, output: OutputStream, engine: StationEngine,
            review: (String, String) -> Unit, progress: (String) -> Unit, renew: Boolean, size: Int) =
        run(host, input, output, engine, review, progress, renew, size, 0L)
    /** review blocks only this transfer worker; closing its channel cancels the wizard. */
    fun run(host: Boolean, input: InputStream, output: OutputStream, engine: StationEngine,
            review: (String, String) -> Unit, progress: (String) -> Unit, renew: Boolean, size: Int, strict: Long) {
        if (renew) engine.requireRenewalIdentity() else engine.requireCableEligible()
        require(strict == 0L || (!renew && strict in StrictChat.DEADLINES)) { "Unknown strict-chat mode" }
        val ephemeral = CableSession.ephemeral()
        val own = hello(engine, ephemeral, renew, size, strict)
        val peer: JSONObject
        if (host) { send(output, own); peer = read(input); checkHello(peer) }
        else { peer = read(input); checkHello(peer); send(output, own) }
        require(own.getString("key") != peer.getString("key"))
        require(peer.getString("purpose") == own.getString("purpose")) { "Choose the same USB operation on both devices" }
        require(peer.optLong("strict", -1) == own.getLong("strict")) { "Choose the same chat type (normal or strict, same check-in time) on both devices" }
        if (renew) {
            val state = checkNotNull(engine.state)
            check(peer.getString("key") == state.getString("peerDevice")) { "This is not the pinned friend's device" }
            check(peer.getString("room") == own.getString("room") && peer.getString("role") != own.getString("role") && peer.getString("role") in listOf("A", "B")) { "Choose the matching chat on both devices" }
        }
        val recover = renew && own.getString("padId") == peer.getString("padId") && (own.optBoolean("paused") || peer.optBoolean("paused"))
        val a = if (host) own else peer; val b = if (host) peer else own
        val server = if (a.has("endpoint")) a else b
        check(server.has("endpoint")) { "Enter and save relay settings on either device first" }
        val endpoint = server.getString("endpoint")
        val identityDigest = identityDigest(a, b, endpoint)
        val code = PadProtocol.hex(identityDigest.copyOf(16)).chunked(4).joinToString(" ")
        // No relay token, pairing secret or pad is sent until BOTH people approve.
        review(code, "$endpoint\nPad: ${a.getInt("padSize") / PadProtocol.MATERIAL_BYTES} MiB · ${if (recover) "recover current pad" else if (renew) "renew existing friend" else "new pairing"}" +
            if (own.getLong("strict") > 0) " · ${StrictChat.label(own.getLong("strict"))}" else "")
        progress("Waiting for confirmation on the other device…")
        val peerKey = peer.getString("key")
        if (host) {
            send(output, JSONObject().put("kind", "accepted").put("proof", proof(engine, identityDigest, "accept-A")))
            val accepted = read(input); require(accepted.getString("kind") == "accepted")
            verify(peerKey, identityDigest, "accept-B", accepted.getString("proof"))
        } else {
            val accepted = read(input); require(accepted.getString("kind") == "accepted")
            verify(peerKey, identityDigest, "accept-A", accepted.getString("proof"))
            send(output, JSONObject().put("kind", "accepted").put("proof", proof(engine, identityDigest, "accept-B")))
        }
        CableSession.establish(host, ephemeral, peer.getString("ephemeral"), identityDigest).use { channel ->
            fun secureSend(value: JSONObject) {
                val bytes = value.toString().toByteArray(Charsets.UTF_8)
                try { channel.send(output, bytes) } finally { bytes.fill(0) }
            }
            fun secureRead(): JSONObject {
                val bytes = channel.read(input)
                return try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
            }
            if (recover) {
                val recovery = JSONObject().put("kind", "recover").put("proof", proof(engine, identityDigest, if (host) "recover-A" else "recover-B"))
                val received = if (host) { secureSend(recovery); secureRead() } else secureRead().also { secureSend(recovery) }
                require(received.getString("kind") == "recover")
                verify(peerKey, identityDigest, if (host) "recover-B" else "recover-A", received.getString("proof"))
                engine.finishCable()
                progress("Connection recovered · current pad and messages retained")
                return
            }
            if (renew) {
                progress("Confirming outstanding messages over encrypted USB…")
                val sent = engine.renewalTraffic()
                if (host) {
                    secureSend(JSONObject().put("kind", "traffic").put("packets", sent))
                    val reply = secureRead(); require(reply.getString("kind") == "traffic-reply")
                    engine.acknowledgeRenewalTraffic(reply.getJSONArray("ack"), sent)
                    val ack = engine.receiveRenewalTraffic(reply.getJSONArray("packets"))
                    secureSend(JSONObject().put("kind", "traffic-done").put("ack", ack))
                } else {
                    val incoming = secureRead(); require(incoming.getString("kind") == "traffic")
                    val ack = engine.receiveRenewalTraffic(incoming.getJSONArray("packets"))
                    secureSend(JSONObject().put("kind", "traffic-reply").put("ack", ack).put("packets", sent))
                    val done = secureRead(); require(done.getString("kind") == "traffic-done")
                    engine.acknowledgeRenewalTraffic(done.getJSONArray("ack"), sent)
                }
                engine.beginRenewal()
            }
            if (!host && !a.has("endpoint")) {
                val token = checkNotNull(engine.state).getString("token")
                val relays = engine.backupsForSetup()
                secureSend(JSONObject().put("kind", "server").put("token", token).put("backups", relays)
                    .put("proof", proof(engine, hash(identityDigest + (token + relays.toString()).toByteArray(Charsets.UTF_8)), "server-B")))
            }
            val setup: JSONObject
            val digest: ByteArray
            progress("Both confirmed · exchanging fresh pairing material…")
            if (host) {
                var relays = engine.backupsForSetup()
                val token = if (a.has("endpoint")) checkNotNull(engine.state).getString("token") else {
                    val credentials = secureRead(); require(credentials.getString("kind") == "server")
                    relays = StationEngine.parseBackups(credentials.optJSONArray("backups") ?: org.json.JSONArray())
                    Protocol.token(credentials.getString("token")).also { verify(peerKey, hash(identityDigest + (it + relays.toString()).toByteArray(Charsets.UTF_8)), "server-B", credentials.getString("proof")) }
                }
                val secret = if (renew) engine.state!!.getString("secret") else Protocol.randomSecret()
                val size = a.getInt("padSize")
                setup = JSONObject().put("endpoint", endpoint).put("token", token).put("secret", secret).put("padSize", size).put("backups", relays)
                secureSend(JSONObject().put("kind", "pad").put("padSize", size))
                val pad = sendPad(channel, output, engine, size, progress)
                digest = digest(a, b, setup, pad.digest)
                val padDigest = PadProtocol.hex(pad.digest)
                if (renew) engine.stageRenewal(setup, peerKey, pad) else engine.stageCable(setup, "A", peerKey, pad)
                secureSend(JSONObject().put("kind", "setup").put("setup", setup).put("padDigest", padDigest).put("proof", proof(engine, digest, "setup-A")))
                val control = engine.controlOffer(host = true); secureSend(control)
                engine.stageControl(control, secureRead(), host = true)
                engine.stageStrict(own.getLong("strict"))
            } else {
                val announced = secureRead(); require(announced.getString("kind") == "pad" && announced.getInt("padSize") == a.getInt("padSize"))
                val pad = receivePad(channel, input, engine, a.getInt("padSize"), progress)
                try {
                    val packet = secureRead(); require(packet.getString("kind") == "setup")
                    setup = packet.getJSONObject("setup"); require(setup.getString("endpoint") == endpoint && setup.getInt("padSize") == a.getInt("padSize"))
                    require(packet.getString("padDigest") == PadProtocol.hex(pad.digest)) { "Message keys were altered in transfer" }
                    digest = digest(a, b, setup, pad.digest)
                    verify(peerKey, digest, "setup-A", packet.getString("proof"))
                } catch (failure: Exception) { pad.keys.fill(0); pad.file.delete(); throw failure }
                if (renew) engine.stageRenewal(setup, peerKey, pad) else engine.stageCable(setup, "B", peerKey, pad)
                val offered = secureRead()
                val control = engine.controlOffer(host = false); secureSend(control)
                engine.stageControl(control, offered, host = false)
                engine.stageStrict(own.getLong("strict"))
            }
            if (host) {
                val accepted = secureRead(); require(accepted.getString("kind") == "saved")
                verify(peerKey, digest, "saved-B", accepted.getString("proof"))
                engine.commitCable(complete = false)
                secureSend(JSONObject().put("kind", "complete").put("proof", proof(engine, digest, "complete-A")))
                engine.finishCable()
            } else {
                engine.commitCable(complete = false)
                secureSend(JSONObject().put("kind", "saved").put("proof", proof(engine, digest, "saved-B")))
                val done = secureRead(); require(done.getString("kind") == "complete")
                verify(peerKey, digest, "complete-A", done.getString("proof"))
                engine.finishCable()
            }
        }
        progress(if (renew) "Pad renewed · friend identity and message history retained" else "Paired · encrypted transfer and device identity verified")
    }
}
