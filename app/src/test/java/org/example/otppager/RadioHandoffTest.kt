package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RadioHandoffTest {
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid wireless input accepted") } catch (_: Exception) {} }
    @Test fun ticketStrictParsingAndWipe() {
        val t = RadioTicket.create("192.168.4.2", 19000)
        val encoded = t.encode()
        RadioTicket.decode(encoded).use { assertArrayEquals(t.secret, it.secret); assertArrayEquals(t.context(), it.context()) }
        for (ip in listOf("127.0.0.1", "8.8.8.8", "192.168.1.999", "192.168.001.1", "example.com", "::1"))
            rejected { RadioTicket.create(ip, 19000) }
        rejected { RadioTicket.decode(encoded + "AAAA") }; rejected { RadioTicket.decode("x".repeat(1025)) }
        rejected { RadioTicket.create("10.0.0.1", 80) }
        t.close(); assertTrue(t.secret.all { it == 0.toByte() })
    }
    @Test fun independentPythonRadioRecordVector() {
        val v = java.util.Properties().apply { load(RadioHandoffTest::class.java.getResourceAsStream("/radio-v1.properties")) }
        fun bytes(name: String) = Base64.getDecoder().decode(v.getProperty(name))
        CableSession.radio(true, bytes("secret"), bytes("transcript")).use { a ->
            val out = ByteArrayOutputStream(); a.send(out, bytes("plaintext")); assertArrayEquals(bytes("wire"), out.toByteArray())
        }
    }
    @Test fun radioEnvelopeRejectsTamperingReplayReflectionAndOtherSession() {
        val key = ByteArray(32) { it.toByte() }; val context = ByteArray(32) { 9 }
        for (attack in listOf("tag", "sequence", "length", "reflection", "context", "replay")) {
            CableSession.radio(true, key, context).use { a ->
                CableSession.radio(false, key, if (attack == "context") ByteArray(32) { 8 } else context).use { b ->
                    val wire = ByteArrayOutputStream().also { a.send(it, ByteArray(32768) { 42 }) }.toByteArray()
                    when (attack) {
                        "tag" -> wire[wire.lastIndex] = (wire.last().toInt() xor 1).toByte()
                        "sequence" -> wire[11] = 1
                        "length" -> ByteBuffer.wrap(wire).putInt(5700016)
                        "replay" -> assertEquals(32768, b.read(ByteArrayInputStream(wire)).size)
                    }
                    val receiver = if (attack == "reflection") a else b
                    rejected { receiver.read(ByteArrayInputStream(wire)) }
                    rejected { receiver.send(ByteArrayOutputStream(), byteArrayOf(1)) }
                }
            }
        }
    }
    @Test fun mutualHandshakeLargeStreamAndWrongSecretRejection() {
        for (wrong in listOf(false, true)) {
            val pool = Executors.newSingleThreadExecutor()
            ServerSocket(0).use { server ->
                val t = RadioTicket.create("10.1.2.3", server.localPort)
                val other = RadioTicket.decode(t.encode())
                if (wrong) other.secret[0] = (other.secret[0].toInt() xor 1).toByte()
                val bytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
                val host = pool.submit<Boolean> {
                    server.accept().use { s -> s.soTimeout = 5000
                        try {
                            RadioHandoff.establish(true, s.getInputStream(), s.getOutputStream(), t).use { h ->
                                h.output.write(bytes); h.output.flush()
                                assertEquals(77, h.input.read())
                            }; true
                        } catch (e: Exception) { if (!wrong) throw e; false }
                    }
                }
                try {
                    Socket("127.0.0.1", server.localPort).use { s -> s.soTimeout = 5000
                        if (wrong) rejected { RadioHandoff.establish(false, s.getInputStream(), s.getOutputStream(), other) }
                        else RadioHandoff.establish(false, s.getInputStream(), s.getOutputStream(), other).use { h ->
                            val received = ByteArray(bytes.size); DataInputStream(h.input).readFully(received)
                            assertArrayEquals(bytes, received); h.output.write(77); h.output.flush()
                        }
                    }
                    assertEquals(!wrong, host.get(20, TimeUnit.SECONDS))
                } finally { t.close(); other.close(); pool.shutdownNow() }
            }
        }
    }
}
