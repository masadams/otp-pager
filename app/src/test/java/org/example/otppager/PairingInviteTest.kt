package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PairingInviteTest {
    private val token = "t".repeat(43)
    private val secret = Protocol.randomSecret()
    private fun rejects(action: () -> Unit) { try { action(); fail("Invalid invitation accepted") } catch (_: IllegalArgumentException) {} }
    @Test fun roundTripIncludesOnlySetupAndDoesNotPrintSecrets() {
        val original = PairingInvite("https://pager.example.com", token, secret, "B")
        val wire = original.encode(); val result = PairingInvite.decode(wire)
        assertEquals(original.endpoint, result.endpoint); assertEquals(token, result.token)
        assertEquals(secret, result.secret); assertEquals("B", result.role)
        assertEquals("PairingInvite([REDACTED])", result.toString())
        assertFalse(wire.contains(token)); assertFalse(wire.contains(secret)) // Encoding is NOT encryption.
        assertEquals(wire, result.encode())
    }
    @Test fun strictVersionBoundsCanonicalEncodingAndNoTrailingData() {
        val wire = PairingInvite("https://pager.example.com", token, secret, "A").encode()
        rejects { PairingInvite.decode(wire.replace("FIELDPAIR1", "FIELDPAIR2")) }
        rejects { PairingInvite.decode(wire + "=") }
        rejects { PairingInvite.decode("https://example.com") }
        rejects { PairingInvite.decode("FIELDPAIR1:" + "A".repeat(1100)) }
        for (n in 0 until wire.length) rejects { PairingInvite.decode(wire.take(n)) }
        val bytes = Base64.getUrlDecoder().decode(wire.removePrefix("FIELDPAIR1:")) + byteArrayOf(0)
        rejects { PairingInvite.decode("FIELDPAIR1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)) }
    }
    @Test fun validatesEndpointRoleAndCredentials() {
        rejects { PairingInvite("http://pager.example.com", token, secret, "B") }
        rejects { PairingInvite("https://user:pass@pager.example.com", token, secret, "B") }
        rejects { PairingInvite("https://pager.example.com/path", token, secret, "B") }
        rejects { PairingInvite("https://pager.example.com", "short", secret, "B") }
        rejects { PairingInvite("https://pager.example.com", token, "short", "B") }
        rejects { PairingInvite("https://pager.example.com", token, secret, "C") }
    }
}
