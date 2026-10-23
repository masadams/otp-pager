package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class IdentityProtocolTest {
    private fun pair(curve: String = "secp256r1") = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
    private fun card(pair: java.security.KeyPair) = Base64.getEncoder().encodeToString(pair.public.encoded)
    @Test fun signatureBindsEveryContextFieldAndPayload() {
        val a = pair(); val b = pair(); val impostor = pair()
        val room = "a".repeat(64); val pad = "b".repeat(32)
        val ac = card(a); val bc = card(b)
        val body = "{\"kind\":\"text\",\"text\":\"HELLO\"}"
        fun data(r: String = room, p: String = pad, role: Char = 'A', n: Long = 7, sender: String = ac, recipient: String = bc, text: String = body) = IdentityProtocol.transcript(r, p, role, n, sender, recipient, text)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(a.private); update(data()); sign() }
        assertTrue(IdentityProtocol.verify(ac, data(), sig))
        for (changed in listOf(data(r = "c".repeat(64)), data(p = "c".repeat(32)), data(role = 'B'), data(n = 8), data(sender = card(impostor)), data(recipient = card(impostor)), data(text = "$body "))) {
            assertFalse(IdentityProtocol.verify(ac, changed, sig))
        }
        val envelope = IdentityProtocol.signed(body) { sig }
        assertEquals(body, IdentityProtocol.body(envelope) { text, signature -> IdentityProtocol.verify(ac, data(text = text), signature) })
        try { IdentityProtocol.body(envelope) { _, _ -> false }; fail("Invalid signature accepted") } catch (_: IllegalArgumentException) {}
        try { IdentityProtocol.body(body) { _, _ -> true }; fail("Unsigned accepted") } catch (_: IllegalArgumentException) {}
        assertEquals(64, IdentityProtocol.fingerprint(ac).length)
    }
    @Test fun independentPythonSignatureAndTranscriptVector() {
        val vector = java.util.Properties().apply { load(IdentityProtocolTest::class.java.getResourceAsStream("/device-v1.properties")) }
        val data = IdentityProtocol.transcript(vector.getProperty("room"), vector.getProperty("pad"), 'A', 7,
            vector.getProperty("sender"), vector.getProperty("recipient"), vector.getProperty("body"))
        assertEquals(vector.getProperty("transcript"), Base64.getEncoder().encodeToString(data))
        assertTrue(IdentityProtocol.verify(vector.getProperty("sender"), data, Base64.getDecoder().decode(vector.getProperty("signature"))))
    }
    @Test fun rejectsWrongCurveMalformedCardsAndOversizedEnvelope() {
        for (bad in listOf("bad", card(pair("secp384r1")), "A".repeat(160))) {
            try { IdentityProtocol.publicKey(bad); fail("Invalid key accepted") } catch (_: Exception) {}
        }
        try { IdentityProtocol.body("FD1:" + "A".repeat(5000)) { _, _ -> true }; fail("Oversized accepted") } catch (_: IllegalArgumentException) {}
    }
}
