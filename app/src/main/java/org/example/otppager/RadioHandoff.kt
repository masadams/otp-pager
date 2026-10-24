package org.example.otppager

import java.io.*
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Short-lived physical handoff. Never log, persist, or send this ticket over IP. */
internal class RadioTicket(val address: String, val port: Int, val id: ByteArray, val secret: ByteArray) : AutoCloseable {
    init {
        require(port in 1024..65535 && id.size == 16 && secret.size == 32)
        require(address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
        val ip = InetAddress.getByName(address)
        require(ip.hostAddress == address && ip.isSiteLocalAddress && !ip.isLoopbackAddress) { "Use a private local Wi-Fi address" }
    }
    fun context(): ByteArray = "Field NFC Wi-Fi ticket v1\n$address\n$port\n".toByteArray(Charsets.US_ASCII) + id
    fun encode(): String {
        val bytes = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply { writeUTF(address); writeInt(port); write(id); write(secret) } }.toByteArray()
        return try { PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) } finally { bytes.fill(0) }
    }
    override fun close() { id.fill(0); secret.fill(0) }
    companion object {
        private const val PREFIX = "otp-pager-radio-v1:"
        fun decode(value: String): RadioTicket {
            require(value.startsWith(PREFIX) && value.length <= 256)
            val bytes = Base64.getUrlDecoder().decode(value.removePrefix(PREFIX))
            return try {
                DataInputStream(ByteArrayInputStream(bytes)).use { s ->
                    val address = s.readUTF(); val port = s.readInt()
                    val id = ByteArray(16).also(s::readFully); val key = ByteArray(32).also(s::readFully)
                    try { require(s.available() == 0); RadioTicket(address, port, id, key) }
                    catch (e: Exception) { id.fill(0); key.fill(0); throw e }
                }
            } finally { bytes.fill(0) }
        }
        fun create(address: String, port: Int) = RadioTicket(address, port,
            ByteArray(16).also { SecureRandom().nextBytes(it) }, ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}

/** Mutual PSK proof with fresh challenges, then separate AES-256-GCM directions.
 * The NFC secret is additional to the signed, code-compared cable protocol inside.
 */
internal class RadioHandoff private constructor(private val cipher: CableSession,
    private val source: InputStream, private val sink: OutputStream) : AutoCloseable {
    private var pending = ByteArray(0)
    private var offset = 0
    val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            require(start >= 0 && length >= 0 && start <= bytes.size - length)
            if (length == 0) return 0
            if (offset == pending.size) { pending.fill(0); pending = cipher.read(source); offset = 0 }
            val count = minOf(length, pending.size - offset)
            pending.copyInto(bytes, start, offset, offset + count); pending.fill(0, offset, offset + count); offset += count
            return count
        }
    }
    val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, start: Int, length: Int) {
            require(start >= 0 && length >= 0 && start <= bytes.size - length)
            var at = start
            while (at < start + length) {
                val end = minOf(at + 32768, start + length); val chunk = bytes.copyOfRange(at, end)
                try { cipher.send(sink, chunk) } finally { chunk.fill(0) }; at = end
            }
        }
        override fun flush() = sink.flush()
    }
    override fun close() { pending.fill(0); cipher.close() }
    companion object {
        private fun proof(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
        }
        fun establish(host: Boolean, input: InputStream, output: OutputStream, ticket: RadioTicket): RadioHandoff {
            val read = DataInputStream(input); val write = DataOutputStream(output)
            val own = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val peer = ByteArray(32)
            val context = ticket.context()
            var transcript = ByteArray(0)
            try {
                if (host) { write.write(own); write.flush(); read.readFully(peer) }
                else { read.readFully(peer); write.write(own); write.flush() }
                transcript = MessageDigest.getInstance("SHA-256").digest(context + (if (host) own + peer else peer + own))
                fun send(label: String) {
                    val p = proof(ticket.secret, transcript + label.toByteArray(Charsets.US_ASCII))
                    try { write.write(p); write.flush() } finally { p.fill(0) }
                }
                fun verify(label: String) {
                    val p = ByteArray(32); val expected = proof(ticket.secret, transcript + label.toByteArray(Charsets.US_ASCII))
                    try { read.readFully(p); require(MessageDigest.isEqual(p, expected)) { "Physical transfer secret rejected" } }
                    finally { p.fill(0); expected.fill(0) }
                }
                if (host) { verify("reader"); send("sharer") } else { send("reader"); verify("sharer") }
                return RadioHandoff(CableSession.radio(host, ticket.secret, transcript), input, output)
            } finally { own.fill(0); peer.fill(0); context.fill(0); transcript.fill(0) }
        }
    }
}
