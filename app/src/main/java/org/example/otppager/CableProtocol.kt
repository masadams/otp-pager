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
     * v9: both phones contribute randomness to the pad (see [mixer]).
     * v10: post-quantum hybrid channel. Each phone also sends an ML-KEM-768 key, each
     *      encapsulates to the other, and the session key needs ECDH and both ML-KEM secrets.
     * v11: the pad is the XOR of both phones' full-length, health-tested random streams (see
     *      [mixPad]), optionally seeded with microphone noise; messages use one-time Poly1305.
     */
    const val VERSION = 11
    private const val CHUNK = 32768
    private const val CHUNK_TAG: Byte = 0x50
    private const val CONTRIBUTION_TAG: Byte = 0x51
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
    private fun hello(engine: StationEngine, ephemeral: java.security.KeyPair, kem: PostQuantum.KemKeyPair, renew: Boolean, size: Int, strict: Long): JSONObject = JSONObject().put("kind", "hello").put("version", VERSION)
        .put("purpose", if (renew) "renew" else "pair").put("padSize", size).put("strict", if (renew) 0L else strict)
        .put("ephemeral", b64.encodeToString(ephemeral.public.encoded)).put("kem", b64.encodeToString(kem.public)).put("key", engine.cableIdentity(renew)).put("nonce", b64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
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
        PostQuantum.checkPublic(decoder.decode(h.getString("kem")))
        if (h.has("endpoint")) { Protocol.endpoint(h.getString("endpoint")) }
    }
    /** The compared code covers both identities, ECDH keys, ML-KEM keys and both ML-KEM ciphertexts. */
    private fun identityDigest(a: JSONObject, b: JSONObject, endpoint: String, ciphertextA: ByteArray, ciphertextB: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            fun part(bytes: ByteArray) { stream.writeInt(bytes.size); stream.write(bytes) }
            part("Field cable identity comparison v5 hybrid".toByteArray(Charsets.US_ASCII))
            for (h in listOf(a, b)) { part(IdentityProtocol.publicKey(h.getString("key")).encoded); part(decoder.decode(h.getString("nonce"))); part(IdentityProtocol.publicKey(h.getString("ephemeral")).encoded); part(decoder.decode(h.getString("kem"))) }
            part(ciphertextA); part(ciphertextB)
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
     * v11 pad generation, lockstep per 32 KiB chunk so neither phone ever holds the whole pad:
     *  1. the peer (B) draws a chunk from its own generator, health-checks it and sends it;
     *  2. the host (A) draws its own chunk, health-checks it, XORs the two, zeroes the tail past
     *     the last whole slot, stores the result as pad and sends it back;
     *  3. B checks that A really contributed (the pad differs from what B sent) and stores it.
     * The pad is therefore at least as unpredictable as the better generator. Both streams cross
     * only the post-quantum encrypted channel.
     */
    private fun mixPad(channel: CableSession, input: InputStream, output: OutputStream, engine: StationEngine, size: Int, progress: (String) -> Unit, random: java.security.SecureRandom): PadMaterial.Installed {
        val half = size / 2; val used = PadProtocol.slotsFor(size) * PadProtocol.STRIDE
        val monitor = PadEntropy.OutputMonitor()
        val own = ByteArray(CHUNK); val frame = ByteArray(1 + CHUNK)
        engine.padWriter(size).use { writer ->
            var at = 0; var shown = -1
            try {
                while (at < size) {
                    val n = minOf(CHUNK, size - at)
                    val peer = channel.read(input)
                    try {
                        require(peer.size == 1 + n && peer[0] == CONTRIBUTION_TAG) { "Unexpected key contribution" }
                        random.nextBytes(own); monitor.check(own, 0, n)
                        frame[0] = CHUNK_TAG
                        for (i in 0 until n) frame[1 + i] = if ((at + i) % half >= used) 0 else (own[i].toInt() xor peer[1 + i].toInt()).toByte()
                    } finally { peer.fill(0) }
                    writer.write(frame, 1, n)
                    channel.send(output, if (n == CHUNK) frame else frame.copyOf(1 + n))
                    at += n
                    (at * 10L / size).toInt().let { if (it != shown) { shown = it; progress("Mixing message keys from both phones · ${it * 10}%") } }
                }
                return writer.finish()
            } finally { own.fill(0); frame.fill(0) }
        }
    }
    private fun contributePad(channel: CableSession, input: InputStream, output: OutputStream, engine: StationEngine, size: Int, progress: (String) -> Unit, random: java.security.SecureRandom): PadMaterial.Installed {
        val half = size / 2; val used = PadProtocol.slotsFor(size) * PadProtocol.STRIDE
        val monitor = PadEntropy.OutputMonitor()
        val contribution = ByteArray(1 + CHUNK)
        engine.padWriter(size).use { writer ->
            var at = 0; var shown = -1
            try {
                while (at < size) {
                    val n = minOf(CHUNK, size - at)
                    random.nextBytes(contribution); contribution[0] = CONTRIBUTION_TAG; monitor.check(contribution, 1, 1 + n)
                    channel.send(output, if (n == CHUNK) contribution else contribution.copyOf(1 + n))
                    val frame = channel.read(input)
                    try {
                        require(frame.size == 1 + n && frame[0] == CHUNK_TAG) { "Unexpected pad frame" }
                        // The host must have mixed in its own randomness: a used region equal to ours means it didn't.
                        var differs = false; var checked = 0
                        for (i in 0 until n) if ((at + i) % half < used) { checked++; if (frame[1 + i] != contribution[1 + i]) differs = true }
                        require(checked == 0 || differs) { "The other phone didn't contribute randomness" }
                        writer.write(frame, 1, n)
                    } finally { frame.fill(0) }
                    at += n
                    (at * 10L / size).toInt().let { if (it != shown) { shown = it; progress("Mixing message keys from both phones · ${it * 10}%") } }
                }
                return writer.finish()
            } finally { contribution.fill(0) }
        }
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
    /** [noise]: an optional 64-byte conditioned hardware-noise seed for this phone's generator ([MicNoise]). */
    fun run(host: Boolean, input: InputStream, output: OutputStream, engine: StationEngine,
            review: (String, String) -> Unit, progress: (String) -> Unit, renew: Boolean, size: Int, strict: Long, noise: ByteArray? = null) {
        if (renew) engine.requireRenewalIdentity() else engine.requireCableEligible()
        require(strict == 0L || (!renew && strict in StrictChat.DEADLINES)) { "Unknown strict-chat mode" }
        val ephemeral = CableSession.ephemeral()
        val kem = PostQuantum.kemKeyPair()
        val own = hello(engine, ephemeral, kem, renew, size, strict)
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
        // Each phone encapsulates to the other's ML-KEM key. Either phone's randomness alone keeps
        // the post-quantum secret safe; both ciphertexts go into the compared code.
        val mine = PostQuantum.encapsulate(decoder.decode(peer.getString("kem")))
        val outgoing = JSONObject().put("kind", "kem").put("ciphertext", b64.encodeToString(mine.ciphertext))
        val incoming = if (host) { send(output, outgoing); read(input) } else read(input).also { send(output, outgoing) }
        require(incoming.getString("kind") == "kem")
        val theirCiphertext = decoder.decode(incoming.getString("ciphertext"))
        val theirs = PostQuantum.decapsulate(kem, theirCiphertext)
        val postQuantum = if (host) mine.secret + theirs else theirs + mine.secret
        mine.secret.fill(0); theirs.fill(0)
        val identityDigest = try {
            if (host) identityDigest(a, b, endpoint, mine.ciphertext, theirCiphertext) else identityDigest(a, b, endpoint, theirCiphertext, mine.ciphertext)
        } catch (failure: Exception) { postQuantum.fill(0); throw failure }
        val code = PadProtocol.hex(identityDigest.copyOf(16)).chunked(4).joinToString(" ")
        val peerKey = peer.getString("key")
        // No relay token, pairing secret or pad is sent until BOTH people approve.
        val session = try {
            review(code, "$endpoint\nPad: ${a.getInt("padSize") / PadProtocol.MATERIAL_BYTES} MiB · ${if (recover) "recover current pad" else if (renew) "renew existing friend" else "new pairing"}" +
                if (own.getLong("strict") > 0) " · ${StrictChat.label(own.getLong("strict"))}" else "")
            progress("Waiting for confirmation on the other device…")
            if (host) {
                send(output, JSONObject().put("kind", "accepted").put("proof", proof(engine, identityDigest, "accept-A")))
                val accepted = read(input); require(accepted.getString("kind") == "accepted")
                verify(peerKey, identityDigest, "accept-B", accepted.getString("proof"))
            } else {
                val accepted = read(input); require(accepted.getString("kind") == "accepted")
                verify(peerKey, identityDigest, "accept-A", accepted.getString("proof"))
                send(output, JSONObject().put("kind", "accepted").put("proof", proof(engine, identityDigest, "accept-B")))
            }
            CableSession.establish(host, ephemeral, peer.getString("ephemeral"), identityDigest, postQuantum)
        } finally { postQuantum.fill(0) }
        session.use { channel ->
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
                val pad = mixPad(channel, input, output, engine, size, progress, PadEntropy.generator(noise).also { noise?.fill(0) })
                digest = digest(a, b, setup, pad.digest)
                val padDigest = PadProtocol.hex(pad.digest)
                if (renew) engine.stageRenewal(setup, peerKey, pad) else engine.stageCable(setup, "A", peerKey, pad)
                engine.stageOneTimeMac()
                secureSend(JSONObject().put("kind", "setup").put("setup", setup).put("padDigest", padDigest).put("proof", proof(engine, digest, "setup-A")))
                val control = engine.controlOffer(host = true); secureSend(control)
                engine.stageControl(control, secureRead(), host = true)
                engine.stageStrict(own.getLong("strict"))
            } else {
                val announced = secureRead(); require(announced.getString("kind") == "pad" && announced.getInt("padSize") == a.getInt("padSize"))
                val pad = contributePad(channel, input, output, engine, a.getInt("padSize"), progress, PadEntropy.generator(noise).also { noise?.fill(0) })
                try {
                    val packet = secureRead(); require(packet.getString("kind") == "setup")
                    setup = packet.getJSONObject("setup"); require(setup.getString("endpoint") == endpoint && setup.getInt("padSize") == a.getInt("padSize"))
                    require(packet.getString("padDigest") == PadProtocol.hex(pad.digest)) { "Message keys were altered in transfer" }
                    digest = digest(a, b, setup, pad.digest)
                    verify(peerKey, digest, "setup-A", packet.getString("proof"))
                } catch (failure: Exception) { pad.keys.fill(0); pad.file.delete(); throw failure }
                if (renew) engine.stageRenewal(setup, peerKey, pad) else engine.stageCable(setup, "B", peerKey, pad)
                engine.stageOneTimeMac()
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
