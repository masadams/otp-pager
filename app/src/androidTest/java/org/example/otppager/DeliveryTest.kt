package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeliveryTest {
    private class FlakyRelay : RelayTransport {
        val packets = mutableListOf<Pair<Long, String>>()
        var seq = 0L
        var epoch = "test-epoch"
        var loseFirstAck = true
        override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            if (method == "GET") return JSONObject().put("protocol", 2).put("epoch", epoch)
                .put("messages", JSONArray(packets.filter { it.first > cursor }.take(50).map { JSONObject().put("seq", it.first).put("packet", it.second) })).toString()
            val wire = JSONObject(body!!).getString("packet")
            val existing = packets.firstOrNull { it.second == wire }
            val n = existing?.first ?: (++seq).also { packets.add(it to wire) }
            if (loseFirstAck) { loseFirstAck = false; throw java.io.IOException("Lost response after server commit") }
            return JSONObject().put("seq", n).toString()
        }
    }
    @Test fun offlineRetryRestartReceiptAndReplayRemainConsistent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val aStore = SecureStore(context, "test-a"); val bStore = SecureStore(context, "test-b")
        aStore.forget(); bStore.forget()
        val relay = FlakyRelay()
        try {
            val secret = Protocol.randomSecret()
            var a = StationEngine(aStore, relay); var b = StationEngine(bStore, relay)
            a.configure("https://pager.example.com", "t".repeat(43), secret, "A")
            b.configure("https://pager.example.com", "t".repeat(43), secret, "B")
            a.send("MEET AT 2200")
            val original = a.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet")
            try { a.sync(); fail("Lost acknowledgement was ignored") } catch (_: java.io.IOException) {}
            assertEquals(1, relay.packets.size)
            a = StationEngine(aStore, relay)
            assertEquals(original, a.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet"))
            a.sync(); assertEquals(1, relay.packets.size) // Same wire bytes, no duplicate.
            assertEquals("relayed", a.view().messages.single().status)
            a.sync() // First read of our own newly uploaded packet advances the transport cursor.
            val stableVault = java.io.File(context.noBackupFilesDir, "test-a.bin").readBytes()
            val stableView = a.view()
            a.sync()
            assertArrayEquals(stableVault, java.io.File(context.noBackupFilesDir, "test-a.bin").readBytes())
            assertSame(stableView, a.view()) // Idle polling neither rewrites the vault nor decrypts history again.
            b.sync(); assertEquals("MEET AT 2200", b.view().messages.single().text)
            b = StationEngine(bStore, relay)
            a.sync(); a = StationEngine(aStore, relay)
            assertEquals("delivered", a.view().messages.single().status)
            relay.packets.add(++relay.seq to original) // Malicious relay re-enqueues an old authenticated frame.
            b.sync(); assertEquals(1, b.view().messages.size)
            b.clearHistory(); b = StationEngine(bStore, relay)
            relay.epoch = "replacement-volume"; relay.packets.clear(); relay.seq = 0
            b.sync() // Epoch change resets only transport cursor.
            relay.packets.add(++relay.seq to original)
            b.sync(); assertTrue(b.view().messages.isEmpty())
            assertEquals(1L, b.state!!.getLong("high"))
        } finally { aStore.forget(); bStore.forget() }
    }
}
