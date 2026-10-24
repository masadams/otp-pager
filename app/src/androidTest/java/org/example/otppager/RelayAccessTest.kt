package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayAccessTest {
    private class ScopedRelay : RelayTransport {
        val claims = mutableMapOf<String, String>()
        val packets = mutableListOf<String>()
        val trafficTokens = mutableListOf<String>()
        var revoked = false
        var legacy = false
        var loseAck = true
        var epoch = "scoped-test"
        var scopes = 0
        override fun scope(endpoint: String, room: String, bootstrap: String, capability: String): Boolean {
            scopes++; assertEquals("t".repeat(43), bootstrap)
            if (legacy) return false
            if (revoked) throw RelayHttpException(410)
            val existing = claims[room]
            if (existing != null && existing != capability) throw RelayHttpException(403)
            claims[room] = capability; return true
        }
        override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            trafficTokens.add(token)
            if (legacy) {
                if (token != "t".repeat(43)) throw RelayHttpException(401)
            } else {
                if (claims[room] != token) throw RelayHttpException(401)
                if (revoked) throw RelayHttpException(410)
            }
            if (method == "DELETE") { revoked = true; packets.clear(); return "{}" }
            if (method == "GET") return JSONObject().put("protocol", 2).put("access", if (legacy) "shared" else "chat").put("epoch", epoch)
                .put("messages", JSONArray(packets.mapIndexedNotNull { i, wire -> if (i + 1 > cursor) JSONObject().put("seq", i + 1).put("packet", wire) else null })).toString()
            val wire = JSONObject(body!!).getString("packet")
            if (wire !in packets) packets.add(wire)
            if (loseAck) { loseAck = false; throw java.io.IOException("lost acknowledgement") }
            return "{}"
        }
    }
    @Test fun protectedChatRetriesLostVolumeAndRevocationPreserveLocalState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sa = SecureStore(context, "scope-a"); val sb = SecureStore(context, "scope-b")
        val stores = listOf(sa, sb); stores.forEach { it.forget(); it.sibling("pads").forget() }
        try {
            val relay = ScopedRelay(); val secret = Protocol.randomSecret()
            var a = StationEngine(sa, relay); var b = StationEngine(sb, relay)
            a.configure("https://pager.example.com", "t".repeat(43), secret, "A")
            b.configure("https://pager.example.com", "t".repeat(43), secret, "B")
            val bundle = a.generatePad(); b.installPad(bundle); bundle.fill(0)
            for (station in listOf(a, b)) station.confirmPad(station.view().padCheck!!.takeLast(8))
            try { a.sync(); fail("Lost ack accepted") } catch (_: java.io.IOException) {}
            assertTrue(a.view().relayScoped)
            val ready = a.state!!.getString("readyPacket")
            a = StationEngine(sa, relay); a.sync(); b.sync(); a.sync()
            assertEquals(2, relay.scopes)
            a.send("PROTECTED TRAFFIC"); a.sync(); b.sync()
            val wire = a.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet")
            val remaining = a.view().padRemaining
            relay.claims.clear(); relay.packets.clear(); relay.epoch = "replacement-volume"
            assertTrue(a.sync().contains("STORAGE CHANGED")); a.sync()
            assertTrue(wire in relay.packets); assertEquals(remaining, a.view().padRemaining)
            assertTrue(ready.isNotEmpty())
            a.revokeRelayAccess(); assertTrue(a.view().relayRevoked)
            assertFalse(a.view().canSend); assertEquals("PROTECTED TRAFFIC", a.view().messages.single().text)
            b = StationEngine(sb, relay)
            try { b.sync(); fail("Revocation ignored") } catch (e: RelayHttpException) { assertEquals(410, e.status) }
            assertTrue(b.view().relayRevoked); assertFalse(b.view().canSend)
            val before = b.state!!.getLong("counter")
            try { b.send("blocked"); fail("Revoked chat allocated a slot") } catch (_: IllegalStateException) {}
            assertEquals(before, b.state!!.getLong("counter"))
            assertTrue(relay.trafficTokens.none { it == "t".repeat(43) })
        } finally { stores.forEach { it.forget(); it.sibling("pads").forget() } }
    }
    @Test fun oldRelayWorksExplicitlyButProtectedChatNeverDowngrades() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, "scope-legacy"); store.forget(); store.sibling("pads").forget()
        try {
            val relay = ScopedRelay().apply { legacy = true; loseAck = false }
            var station = StationEngine(store, relay)
            station.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            station.generatePad().fill(0); station.confirmPad(station.view().padCheck!!.takeLast(8)); station.sync()
            assertFalse(station.view().relayScoped)
            relay.legacy = false; station.sync(); assertTrue(station.view().relayScoped)
            val count = relay.trafficTokens.size; val counter = station.state!!.getLong("counter")
            relay.legacy = true; station = StationEngine(store, relay)
            try { station.sync(); fail("Protected chat downgraded") } catch (_: IllegalStateException) {}
            assertTrue(station.view().relayScoped); assertEquals(counter, station.state!!.getLong("counter"))
            assertTrue(relay.trafficTokens.drop(count).none { it == "t".repeat(43) })
        } finally { store.forget(); store.sibling("pads").forget() }
    }
}
