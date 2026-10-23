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
    @Test fun padRetryBurnBeforeQueueUnreadAndRetirement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val aStore = SecureStore(context, "pad-test-a"); val bStore = SecureStore(context, "pad-test-b")
        aStore.forget(); bStore.forget(); aStore.sibling("pads").forget(); bStore.sibling("pads").forget()
        val relay = FlakyRelay()
        try {
            val secret = Protocol.randomSecret()
            var a = StationEngine(aStore, relay); var b = StationEngine(bStore, relay)
            a.configure("https://pager.example.com", "t".repeat(43), secret, "A")
            b.configure("https://pager.example.com", "t".repeat(43), secret, "B")
            val bundle = a.generatePad(); b.installPad(bundle)
            val id = a.state!!.getString("padId")
            relay.loseFirstAck = false
            a.confirmPad(id.takeLast(8)); b.confirmPad(id.takeLast(8))
            a.sync(); b.sync(); a.sync()
            assertTrue(a.view().canSend); assertTrue(b.view().canSend)
            relay.loseFirstAck = true
            // Simulate death after a durable reservation, before queue encryption/commit.
            PadLedger(aStore.sibling("pads")).reserve(id)
            a = StationEngine(aStore, relay); a.send("PAD TRAFFIC")
            assertEquals(3L, a.state!!.getLong("counter"))
            assertEquals(124, a.view().padRemaining)
            val original = a.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet")
            try { a.sync(); fail("Expected lost response") } catch (_: java.io.IOException) {}
            a = StationEngine(aStore, relay); a.sync()
            assertEquals(3, relay.packets.size); assertEquals(original, relay.packets.last().second)
            b.sync(); assertEquals("PAD TRAFFIC", b.view().messages.single().text)
            assertEquals(1, b.view().unread); assertEquals(125, b.view().padRemaining)
            b = StationEngine(bStore, relay); assertEquals(1, b.view().unread)
            b.markRead(); assertEquals(0, b.view().unread)
            a.sync(); assertEquals("delivered", a.view().messages.single().status)
            relay.packets.add(++relay.seq to original); b.sync(); assertEquals(1, b.view().messages.size)
            assertEquals(125, b.view().padRemaining) // Replay does not consume another receipt slot.
            b.clearHistory(); b = StationEngine(bStore, relay); assertEquals(125, b.view().padRemaining)
            // Exhaust all remaining A slots without emitting a ciphertext. Never fall back to AES.
            val ledger = PadLedger(aStore.sibling("pads"))
            while (ledger.high(id) < PadProtocol.SLOTS) ledger.reserve(id)
            a = StationEngine(aStore, relay)
            try { a.send("must fail"); fail("Exhausted pad sent a message") } catch (_: IllegalStateException) {}
            assertEquals(0, a.view().padRemaining)
            b.send("Still receive when exhausted"); b.sync(); a.sync()
            assertTrue(a.view().messages.any { it.incoming && it.text == "Still receive when exhausted" })
            assertEquals(0, a.view().padRemaining)
            val savedLedger = aStore.sibling("pads").load()!!
            val laggingLedger = JSONObject(savedLedger.toString())
            laggingLedger.getJSONObject("pads").put(id, 0)
            aStore.sibling("pads").save(laggingLedger)
            try { StationEngine(aStore, relay); fail("Lagging ledger was accepted") } catch (_: IllegalStateException) {}
            aStore.sibling("pads").save(savedLedger)
            b.forget(); b.configure("https://pager.example.com", "t".repeat(43), secret, "B")
            try { b.installPad(bundle); fail("Re-imported retired pad") } catch (_: IllegalArgumentException) {}
            bundle.fill(0)
        } finally { aStore.forget(); bStore.forget(); aStore.sibling("pads").forget(); bStore.sibling("pads").forget() }
    }

    @Test fun padVerificationGateExactReadinessRetryAndMigration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val aStore = SecureStore(context, "ready-a"); val bStore = SecureStore(context, "ready-b")
        for (store in listOf(aStore, bStore)) { store.forget(); store.sibling("pads").forget() }
        val relay = FlakyRelay()
        try {
            val secret = Protocol.randomSecret()
            var a = StationEngine(aStore, relay); var b = StationEngine(bStore, relay)
            a.configure("https://pager.example.com", "t".repeat(43), secret, "A")
            b.configure("https://pager.example.com", "t".repeat(43), secret, "B")
            val bundle = a.generatePad(); b.installPad(bundle); bundle.fill(0)
            assertFalse(a.view().canSend)
            try { a.send("blocked"); fail("Unverified pad sent") } catch (_: IllegalStateException) {}
            val id = a.view().padCheck!!
            try { a.confirmPad("wrong"); fail("Wrong code accepted") } catch (_: IllegalArgumentException) {}
            assertEquals(127, a.view().padRemaining)
            a.confirmPad(id.takeLast(8)); assertFalse(a.view().canSend)
            val ready = a.state!!.getString("readyPacket")
            try { a.sync(); fail("Expected lost ready acknowledgement") } catch (_: java.io.IOException) {}
            a = StationEngine(aStore, relay); a.sync()
            assertEquals(1, relay.packets.size); assertEquals(ready, relay.packets.single().second)
            b.sync(); assertTrue(b.view().peerReady); assertFalse(b.view().canSend)
            b.confirmPad(id.takeLast(8)); b.sync(); a.sync()
            assertTrue(a.view().canSend); assertTrue(b.view().canSend)
            assertEquals(126, a.view().padRemaining); assertEquals(126, b.view().padRemaining)
            a.confirmPad(id.takeLast(8)); a.sync()
            assertEquals(126, a.view().padRemaining); assertEquals(2, relay.packets.size)
            assertEquals(ready, a.state!!.getString("readyPacket"))
            // A 0.3 vault migrates without resetting pad reservations or receive replay state.
            val legacy = JSONObject(a.state.toString()).put("version", 2).put("cursor", 200L)
            legacy.remove("padVerified"); legacy.remove("peerReady"); legacy.remove("readyPacket"); legacy.remove("readyId")
            aStore.save(legacy); a = StationEngine(aStore, relay)
            assertEquals(3, a.state!!.getInt("version")); assertEquals(0L, a.state!!.getLong("cursor"))
            assertEquals(126, a.view().padRemaining); assertFalse(a.view().canSend)
            assertEquals(legacy.getLong("high"), a.state!!.getLong("high"))
            // An unauthenticated readiness-looking packet cannot open the gate or reserve a slot.
            bStore.save(JSONObject(b.state.toString()).put("peerReady", false)); b = StationEngine(bStore, relay)
            val forged = java.util.Base64.getDecoder().decode(ready); forged[forged.lastIndex] = (forged.last().toInt() xor 1).toByte()
            relay.packets.add(++relay.seq to java.util.Base64.getEncoder().encodeToString(forged))
            b.sync(); assertEquals(126, b.view().padRemaining); assertFalse(b.view().peerReady); assertFalse(b.view().canSend)
        } finally { for (store in listOf(aStore, bStore)) { store.forget(); store.sibling("pads").forget() } }
    }

}
