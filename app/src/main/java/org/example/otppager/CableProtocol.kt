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
    private const val LIMIT = 1450000
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private val b64 = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
    private fun send(output: OutputStream, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8); require(bytes.size in 1..LIMIT)
        DataOutputStream(output).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    private fun read(input: InputStream): JSONObject {
        val source = DataInputStream(input); val size = source.readInt(); require(size in 1..LIMIT) { "Invalid cable frame" }
        val bytes = ByteArray(size); source.readFully(bytes)
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } finally { bytes.fill(0) }
    }
    private fun hello(engine: StationEngine): JSONObject = JSONObject().put("kind", "hello").put("version", 1)
        .put("key", engine.cableIdentity()).put("nonce", b64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }))
        .also { h -> engine.state?.let { h.put("endpoint", it.getString("endpoint")).put("token", it.getString("token")) } }
    private fun checkHello(h: JSONObject) {
        require(h.getString("kind") == "hello" && h.getInt("version") == 1)
        IdentityProtocol.publicKey(h.getString("key")); require(decoder.decode(h.getString("nonce")).size == 32)
        if (h.has("endpoint")) { Protocol.endpoint(h.getString("endpoint")); Protocol.token(h.getString("token")) }
    }
    private fun digest(a: JSONObject, b: JSONObject, setup: JSONObject): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            fun part(bytes: ByteArray) { stream.writeInt(bytes.size); stream.write(bytes) }
            part("Field physical cable pairing v1".toByteArray(Charsets.US_ASCII))
            for (h in listOf(a, b)) { part(IdentityProtocol.publicKey(h.getString("key")).encoded); part(decoder.decode(h.getString("nonce"))) }
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
        val own = hello(engine)
        val peer: JSONObject
        if (host) { send(output, own); peer = read(input); checkHello(peer) }
        else { peer = read(input); checkHello(peer); send(output, own) }
        require(own.getString("key") != peer.getString("key"))
        val a = if (host) own else peer; val b = if (host) peer else own
        val setup: JSONObject
        val digest: ByteArray
        progress("Exchanging fresh pairing material…")
        if (host) {
            val server = if (own.has("endpoint")) own else peer
            check(server.has("endpoint")) { "Enter and save relay settings on either device first" }
            val secret = Protocol.randomSecret(); val master = Protocol.master(secret)
            val bundle = try { PadProtocol.generate(PadProtocol.binding(master)) } finally { master.fill(0) }
            setup = try { JSONObject().put("endpoint", server.getString("endpoint")).put("token", server.getString("token"))
                .put("secret", secret).put("pad", b64.encodeToString(bundle)) } finally { bundle.fill(0) }
            digest = digest(a, b, setup)
            engine.stageCable(setup, "A", peer.getString("key")) // Retire before transfer.
            send(output, JSONObject().put("kind", "setup").put("setup", setup).put("proof", proof(engine, digest, "setup-A")))
        } else {
            val packet = read(input); require(packet.getString("kind") == "setup")
            setup = packet.getJSONObject("setup"); digest = digest(a, b, setup)
            verify(peer.getString("key"), digest, "setup-A", packet.getString("proof"))
            engine.stageCable(setup, "B", peer.getString("key"))
        }
        val code = PadProtocol.hex(digest.copyOf(16)).chunked(4).joinToString(" ")
        review(code, setup.getString("endpoint"))
        progress("Waiting for confirmation on the other device…")
        val peerKey = peer.getString("key")
        if (host) {
            send(output, JSONObject().put("kind", "accepted").put("proof", proof(engine, digest, "accept-A")))
            val accepted = read(input); require(accepted.getString("kind") == "saved")
            verify(peerKey, digest, "saved-B", accepted.getString("proof"))
            engine.commitCable(complete = false)
            send(output, JSONObject().put("kind", "complete").put("proof", proof(engine, digest, "complete-A")))
            engine.finishCable()
        } else {
            val accepted = read(input); require(accepted.getString("kind") == "accepted")
            verify(peerKey, digest, "accept-A", accepted.getString("proof"))
            engine.commitCable(complete = false)
            send(output, JSONObject().put("kind", "saved").put("proof", proof(engine, digest, "saved-B")))
            val done = read(input); require(done.getString("kind") == "complete")
            verify(peerKey, digest, "complete-A", done.getString("proof"))
            engine.finishCable()
        }
        progress("Paired · pad and device identity verified")
    }
}
