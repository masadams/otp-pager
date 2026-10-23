package org.example.otppager

import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Experimental v2 framing. AES-GCM, not OTP or Jericho. No Android dependencies. */
object Protocol {
    private val rng = SecureRandom()
    private const val HEADER = 38 // version, role, counter(8), id(16), nonce(12)
    data class Opened(val counter: Long, val id: String, val payload: String)
    fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(rng::nextBytes))
    fun master(secret: String): ByteArray {
        require(Regex("[A-Za-z0-9_-]{43}").matches(secret)) { "Pairing secret must be 43 URL-safe characters" }
        return Base64.getUrlDecoder().decode(secret).also { require(it.size == 32) }
    }
    fun endpoint(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
            "Use an HTTPS relay URL without credentials, query or fragment"
        }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Use the relay hostname without a path" }
        return uri.toASCIIString().trimEnd('/')
    }
    fun token(value: String): String = value.trim().also {
        require(Regex("[A-Za-z0-9_-]{43,128}").matches(it)) { "Relay token must be 43–128 URL-safe characters" }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    // Retain mailbox derivation so the existing relay URL and pairing secret still work.
    fun room(master: ByteArray) = hex(hash("room".toByteArray() + master))
    fun fingerprint(master: ByteArray) = hex(hash("verify:v2".toByteArray() + master)).take(24).chunked(4).joinToString(" ")
    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    private fun key(master: ByteArray, role: Char): ByteArray {
        // RFC 5869 HKDF extract and a single expand block, with domain separation.
        val prk = hmac("OTP Pager / protocol v2".toByteArray(), master)
        return try { hmac(prk, "message:$role".toByteArray() + byteArrayOf(1)) } finally { prk.fill(0) }
    }
    fun seal(master: ByteArray, role: Char, counter: Long, payload: String): Pair<String, String> {
        require(role == 'A' || role == 'B'); require(counter > 0)
        require(payload.toByteArray(Charsets.UTF_8).size <= 8192)
        val id = ByteArray(16).also(rng::nextBytes)
        val nonce = ByteArray(12).also(rng::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(2.toByte()).put(role.code.toByte()).putLong(counter).put(id).put(nonce).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = key(master, role)
        return try {
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            hex(id) to Base64.getEncoder().encodeToString(header + cipher.doFinal(payload.toByteArray(Charsets.UTF_8)))
        } finally { key.fill(0) }
    }
    fun role(wire: String): Char? = try {
        val packet = Base64.getDecoder().decode(wire)
        if (packet.size < HEADER + 16 || packet[0] != 2.toByte()) null else packet[1].toInt().toChar()
    } catch (_: IllegalArgumentException) { null }
    fun open(master: ByteArray, wire: String, expectedRole: Char): Opened {
        require(wire.length <= 12000)
        val packet = Base64.getDecoder().decode(wire)
        require(packet.size in (HEADER + 16)..(HEADER + 16 + 8192))
        require(packet[0] == 2.toByte() && packet[1] == expectedRole.code.toByte()) { "Unsupported packet" }
        val key = key(master, expectedRole)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet.copyOfRange(26, 38)))
            cipher.updateAAD(packet.copyOfRange(0, HEADER))
            val clear = cipher.doFinal(packet.copyOfRange(HEADER, packet.size))
            val counter = ByteBuffer.wrap(packet, 2, 8).long
            require(counter > 0)
            try { Opened(counter, hex(packet.copyOfRange(10, 26)), String(clear, Charsets.UTF_8)) }
            finally { clear.fill(0) }
        } finally { key.fill(0) }
    }
}

/** Persist with inbox/cursor in the same transaction. Older than the window is rejected. */
data class ReplayWindow(val high: Long = 0, val received: Set<Long> = emptySet()) {
    init { require(high >= 0 && received.size <= 4096 && received.all { it > 0 && it <= high && high - it < 4096 }) }
    fun accepts(counter: Long) = counter > 0 && (counter > high || high - counter < 4096) && counter !in received
    fun accept(counter: Long): ReplayWindow {
        require(accepts(counter)) { "Duplicate or stale message" }
        val nextHigh = maxOf(high, counter)
        return ReplayWindow(nextHigh, (received + counter).filter { nextHigh - it < 4096 }.toSet())
    }
}
