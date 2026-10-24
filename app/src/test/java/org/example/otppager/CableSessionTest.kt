package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64

class CableSessionTest {
    private fun card(pair: java.security.KeyPair) = Base64.getEncoder().encodeToString(pair.public.encoded)
    private fun sessions(transcriptB: ByteArray = ByteArray(32) { 7 }): Pair<CableSession, CableSession> {
        val a = CableSession.ephemeral(); val b = CableSession.ephemeral()
        return CableSession.establish(true, a, card(b), ByteArray(32) { 7 }) to CableSession.establish(false, b, card(a), transcriptB)
    }
    private fun wire(sender: CableSession, text: ByteArray): ByteArray = ByteArrayOutputStream().also { sender.send(it, text) }.toByteArray()
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid cable frame accepted") } catch (_: Exception) {} }
    @Test fun encryptedBidirectionalLargeTransferAndReplayRejection() {
        val (a, b) = sessions()
        a.use { b.use {
            val pad = ByteArray(1400000) { (it % 251).toByte() }
            val encrypted = wire(a, pad)
            assertFalse(encrypted.copyOfRange(12, 76).contentEquals(pad.copyOfRange(0, 64)))
            assertArrayEquals(pad, b.read(ByteArrayInputStream(encrypted)))
            val response = "saved".toByteArray()
            assertArrayEquals(response, a.read(ByteArrayInputStream(wire(b, response))))
            rejected { b.read(ByteArrayInputStream(encrypted)) }
            rejected { b.send(ByteArrayOutputStream(), response) } // Failure poisons the session.
        } }
    }
    @Test fun rejectsModifiedTagSequenceReflectionAndDifferentTranscript() {
        for (attack in listOf("tag", "sequence", "reflection", "transcript", "truncate", "oversize")) {
            val (a, b) = sessions(if (attack == "transcript") ByteArray(32) { 8 } else ByteArray(32) { 7 })
            a.use { b.use {
                val packet = wire(a, "secret token".toByteArray())
                when (attack) {
                    "tag" -> packet[packet.lastIndex] = (packet.last().toInt() xor 1).toByte()
                    "sequence" -> packet[11] = 1
                    "oversize" -> ByteBuffer.wrap(packet).putInt(Int.MAX_VALUE)
                }
                val receiver = if (attack == "reflection") a else b
                val bytes = if (attack == "truncate") packet.copyOf(packet.size - 1) else packet
                rejected { receiver.read(ByteArrayInputStream(bytes)) }
                rejected { receiver.read(ByteArrayInputStream(packet)) }
            } }
        }
    }
    @Test fun independentPythonEcdhHkdfAndFrameVector() {
        val v = java.util.Properties().apply { load(CableSessionTest::class.java.getResourceAsStream("/cable-v3.properties")) }
        fun bytes(name: String) = Base64.getDecoder().decode(v.getProperty(name))
        val privateKey = java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(bytes("privateA")))
        val own = java.security.KeyPair(IdentityProtocol.publicKey(v.getProperty("publicA")), privateKey)
        CableSession.establish(true, own, v.getProperty("publicB"), bytes("transcript")).use {
            assertArrayEquals(bytes("wire"), wire(it, bytes("plaintext")))
        }
    }
    @Test fun outputFailureCannotReuseSessionOrNonce() {
        val (a, b) = sessions()
        a.use { b.use {
            val broken = object : java.io.OutputStream() { override fun write(value: Int) { throw java.io.IOException("disconnected") } }
            rejected { a.send(broken, byteArrayOf(1)) }
            rejected { a.send(ByteArrayOutputStream(), byteArrayOf(1)) }
        } }
    }
}
