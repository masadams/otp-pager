package org.example.otppager

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One ephemeral physical-pairing session. Keys are never stored or reused across sessions. */
internal class CableSession private constructor(
    private val sendKey: ByteArray, private val receiveKey: ByteArray,
    private val context: ByteArray, private val sendDirection: Byte, private val receiveDirection: Byte,
    private val limit: Int = LIMIT
) : AutoCloseable {
    private var sent = 0L
    private var received = 0L
    private var closed = false
    private fun aad(direction: Byte, sequence: Long) = context + byteArrayOf(direction) + ByteBuffer.allocate(8).putLong(sequence).array()
    private fun nonce(sequence: Long) = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array()
    // A failed write consumes the sequence and invalidates the entire channel; never retry encryption.
    @Synchronized fun send(output: OutputStream, plaintext: ByteArray) {
        check(!closed); require(plaintext.size in 1..limit); check(sent < Long.MAX_VALUE)
        val sequence = sent++
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(sequence)))
            cipher.updateAAD(aad(sendDirection, sequence))
            val encrypted = cipher.doFinal(plaintext)
            try { DataOutputStream(output).apply { writeInt(encrypted.size); writeLong(sequence); write(encrypted); flush() } }
            finally { encrypted.fill(0) }
        } catch (failure: Exception) { close(); throw failure }
    }
    @Synchronized fun read(input: InputStream): ByteArray {
        check(!closed)
        try {
            val source = DataInputStream(input)
            val size = source.readInt(); require(size in 17..(limit + 16)) { "Invalid encrypted cable frame" }
            val sequence = source.readLong(); require(sequence == received && received < Long.MAX_VALUE) { "Cable sequence rejected" }
            val encrypted = ByteArray(size)
            try {
                source.readFully(encrypted)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"), GCMParameterSpec(128, nonce(sequence)))
                cipher.updateAAD(aad(receiveDirection, sequence))
                return cipher.doFinal(encrypted).also { received++ }
            } finally { encrypted.fill(0) }
        } catch (failure: Exception) { close(); throw failure }
    }
    override fun close() { closed = true; sendKey.fill(0); receiveKey.fill(0); context.fill(0) }
    companion object {
        const val LIMIT = 5700000
        /** Separate domain and bounded records for the NFC secret's outer radio envelope. */
        fun radio(host: Boolean, secret: ByteArray, transcript: ByteArray): CableSession {
            require(secret.size == 32 && transcript.size == 32)
            fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
            }
            val prk = hmac(transcript, secret)
            try {
                fun expand(direction: String) = hmac(prk, "Field NFC radio v1 / $direction".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
                val a = expand("A to B"); val b = expand("B to A")
                return if (host) CableSession(a, b, transcript.copyOf(), 0, 1, 32768)
                    else CableSession(b, a, transcript.copyOf(), 1, 0, 32768)
            } finally { prk.fill(0) }
        }
        fun ephemeral(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        /**
         * Hybrid key schedule: HKDF-SHA256 (RFC 5869) extract over the ECDH secret followed by both
         * ML-KEM-768 secrets ([postQuantum], A's encapsulation then B's), salted with the confirmed
         * transcript, then a single-block expand per direction. An attacker must break both ECDH
         * and ML-KEM to read the pad, so recorded USB traffic stays safe against a future quantum
         * computer. The transcript already binds every public key and ciphertext.
         */
        fun establish(host: Boolean, own: KeyPair, peerCard: String, transcript: ByteArray, postQuantum: ByteArray): CableSession {
            require(transcript.size == 32 && postQuantum.size == 2 * PostQuantum.KEM_SECRET_BYTES)
            val shared = KeyAgreement.getInstance("ECDH").run { init(own.private); doPhase(IdentityProtocol.publicKey(peerCard), true); generateSecret() }
            fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
            val combined = shared + postQuantum
            val prk = try { hmac(transcript, combined) } finally { shared.fill(0); combined.fill(0) }
            fun expand(direction: String) = hmac(prk, "Field hybrid USB v4 ECDH+ML-KEM-768 / $direction".toByteArray(Charsets.US_ASCII) + byteArrayOf(1))
            try {
                val a = expand("A to B"); val b = expand("B to A")
                return if (host) CableSession(a, b, transcript.copyOf(), 0, 1) else CableSession(b, a, transcript.copyOf(), 1, 0)
            } finally { prk.fill(0) }
        }
    }
}
