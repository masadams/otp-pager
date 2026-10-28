package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Pipeline item 7: groups as labels on pairwise chats, fanned out one pad copy per member. */
@RunWith(AndroidJUnit4::class)
class GroupChatTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private class Relay : RelayTransport {
        val rooms = mutableMapOf<String, MutableList<String>>()
        @Synchronized override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            val list = rooms.getOrPut(room) { mutableListOf() }
            if (method == "POST") { list.add(JSONObject(body!!).getString("packet")); return "{}" }
            return JSONObject().put("protocol", 2).put("epoch", "groups").put("messages", JSONArray(list.mapIndexedNotNull { i, p ->
                if (i + 1 > cursor) JSONObject().put("seq", i + 1).put("packet", p) else null }.take(50))).toString()
        }
    }
    private fun pair(a: StationEngine, b: StationEngine) {
        val pool = Executors.newFixedThreadPool(2)
        val ain = PipedInputStream(65536); val bout = PipedOutputStream(ain)
        val bin = PipedInputStream(65536); val aout = PipedOutputStream(bin)
        try {
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "B")
            val ready = CountDownLatch(2)
            val review: (String, String) -> Unit = { _, _ -> ready.countDown(); check(ready.await(20, TimeUnit.SECONDS)) }
            val af = pool.submit { CableProtocol.run(true, ain, aout, a, review) {} }
            val bf = pool.submit { CableProtocol.run(false, bin, bout, b, review) {} }
            af.get(60, TimeUnit.SECONDS); bf.get(60, TimeUnit.SECONDS)
        } finally { listOf(ain, bin, aout, bout).forEach { try { it.close() } catch (_: Exception) {} }; pool.shutdownNow() }
    }

    @Test fun groupMessagesFanOutPerMemberAndMergeIntoOneThreadRow() {
        val index = "grp-" + UUID.randomUUID().toString().take(8); val first = root()
        val rb = root(); val rc = root(); val relay = Relay()
        val roots = mutableListOf(first, rb, rc)
        try {
            val book = ChatBook(context, index, first); book.rename("Bob")
            val carol = book.add("Carol", null); roots.add(carol.id)
            val b = StationEngine(SecureStore(context, rb), relay); val c = StationEngine(SecureStore(context, rc), relay)
            pair(book.engine(first, relay), b); pair(book.engine(carol.id, relay), c)

            // Creating a group announces it to each member: one pad slot per member.
            val before = b.view().padRemaining!!
            val group = book.createGroup("Hike", listOf(first, carol.id))
            assertEquals(listOf("Me", "Bob", "Carol"), group.members)
            listOf(first, carol.id).forEach { book.engine(it, relay).sync() }
            b.sync(); c.sync()
            for (peer in listOf(b, c)) {
                val g = peer.groups().single(); assertEquals(group.id, g.id); assertEquals("Hike", g.name)
                assertEquals(group.id, peer.view().messages.last().group)
            }

            // One group send becomes two independent ciphertexts, one per member, sharing a group message id.
            val missed = book.sendGroup(group.id, "Meet at 9")
            assertTrue(missed.isEmpty())
            val packets = listOf(first, carol.id).map { book.engine(it, relay).state!!.getJSONArray("outbox").let { o -> o.getJSONObject(o.length() - 1).getString("packet") } }
            assertNotEquals(packets[0], packets[1])
            listOf(first, carol.id).forEach { book.engine(it, relay).sync() }
            b.sync(); c.sync()
            for (peer in listOf(b, c)) {
                val m = peer.view().messages.last()
                assertEquals("Meet at 9", m.text); assertEquals(group.id, m.group); assertNotNull(m.groupMessage)
            }
            assertEquals(b.view().messages.last().groupMessage, c.view().messages.last().groupMessage)
            assertTrue(before > b.view().padRemaining!!)

            // The sender's thread shows one outgoing row; a member's reply appears under their chat name.
            b.sendGroup("Bring water", group.id, PadProtocol.hex(ByteArray(16) { 5 }))
            b.sync(); book.engine(first, relay).sync(); listOf(first, carol.id).forEach { book.engine(it, relay).sync() }
            val lines = book.groupLines(group.id)
            assertEquals(1, lines.count { !it.incoming && it.text == "Meet at 9" })
            assertTrue(lines.any { it.incoming && it.from == "Bob" && it.text == "Bring water" })
            assertTrue(lines.none { it.text.startsWith("Started the group") && it.incoming })

            // A tag for a group this chat is not linked to stays an ordinary pairwise message.
            c.leaveGroup(group.id)
            book.sendGroup(group.id, "Only linked chats see this in the group"); listOf(first, carol.id).forEach { book.engine(it, relay).sync() }
            c.sync()
            assertNull(c.view().messages.last().group)
            assertEquals("Only linked chats see this in the group", c.view().messages.last().text)
        } finally {
            for (id in roots) { listOf(id, "$id-draft", "$id-pads").forEach { SecureStore(context, it).forget() }; PadMaterial.deleteAll(context.noBackupFilesDir, id) }
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
        }
    }
}
