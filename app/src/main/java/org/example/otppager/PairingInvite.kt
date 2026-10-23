package org.example.otppager

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** A bearer secret for physical exchange, never a public link or a pad backup. */
class PairingInvite(val endpoint: String, val token: String, val secret: String, val role: String) {
    init {
        require(endpoint.length <= 256 && Protocol.endpoint(endpoint) == endpoint)
        require(Protocol.token(token) == token)
        Protocol.master(secret).fill(0)
        require(role in listOf("A", "B"))
    }
    override fun toString() = "PairingInvite([REDACTED])"
    fun encode(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeUTF(endpoint); it.writeUTF(token); it.writeUTF(secret); it.writeByte(role[0].code) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }
    companion object {
        private const val PREFIX = "FIELDPAIR1:"
        fun decode(value: String): PairingInvite = try { parse(value) }
        catch (e: Exception) { throw IllegalArgumentException("Invalid Field setup QR; no settings changed", e) }
        private fun parse(value: String): PairingInvite {
            require(value.length in 100..1024 && value.startsWith(PREFIX)) { "This is not a Field pairing QR" }
            val encoded = value.removePrefix(PREFIX)
            val bytes = Base64.getUrlDecoder().decode(encoded)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
            return DataInputStream(ByteArrayInputStream(bytes)).use {
                val invite = PairingInvite(it.readUTF(), it.readUTF(), it.readUTF(), it.readUnsignedByte().toChar().toString())
                require(it.read() == -1) { "Unexpected pairing data" }; invite
            }
        }
    }
}
