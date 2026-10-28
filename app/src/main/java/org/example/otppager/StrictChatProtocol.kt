package org.example.otppager

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Candidate strict-chat v1 codec. Not connected to storage, networking or UI. */
internal object StrictChatProtocol {
    const val BYTES = 178
    const val MAX_LEASE_MS = 60_000L
    private val magic = "OTPSTR01".toByteArray(Charsets.US_ASCII)
    enum class Kind(val wire: Int) { ENROLL(1), UNLOCK(2), GRANT(3), REVOKE(4), REVOKED(5), ENROLLED(6) }
    data class Frame(val kind: Kind, val role: Char, val generation: ByteArray,
        val device: ByteArray, val authority: ByteArray, val epoch: Long,
        val nonce: ByteArray, val issued: Long, val expires: Long, val share: ByteArray)

    fun encode(frame: Frame): ByteArray = with(frame) {
        require(role == 'A' || role == 'B')
        require(generation.size == 16 && device.size == 32 && authority.size == 32 && nonce.size == 32 && share.size == 32)
        require(epoch > 0)
        if (kind == Kind.REVOKE || kind == Kind.REVOKED) require(issued == 0L && expires == 0L)
        else require(issued > 0 && expires > issued && expires - issued <= MAX_LEASE_MS)
        if (kind != Kind.ENROLL && kind != Kind.GRANT) require(share.all { it == 0.toByte() })
        ByteBuffer.allocate(BYTES).put(magic).put(kind.wire.toByte()).put(role.code.toByte())
            .put(generation).put(device).put(authority).putLong(epoch).put(nonce)
            .putLong(issued).putLong(expires).put(share).array()
    }
    fun decode(bytes: ByteArray): Frame {
        require(bytes.size == BYTES)
        val b = ByteBuffer.wrap(bytes)
        fun take(n: Int) = ByteArray(n).also { b.get(it) }
        require(take(8).contentEquals(magic))
        val wire = b.get().toInt() and 255
        val kind = Kind.entries.singleOrNull { it.wire == wire } ?: error("Unknown strict frame kind")
        val result = Frame(kind, (b.get().toInt() and 255).toChar(), take(16), take(32), take(32),
            b.long, take(32), b.long, b.long, take(32))
        require(encode(result).contentEquals(bytes))
        return result
    }
    fun fingerprint(card: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(IdentityProtocol.publicKey(card).encoded)

    /** Verify the pinned P-256 signer before exposing a parsed frame. HTTPS remains required. */
    fun verified(bytes: ByteArray, signature: ByteArray, signer: String, response: Boolean): Frame {
        require(bytes.size == BYTES)
        val snapshot = bytes.copyOf()
        require(signature.size in 8..80)
        require(IdentityProtocol.verify(signer, snapshot, signature)) { "Strict signature rejected" }
        val frame = decode(snapshot)
        val isResponse = frame.kind in listOf(Kind.GRANT, Kind.REVOKED, Kind.ENROLLED)
        require(response == isResponse)
        require(MessageDigest.isEqual(fingerprint(signer), if (response) frame.authority else frame.device))
        return frame
    }

    /** One outstanding unlock. The caller must cancel it on background, retirement or renewal.
     * The resulting share must stay in memory; this class is not a vault-unlock implementation.
     */
    class PendingUnlock(request: Frame, private val started: Long) {
        private val requestBytes = encode(request).also { require(request.kind == Kind.UNLOCK) }
        private var terminal = false
        @Synchronized fun cancel() { terminal = true }
        @Synchronized fun accept(bytes: ByteArray, signature: ByteArray, authorityCard: String,
            now: Long, elapsed: Long): ByteArray {
            check(!terminal) { "Unlock no longer pending" }
            val expected = decode(requestBytes)
            val age = elapsed - started
            require(elapsed >= started && age >= 0 && age < expected.expires - expected.issued)
            require(now >= expected.issued && now < expected.expires)
            val grant = verified(bytes, signature, authorityCard, response = true)
            require(grant.kind == Kind.GRANT && grant.role == expected.role && grant.epoch == expected.epoch)
            require(grant.generation.contentEquals(expected.generation) && grant.device.contentEquals(expected.device))
            require(grant.authority.contentEquals(expected.authority) && grant.nonce.contentEquals(expected.nonce))
            require(grant.issued == expected.issued && grant.expires == expected.expires)
            terminal = true
            return grant.share.copyOf()
        }
    }
}
