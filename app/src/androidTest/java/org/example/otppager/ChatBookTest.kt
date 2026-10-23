package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatBookTest {
    private class Rooms : RelayTransport {
        val packets = mutableMapOf<String, MutableList<String>>()
        override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            val rows = packets.getOrPut(room) { mutableListOf() }
            if (method == "POST") {
                val wire = JSONObject(body!!).getString("packet")
                if (wire !in rows) rows.add(wire)
                return "{}"
            }
            return JSONObject().put("protocol", 2).put("epoch", "test")
                .put("messages", JSONArray(rows.mapIndexedNotNull { i, wire ->
                    if (i + 1L > cursor) JSONObject().put("seq", i + 1L).put("packet", wire) else null
                }.take(50))).toString()
        }
    }
    @Test fun separateFriendsKeepKeysTrafficUnreadAndForgetIsolated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val index = "friends-test"; val legacy = "friends-legacy"
        var book: ChatBook? = null
        val peers = listOf(SecureStore(context, "friend-bob"), SecureStore(context, "friend-casey"))
        val relay = Rooms()
        try {
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
            SecureStore(context, legacy).forget(); SecureStore(context, legacy).sibling("pads").forget()
            peers.forEach { it.forget(); it.sibling("pads").forget() }
            book = ChatBook(context, index, legacy)
            val bobId = book.active().id; book.rename("Bob")
            val a = book.engine(relay = relay)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val b = StationEngine(peers[0], relay)
            b.configure("https://pager.example.com", "t".repeat(43), a.state!!.getString("secret"), "B")
            var bundle = a.generatePad(); b.installPad(bundle); bundle.fill(0)
            var pad = a.view().padCheck!!; a.confirmPad(pad.takeLast(8)); b.confirmPad(pad.takeLast(8))
            a.sync(); b.sync(); a.sync()
            val casey = book.add("Casey")
            val c = book.engine(casey.id, relay)
            assertEquals(a.state!!.getString("endpoint"), c.state!!.getString("endpoint"))
            assertEquals(a.state!!.getString("token"), c.state!!.getString("token"))
            assertNotEquals(a.state!!.getString("secret"), c.state!!.getString("secret"))
            assertNull(c.view().padCheck); assertNull(c.view().ownDevice)
            val d = StationEngine(peers[1], relay)
            d.configure("https://pager.example.com", "t".repeat(43), c.state!!.getString("secret"), "B")
            bundle = c.generatePad(); d.installPad(bundle); bundle.fill(0)
            pad = c.view().padCheck!!; c.confirmPad(pad.takeLast(8)); d.confirmPad(pad.takeLast(8))
            c.sync(); d.sync(); c.sync()
            b.send("FROM BOB"); b.sync(); d.send("FROM CASEY"); d.sync()
            book.select(casey.id)
            book.syncAnother(relay) // Bob syncs even while Casey is selected.
            assertEquals(1, book.unreadElsewhere())
            val loadedBob = book.engine(bobId, relay)
            assertEquals("FROM BOB", loadedBob.view().messages.single().text)
            c.sync(); assertEquals("FROM CASEY", c.view().messages.single().text)
            // A relay moving Bob's encrypted packet to Casey's mailbox cannot make it readable.
            val bobPacket = b.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet")
            val master = Protocol.master(c.state!!.getString("secret"))
            val room = try { Protocol.room(master) } finally { master.fill(0) }
            relay.packets.getValue(room).add(bobPacket); c.sync()
            assertEquals(listOf("FROM CASEY"), c.view().messages.map { it.text })
            book = ChatBook(context, index, legacy)
            assertEquals(casey.id, book.active().id); assertEquals(listOf("Bob", "Casey"), book.chats().map { it.name })
            val before = SecureStore(context, bobId).load()!!.toString()
            book.forgetActive()
            assertNull(book.engine().state)
            assertEquals(before, SecureStore(context, bobId).load()!!.toString())
            assertEquals(loadedBob.view().padRemaining, book.engine(bobId).view().padRemaining)
        } finally {
            book?.chats()?.forEach { SecureStore(context, it.id).forget(); SecureStore(context, it.id).sibling("pads").forget() }
            peers.forEach { it.forget(); it.sibling("pads").forget() }
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
        }
    }
    @Test fun legacyMigrationAndCrossChatPadAndSecretReuseFailClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val index = "claim-test"; val legacy = "claim-legacy"
        val old = SecureStore(context, legacy)
        var book: ChatBook? = null
        try {
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
            old.forget(); old.sibling("pads").forget()
            val previous = StationEngine(old)
            val secret = Protocol.randomSecret()
            previous.configure("https://pager.example.com", "t".repeat(43), secret, "A")
            val bundle = previous.generatePad()
            val counter = previous.state!!.getLong("counter")
            book = ChatBook(context, index, legacy)
            assertEquals(counter, book.engine().state!!.getLong("counter"))
            assertEquals(previous.view().padCheck, book.engine().view().padCheck)
            val other = book.add("Different friend", copyServerFrom = null)
            val target = book.engine(other.id)
            try { target.configure("https://pager.example.com", "t".repeat(43), secret, "B"); fail("Shared a mailbox secret across chats") } catch (_: IllegalArgumentException) {}
            assertNull(target.state)
            val fresh = Protocol.randomSecret()
            target.configure("https://pager.example.com", "t".repeat(43), fresh, "A")
            val master = Protocol.master(fresh)
            try { PadProtocol.binding(master).copyInto(bundle, 8) } finally { master.fill(0) }
            try { target.installPad(bundle); fail("Rewrapped the same pad into another chat") } catch (_: IllegalArgumentException) {}
            assertNull(target.view().padCheck); bundle.fill(0)
            book.forgetActive()
            try { book.engine().configure("https://pager.example.com", "t".repeat(43), secret, "A"); fail("Forgotten secret reused with reset counters") } catch (_: IllegalArgumentException) {}
            assertEquals(0L, old.sibling("pads").load()!!.getJSONObject("pads").getLong(previous.view().padCheck!!))
        } finally {
            book?.chats()?.forEach { SecureStore(context, it.id).forget(); SecureStore(context, it.id).sibling("pads").forget() }
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
        }
    }
    @Test fun chatBoundsNamesAndDamagedIndexNeverSilentlyReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val index = "index-test"; val legacy = "index-legacy"
        var book: ChatBook? = null
        try {
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
            SecureStore(context, legacy).forget(); SecureStore(context, legacy).sibling("pads").forget()
            book = ChatBook(context, index, legacy)
            book.rename("Alice")
            try { book.add("ALICE", null); fail("Ambiguous duplicate name") } catch (_: IllegalArgumentException) {}
            try { book.add("Alice\u202e", null); fail("Bidi name accepted") } catch (_: IllegalArgumentException) {}
            for (i in 2..ChatBook.MAX_CHATS) book.add("Friend $i", null)
            try { book.add("Overflow", null); fail("Unbounded chat count") } catch (_: IllegalStateException) {}
            val saved = SecureStore(context, index).load()!!
            SecureStore(context, index).save(JSONObject(saved.toString()).put("active", "unknown-chat"))
            try { ChatBook(context, index, legacy); fail("Damaged index silently reset") } catch (_: IllegalStateException) {}
            assertEquals("unknown-chat", SecureStore(context, index).load()!!.getString("active"))
        } finally {
            book?.chats()?.forEach { SecureStore(context, it.id).forget(); SecureStore(context, it.id).sibling("pads").forget() }
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
        }
    }
}
