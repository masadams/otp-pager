package org.example.otppager

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `OTPCTL01` peer-wipe control frames (docs/PEER_WIPE_PROTOCOL.md §2–3). Pure codec: no storage,
 * no network, no destruction. Fixed lengths; anything else is rejected before any key is used.
 * UNREVIEWED: the specification has not had its independent review yet; callers are gated by
 * the off-by-default "unreviewed peer wipe" setting.
 */
internal object ControlProtocol {
    private val MAGIC = "OTPCTL01".toByteArray(Charsets.US_ASCII)
    const val WIPE_BYTES = 89
    const val ACK_BYTES = 60
    private const val WIPE: Byte = 1
    private const val ACK: Byte = 2
    enum class Direction(val code: Byte) { A_TO_B(1), B_TO_A(2);
        val reverse get() = if (this == A_TO_B) B_TO_A else A_TO_B
        companion object { fun of(code: Byte) = entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unknown control direction") }
    }
    enum class Outcome(val code: Byte) { RECEIVED(1), WIPED(2), INCOMPLETE(3);
        companion object { fun of(code: Byte) = entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unknown control outcome") }
    }
    data class Wipe(val direction: Direction, val generation: ByteArray, val request: ByteArray, val issued: Long)
    data class Ack(val direction: Direction, val generation: ByteArray, val outcome: Outcome)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32)
        return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    }

    fun wipe(key: ByteArray, direction: Direction, generation: ByteArray, request: ByteArray, issued: Long): ByteArray {
        require(generation.size == 16 && request.size == 16)
        val body = ByteBuffer.allocate(WIPE_BYTES - 32).put(MAGIC).put(WIPE).put(direction.code)
            .put(generation).put(request).putLong(issued).put(ByteArray(7)).array()
        return body + hmac(key, body)
    }
    fun ack(key: ByteArray, direction: Direction, generation: ByteArray, outcome: Outcome): ByteArray {
        require(generation.size == 16)
        val body = ByteBuffer.allocate(ACK_BYTES - 32).put(MAGIC).put(ACK).put(direction.code)
            .put(generation).put(outcome.code).put(0).array()
        return body + hmac(key, body)
    }

    /** Structure only (no key): lets a recipient find the pairing named by the generation. */
    fun peekWipe(frame: ByteArray): Wipe {
        require(frame.size == WIPE_BYTES && frame.copyOf(8).contentEquals(MAGIC) && frame[8] == WIPE) { "Not a wipe request" }
        require((50 until 57).all { frame[it] == 0.toByte() }) { "Reserved bytes set" }
        val b = ByteBuffer.wrap(frame)
        return Wipe(Direction.of(frame[9]), frame.copyOfRange(10, 26), frame.copyOfRange(26, 42), b.getLong(42))
    }
    /** Authenticated parse; [expected] is the peer's sending direction. Constant-time tag check. */
    fun openWipe(key: ByteArray, frame: ByteArray, expected: Direction, generation: ByteArray): Wipe {
        val parsed = peekWipe(frame)
        require(parsed.direction == expected) { "Control direction rejected" }
        require(MessageDigest.isEqual(parsed.generation, generation)) { "Control generation rejected" }
        require(MessageDigest.isEqual(hmac(key, frame.copyOf(WIPE_BYTES - 32)), frame.copyOfRange(WIPE_BYTES - 32, WIPE_BYTES))) { "Control tag rejected" }
        return parsed
    }
    fun peekAck(frame: ByteArray): Ack {
        require(frame.size == ACK_BYTES && frame.copyOf(8).contentEquals(MAGIC) && frame[8] == ACK && frame[27] == 0.toByte()) { "Not an acknowledgement" }
        return Ack(Direction.of(frame[9]), frame.copyOfRange(10, 26), Outcome.of(frame[26]))
    }
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** HKDF-SHA256 (RFC 5869) with an empty salt, one 32-byte block, as a 64-hex relay room. */
    fun mailbox(credential: ByteArray): String {
        require(credential.size == 32)
        val prk = hmac(ByteArray(32), credential)
        try { return PadProtocol.hex(hmac(prk, "OTP Pager control room v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))) }
        finally { prk.fill(0) }
    }
}
