package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ProtocolTest {
    private val master = ByteArray(32) { it.toByte() }
    private fun rejects(action: () -> Unit) { try { action(); fail("Expected rejection") } catch (_: Exception) {} }
    @Test fun twoDirectionsUnicodeAndEmptyRoundTrips() {
        for (role in listOf('A', 'B')) for (text in listOf("", "RENDEZVOUS — café 🛰", "\n".repeat(2048))) {
            val (id, wire) = Protocol.seal(master, role, 21L, text)
            val decoded = Protocol.open(master, wire, role)
            assertEquals(id, decoded.id); assertEquals(21L, decoded.counter); assertEquals(text, decoded.payload)
            assertEquals(role, Protocol.role(wire))
        }
    }
    @Test fun wrongKeyRoleAndAllHeaderFieldsAreAuthenticated() {
        val wire = Protocol.seal(master, 'A', 1L, "message").second
        rejects { Protocol.open(ByteArray(32), wire, 'A') }
        rejects { Protocol.open(master, wire, 'B') }
        for (i in Base64.getDecoder().decode(wire).indices) {
            val bytes = Base64.getDecoder().decode(wire); bytes[i] = (bytes[i].toInt() xor 1).toByte()
            rejects { Protocol.open(master, Base64.getEncoder().encodeToString(bytes), 'A') }
        }
    }
    @Test fun sharedWireVectorMatchesNode() {
        val fixture = java.util.Properties().apply { load(ProtocolTest::class.java.getResourceAsStream("/protocol-v2.properties")) }
        val bytes = fixture.getProperty("master").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val opened = Protocol.open(bytes, fixture.getProperty("wire"), 'A')
        assertEquals(fixture.getProperty("payload"), opened.payload)
        assertEquals(fixture.getProperty("counter").toLong(), opened.counter)
        assertEquals(fixture.getProperty("id"), opened.id)
    }
    @Test fun randomMessagesGetDistinctIdsAndCiphertext() {
        val first = Protocol.seal(master, 'A', 1, "same")
        val second = Protocol.seal(master, 'A', 2, "same")
        assertNotEquals(first.first, second.first); assertNotEquals(first.second, second.second)
    }
    @Test fun replayWindowAllowsReorderingRejectsDuplicatesAndStaleAfterReload() {
        var window = ReplayWindow().accept(3).accept(1).accept(2)
        assertFalse(window.accepts(1)); assertFalse(window.accepts(0))
        window = ReplayWindow(window.high, window.received.toSet())
        assertFalse(window.accepts(3)); assertTrue(window.accepts(4))
        window = window.accept(5000)
        assertFalse(window.accepts(2)); assertTrue(window.accepts(4999)); assertFalse(window.accepts(904))
        assertTrue(window.received.size <= 4096)
    }
    @Test fun settingsRejectCredentialUrlsRedirectTricksAndInvalidSecrets() {
        assertEquals("https://pager.example.com", Protocol.endpoint("https://pager.example.com/"))
        for (url in listOf("http://pager.example.com", "https://user:pass@pager.example.com", "https://pager.example.com/?token=foo", "https://pager.example.com/#secret", "https://pager.example.com/path"))
            rejects { Protocol.endpoint(url) }
        val secret = Protocol.randomSecret(); assertEquals(43, secret.length); assertEquals(32, Protocol.master(secret).size)
        rejects { Protocol.master("weak") }; rejects { Protocol.token("weak") }
    }
}
