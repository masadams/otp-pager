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
    private fun hello(engine: StationEngine, ephemeral: java.security.KeyPair): JSONObject = JSONObject().put("kind", "hello").put("version", 3)
        .put("ephemeral", b64.encodeToString(ephemeral.public.encoded)).put("key", engine.cableIdentity()).put("nonce", b64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        .also { h -> engine.state?.let { h.put("endpoint", it.getString("endpoint")) } }
    private fun checkHello(h: JSONObject) {
        require(h.getString("kind") == "hello")
        require(h.getInt("version") == 3) { "Upgrade both devices to 0.8 or later for encrypted USB pairing" }
        IdentityProtocol.publicKey(h.getString("ephemeral")); IdentityProtocol.publicKey(h.getString("key")); require(decoder.decode(h.getString("nonce")).size == 32)
        if (h.has("endpoint")) { Protocol.endpoint(h.getString("endpoint")) }
    }
    private fun identityDigest(a: JSONObject, b: JSONObject, endpoint: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            fun part(bytes: ByteArray) { stream.writeInt(bytes.size); stream.write(bytes) }
            part("Field cable identity comparison v3".toByteArray(Charsets.US_ASCII))
            for (h in listOf(a, b)) { part(IdentityProtocol.publicKey(h.getString("key")).encoded); part(decoder.decode(h.getString("nonce"))); part(IdentityProtocol.publicKey(h.getString("ephemeral")).encoded) }
            part(endpoint.toByteArray(Charsets.UTF_8))
        }
        return hash(out.toByteArray())
    }
    private fun digest(a: JSONObject, b: JSONObject, setup: JSONObject): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            fun part(bytes: ByteArray) { stream.writeInt(bytes.size); stream.write(bytes) }
            part("Field physical cable material v3".toByteArray(Charsets.US_ASCII))
            for (h in listOf(a, b)) { part(IdentityProtocol.publicKey(h.getString("key")).encoded); part(decoder.decode(h.getString("nonce"))); part(IdentityProtocol.publicKey(h.getString("ephemeral")).encoded) }
            for (name in listOf("endpoint", "token", "secret", "pad")) part(setup.getString(name).toByteArray(Charsets.UTF_8))
        }
        return hash(out.toByteArray())
    }
    private fun proof(engine: StationEngine, digest: ByteArray, label: String): String = b64.encodeToString(engine.signCable(digest + label.toByteArray(Charsets.US_ASCII)))
    private fun verify(key: String, digest: ByteArray, label: String, proof: String) {
        require(proof.length <= 108 && IdentityProtocol.verify(key, digest + label.toByteArray(Charsets.US_ASCII), decoder.decode(proof))) { "Cable identity proof rejected" }
    }
    /** review blocks only this transfer worker; closing its channel cancels the wizard. */
    fun run(host: Boolean, input: InputStream, output: OutputStream, engine: StationEngine,
            review: (String, String) -> Unit, progress: (String) -> Unit) {
        engine.requireCableEligible()
        val ephemeral = CableSession.ephemeral()
        val own = hello(engine, ephemeral)
        val peer: JSONObject
        if (host) { send(output, own); peer = read(input); checkHello(peer) }
        else { peer = read(input); checkHello(peer); send(output, own) }
        require(own.getString("key") != peer.getString("key"))
        val a = if (host) own else peer; val b = if (host) peer else own
        val server = if (a.has("endpoint")) a else b
        check(server.has("endpoint")) { "Enter and save relay settings on either device first" }
        val endpoint = server.getString("endpoint")
        val identityDigest = identityDigest(a, b, endpoint)
        val code = PadProtocol.hex(identityDigest.copyOf(16)).chunked(4).joinToString(" ")
        // No relay token, pairing secret or pad is sent until BOTH people approve.
        review(code, endpoint)
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
            if (!host && !a.has("endpoint")) {
                val token = checkNotNull(engine.state).getString("token")
                secureSend(JSONObject().put("kind", "server").put("token", token)
                    .put("proof", proof(engine, hash(identityDigest + token.toByteArray(Charsets.UTF_8)), "server-B")))
            }
            val setup: JSONObject
            val digest: ByteArray
            progress("Both confirmed · exchanging fresh pairing material…")
            if (host) {
                val token = if (a.has("endpoint")) checkNotNull(engine.state).getString("token") else {
                    val credentials = secureRead(); require(credentials.getString("kind") == "server")
                    Protocol.token(credentials.getString("token")).also { verify(peerKey, hash(identityDigest + it.toByteArray(Charsets.UTF_8)), "server-B", credentials.getString("proof")) }
                }
                val secret = Protocol.randomSecret(); val master = Protocol.master(secret)
                val bundle = try { PadProtocol.generate(PadProtocol.binding(master)) } finally { master.fill(0) }
                setup = try { JSONObject().put("endpoint", endpoint).put("token", token)
                    .put("secret", secret).put("pad", b64.encodeToString(bundle)) } finally { bundle.fill(0) }
                digest = digest(a, b, setup)
                engine.stageCable(setup, "A", peerKey)
                secureSend(JSONObject().put("kind", "setup").put("setup", setup).put("proof", proof(engine, digest, "setup-A")))
            } else {
                val packet = secureRead(); require(packet.getString("kind") == "setup")
                setup = packet.getJSONObject("setup"); require(setup.getString("endpoint") == endpoint)
                digest = digest(a, b, setup)
                verify(peerKey, digest, "setup-A", packet.getString("proof"))
                engine.stageCable(setup, "B", peerKey)
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
        progress("Paired · encrypted transfer and device identity verified")
    }
}
