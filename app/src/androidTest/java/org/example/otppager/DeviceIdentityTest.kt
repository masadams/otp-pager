package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceIdentityTest {
    private class Mailbox : RelayTransport {
        val packets = mutableListOf<String>()
        var loseAck = false
        override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            if (method == "GET") return JSONObject().put("protocol", 2).put("epoch", "identity-test")
                .put("messages", JSONArray(packets.mapIndexedNotNull { i, wire -> if (i + 1 > cursor) JSONObject().put("seq", i + 1).put("packet", wire) else null }.take(50))).toString()
            val wire = JSONObject(body!!).getString("packet")
            if (wire !in packets) packets.add(wire)
            if (loseAck) { loseAck = false; throw java.io.IOException("Lost acknowledgement") }
            return "{}"
        }
    }
    @Test fun offlineIdentityRetryCloneForgeryDowngradeAndMissingKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sa = SecureStore(context, "device-a"); val sb = SecureStore(context, "device-b")
        val stores = listOf(sa, sb)
        stores.forEach { it.forget(); it.sibling("pads").forget() }
        try {
            val relay = Mailbox(); val secret = Protocol.randomSecret()
            var a = StationEngine(sa, relay); var b = StationEngine(sb, relay)
            a.configure("https://pager.example.com", "t".repeat(43), secret, "A")
            b.configure("https://pager.example.com", "t".repeat(43), secret, "B")
            val bundle = a.generatePad(); b.installPad(bundle)
            val master = Protocol.master(secret); val pad = PadProtocol.material(bundle, PadProtocol.binding(master))
            bundle.fill(0); master.fill(0)
            val check = a.view().padCheck!!
            a.confirmPad(check.takeLast(8)); b.confirmPad(check.takeLast(8)); a.sync(); b.sync(); a.sync()
            a.send("OLD HISTORY"); a.sync(); b.sync(); a.sync()
            val counter = a.state!!.getLong("counter"); val remaining = a.view().padRemaining
            a.prepareIdentity(); b.prepareIdentity()
            assertEquals(counter, a.state!!.getLong("counter")); assertEquals(remaining, a.view().padRemaining)
            assertFalse(a.view().canSend)
            val ac = a.view().ownDevice!!; val bc = b.view().ownDevice!!
            try { a.pinDevice(bc, "wrong"); fail("Wrong fingerprint accepted") } catch (_: IllegalArgumentException) {}
            try { a.pinDevice(ac, IdentityProtocol.fingerprint(ac).takeLast(8)); fail("Own key pinned") } catch (_: IllegalArgumentException) {}
            a.pinDevice(bc, IdentityProtocol.fingerprint(bc).takeLast(8))
            // B need not be online or have completed pinning when A sends.
            a.send("OFFLINE SIGNED MESSAGE")
            relay.loseAck = true
            try { a.sync(); fail("Expected lost ack") } catch (_: java.io.IOException) {}
            val original = relay.packets.last()
            a = StationEngine(sa, relay); a.sync(); assertEquals(1, relay.packets.count { it == original })
            b.sync(); assertEquals(1, b.view().messages.size) // Held until fingerprint exchange finishes.
            b.pinDevice(ac, IdentityProtocol.fingerprint(ac).takeLast(8)); b = StationEngine(sb, relay); b.sync()
            assertTrue(b.view().messages.last().deviceVerified); assertFalse(b.view().messages.first().deviceVerified)
            assertEquals("OFFLINE SIGNED MESSAGE", b.view().messages.last().text)
            a.sync(); assertEquals("delivered", a.view().messages.last().status)
            val high = b.state!!.getLong("high"); val slots = b.view().padRemaining
            // A cloned pad can create valid HMAC frames but cannot create the device signature.
            relay.packets.add(PadProtocol.seal(pad, 'A', 100, "{\"kind\":\"text\",\"text\":\"UNSIGNED CLONE\"}").second)
            val raw = PadProtocol.open(pad, original, 'A')
            relay.packets.add(PadProtocol.seal(pad, 'A', 101, raw.payload).second) // Signature bound to original counter.
            relay.packets.add(original) // Replay valid device frame.
            b.sync(); assertEquals(2, b.view().messages.size); assertEquals(high, b.state!!.getLong("high")); assertEquals(slots, b.view().padRemaining)
            val impostor = DeviceIdentity("identity-test-impostor")
            try {
                val card = impostor.card(true)
                try { a.pinDevice(card, IdentityProtocol.fingerprint(card).takeLast(8)); fail("Pinned identity replaced") } catch (_: IllegalStateException) {}
            } finally { impostor.forget() }
            sa.identity().forget()
            try { StationEngine(sa, relay); fail("Missing key silently replaced") } catch (_: IllegalStateException) {}
            pad.fill(0)
        } finally { stores.forEach { it.forget(); it.sibling("pads").forget() } }
    }
}
