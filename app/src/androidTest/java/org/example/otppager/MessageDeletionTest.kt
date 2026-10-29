package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/** Deleted, expired and read-then-expired messages are removed and crypto-erased (vault rekey). */
@RunWith(AndroidJUnit4::class)
class MessageDeletionTest {
    private class Relay : RelayTransport {
        val packets = mutableListOf<Pair<Long, String>>()
        override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            if (method == "GET") return JSONObject().put("protocol", 2).put("epoch", "e")
                .put("messages", JSONArray(packets.filter { it.first > cursor }.map { JSONObject().put("seq", it.first).put("packet", it.second) })).toString()
            val wire = JSONObject(body!!).getString("packet")
            val n = packets.firstOrNull { it.second == wire }?.first ?: (packets.size + 1L).also { packets.add(it to wire) }
            return JSONObject().put("seq", n).toString()
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun keys(id: String) = VaultKeys.all(id, KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList())
    /** Moves every stored message timestamp (and read time) back by [millis], as if time had passed. */
    private fun age(store: SecureStore, millis: Long) {
        val s = checkNotNull(store.load())
        for (box in listOf("inbox", "outbox")) for (i in 0 until s.getJSONArray(box).length()) s.getJSONArray(box).getJSONObject(i).let { e ->
            e.put("time", e.getLong("time") - millis); if (e.has("readAt")) e.put("readAt", e.getLong("readAt") - millis)
            if (e.has("doneAt")) e.put("doneAt", e.getLong("doneAt") - millis)
        }
        store.save(s)
    }

    @Test fun deleteOneRetentionAndAfterReadEraseFromThisPhone() {
        val aStore = SecureStore(context, "del-test-a"); val bStore = SecureStore(context, "del-test-b")
        aStore.forget(); bStore.forget()
        val relay = Relay()
        try {
            val secret = Protocol.randomSecret()
            StationEngine(aStore, relay).configure("https://pager.example.com", "t".repeat(43), secret, "A")
            StationEngine(bStore, relay).configure("https://pager.example.com", "t".repeat(43), secret, "B")
            for (text in listOf("ONE", "TWO", "THREE")) legacyQueuedText(aStore, text)
            val a = StationEngine(aStore, relay)
            val queued = a.view().messages.first().id!!
            try { a.deleteMessage(queued); fail("A queued message was deleted before sending") } catch (_: IllegalStateException) {}
            a.sync()
            var b = StationEngine(bStore, relay); b.sync()
            assertEquals(setOf("ONE", "TWO", "THREE"), b.view().messages.map { it.text }.toSet())

            // Delete one: gone from the view and the vault, and the old snapshot no longer opens.
            val file = File(context.noBackupFilesDir, "del-test-b.bin")
            val before = file.readBytes(); val beforeKeys = keys("del-test-b")
            b.deleteMessage(b.view().messages.first { it.text == "ONE" }.id!!)
            assertEquals(setOf("TWO", "THREE"), b.view().messages.map { it.text }.toSet())
            assertNotEquals(beforeKeys, keys("del-test-b"))
            val current = file.readBytes(); file.writeBytes(before)
            try { SecureStore(context, "del-test-b").load(); fail("Snapshot holding the deleted message reopened") } catch (_: Exception) {}
            file.writeBytes(current)
            b = StationEngine(bStore, relay)
            assertEquals(2, b.state!!.getJSONArray("inbox").length())

            // Retention: only the allowed choices; shortening it erases older messages now.
            try { b.setRetention(1234L); fail("Unknown retention accepted") } catch (_: IllegalArgumentException) {}
            age(bStore, 2 * 3_600_000L); b = StationEngine(bStore, relay)
            b.setRetention(21_600_000L)
            assertEquals(2, b.view().messages.size) // 2 h old, kept for 6 h.
            val sixHourKeys = keys("del-test-b")
            b.setRetention(3_600_000L)
            assertTrue(b.view().messages.isEmpty())
            assertEquals(0, b.state!!.getJSONArray("inbox").length())
            assertNotEquals(sixHourKeys, keys("del-test-b"))
            assertEquals(3_600_000L, StationEngine(bStore, relay).retention)
            assertFalse(b.expire()) // Nothing left to expire: no needless rewrite.

            // After reading: a read message goes about a minute later, on the sender's side once delivered too.
            a.sync() // Collects B's receipts.
            assertTrue(a.view().messages.all { it.status == "delivered" })
            a.setRetention(StationEngine.AFTER_READ)
            assertEquals(3, a.view().messages.size) // Delivered just now: still inside the grace minute.
            age(aStore, 120_000L)
            val a2 = StationEngine(aStore, relay) // Opening runs expiry, as every check does.
            assertTrue(a2.view().messages.isEmpty())
            assertEquals(0, a2.state!!.getJSONArray("outbox").length())
        } finally { aStore.forget(); bStore.forget() }
    }
}
