package org.example.otppager

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Experimental finite pad transport. HMAC authentication is computational, not a one-time MAC. */
object PadProtocol {
    const val MATERIAL_BYTES = 1048576
    const val BLOCK = 4096
    const val STRIDE = BLOCK + 32
    const val SLOTS = MATERIAL_BYTES / 2 / STRIDE // 127 per direction; unused tail is never allocated.
    const val BUNDLE_BYTES = 8 + 32 + MATERIAL_BYTES
    private val magic = "FIELDP03".toByteArray(Charsets.US_ASCII)
    private val b64 = Base64.getEncoder()
    fun generate(binding: ByteArray): ByteArray = magic + binding.also { require(it.size == 32) } + ByteArray(MATERIAL_BYTES).also { SecureRandom().nextBytes(it) }
    fun binding(master: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest("Field pad binding v3".toByteArray() + master)
    fun material(bundle: ByteArray, binding: ByteArray): ByteArray {
        require(bundle.size == BUNDLE_BYTES && bundle.copyOfRange(0, 8).contentEquals(magic)) { "Invalid pad file" }
        require(MessageDigest.isEqual(binding, bundle.copyOfRange(8, 40))) { "Pad file belongs to a different pairing" }
        return bundle.copyOfRange(40, bundle.size)
    }
    fun id(pad: ByteArray): ByteArray { require(pad.size == MATERIAL_BYTES); return MessageDigest.getInstance("SHA-256").digest(pad).copyOf(16) }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun offset(role: Char, counter: Long): Int {
        require(role == 'A' || role == 'B'); require(counter in 1..SLOTS.toLong()) { "Pad exhausted; exchange a fresh pad" }
        return (if (role == 'A') 0 else MATERIAL_BYTES / 2) + (counter.toInt() - 1) * STRIDE
    }
    /** Logical removal only: old flash pages, backups and retained history are separate concerns. */
    fun retire(pad: ByteArray, role: Char, counter: Long) {
        require(pad.size == MATERIAL_BYTES)
        val at = offset(role, counter)
        pad.fill(0, at, at + STRIDE)
    }
    private fun requireAvailable(pad: ByteArray, at: Int) {
        require((at until at + STRIDE).any { pad[it] != 0.toByte() }) { "Pad slot retired" }
    }
    fun authenticate(key: ByteArray, bytes: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(bytes) }
    fun seal(pad: ByteArray, role: Char, counter: Long, payload: String, stableId: ByteArray = id(pad)): Pair<String, String> {
        require(pad.size == MATERIAL_BYTES && stableId.size == 16)
        val data = payload.toByteArray(Charsets.UTF_8)
        require(data.size <= BLOCK - 2) { "Encoded message too large for a pad slot" }
        val at = offset(role, counter)
        requireAvailable(pad, at)
        val header = ByteBuffer.allocate(26).put(3).put(role.code.toByte()).putLong(counter).put(stableId).array()
        val clear = ByteBuffer.allocate(BLOCK).putShort(data.size.toShort()).put(data).array()
        val cipher = ByteArray(BLOCK) { (clear[it].toInt() xor pad[at + it].toInt()).toByte() }
        val key = pad.copyOfRange(at + BLOCK, at + STRIDE)
        return try {
            val wire = header + cipher
            messageId(pad, role, counter, stableId) to b64.encodeToString(wire + authenticate(key, wire))
        } finally { key.fill(0); clear.fill(0); data.fill(0) }
    }
    fun messageId(pad: ByteArray, role: Char, counter: Long, stableId: ByteArray = id(pad)): String = hex(MessageDigest.getInstance("SHA-256").digest(stableId + role.code.toByte() + ByteBuffer.allocate(8).putLong(counter).array()).copyOf(16))
    fun role(wire: String): Char? = try {
        val bytes = Base64.getDecoder().decode(wire)
        if (bytes.size == 26 + BLOCK + 32 && bytes[0] == 3.toByte() && bytes[1].toInt().toChar() in listOf('A', 'B')) bytes[1].toInt().toChar() else null
    } catch (_: Exception) { null }
    fun open(pad: ByteArray, wire: String, role: Char, stableId: ByteArray = id(pad)): Protocol.Opened {
        require(pad.size == MATERIAL_BYTES && stableId.size == 16)
        require(wire.length <= 5600)
        val bytes = Base64.getDecoder().decode(wire)
        require(bytes.size == 26 + BLOCK + 32 && bytes[0] == 3.toByte() && bytes[1] == role.code.toByte())
        require(MessageDigest.isEqual(stableId, bytes.copyOfRange(10, 26))) { "Wrong pad" }
        val counter = ByteBuffer.wrap(bytes, 2, 8).long
        val at = offset(role, counter); requireAvailable(pad, at); val key = pad.copyOfRange(at + BLOCK, at + STRIDE)
        try { require(MessageDigest.isEqual(authenticate(key, bytes.copyOfRange(0, 26 + BLOCK)), bytes.copyOfRange(26 + BLOCK, bytes.size))) { "Invalid pad authentication" } }
        finally { key.fill(0) }
        val clear = ByteArray(BLOCK) { (bytes[26 + it].toInt() xor pad[at + it].toInt()).toByte() }
        return try {
            val length = ByteBuffer.wrap(clear).short.toInt() and 65535
            require(length <= BLOCK - 2)
            Protocol.Opened(counter, messageId(pad, role, counter, stableId), String(clear, 2, length, Charsets.UTF_8))
        } finally { clear.fill(0) }
    }
}
