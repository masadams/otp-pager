package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftTest {
    @Test fun encryptedDraftsExpireStayInTheirRoomAndNeverConsumePad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val station = SecureStore(context, "draft-test"); val small = station.sibling("draft")
        station.forget(); station.sibling("pads").forget(); small.forget()
        try {
            val engine = StationEngine(station)
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            engine.generatePad().fill(0)
            val master = Protocol.master(engine.state!!.getString("secret"))
            val room = try { Protocol.room(master) } finally { master.fill(0) }
            val counter = engine.state!!.getLong("counter"); val pad = engine.state!!.getString("padKeys")
            val id = UUID.randomUUID().toString(); val draft = ChatDraft(small)
            draft.save(room, id, "Unsent private draft", now = 1000)
            assertEquals("Unsent private draft", draft.read(room, now = 2000)!!.text)
            assertFalse(java.io.File(context.noBackupFilesDir, "draft-test-draft.bin").readText(Charsets.ISO_8859_1).contains("Unsent private draft"))
            assertEquals(counter, StationEngine(station).state!!.getLong("counter"))
            assertEquals(pad, StationEngine(station).state!!.getString("padKeys"))
            // Saving the same revision on backgrounding does not extend its original lifetime.
            draft.save(room, id, "Unsent private draft", now = 9000)
            assertNull(draft.read(room, now = 1000 + ChatDraft.RETENTION))
            draft.save(room, id, "Wrong friend", now = 1000)
            assertNull(draft.read("0".repeat(64), now = 1001))
            draft.save(room, id, "Clock moved backwards", now = 400000)
            assertNull(draft.read(room, now = 0))
            try { draft.save(room, id, "x".repeat(ChatDraft.MAX_BYTES + 1)); fail("Oversized draft saved") } catch (_: IllegalArgumentException) {}
            draft.save(room, id, "Damaged draft fixture")
            java.io.File(context.noBackupFilesDir, "draft-test-draft.bin").writeBytes(byteArrayOf(1, 2, 3))
            try { draft.read(room); fail("Damaged draft opened") } catch (_: Exception) {}
            assertEquals(counter, StationEngine(station).state!!.getLong("counter"))
            draft.clear(); assertNull(draft.read(room))
        } finally { small.forget(); station.forget(); station.sibling("pads").forget() }
    }
    @Test fun acceptedDraftSuppressionCommitsWithMessageAndDoesNotDropANewerDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, "draft-send-test"); val draftStore = store.sibling("draft")
        store.forget(); store.sibling("pads").forget(); draftStore.forget()
        try {
            val engine = StationEngine(store)
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            engine.generatePad().fill(0); engine.confirmPad(engine.view().padCheck!!.takeLast(8))
            store.save(JSONObject(engine.state.toString()).put("peerReady", true))
            val ready = StationEngine(store); val master = Protocol.master(ready.state!!.getString("secret"))
            val room = try { Protocol.room(master) } finally { master.fill(0) }
            val sentId = UUID.randomUUID().toString(); val draft = ChatDraft(draftStore)
            draft.save(room, sentId, "Send once")
            ready.sendDraft("Send once", sentId)
            val restarted = StationEngine(store)
            assertEquals(sentId, restarted.state!!.getString("lastSentDraft"))
            // Simulate missed UI completion and failed/delayed cleanup, followed by an onStop save.
            draft.save(room, sentId, "Send once")
            assertNull(draft.read(room, restarted.state!!.getString("lastSentDraft")))
            val nextId = UUID.randomUUID().toString()
            draft.save(room, nextId, "Still typing")
            assertEquals("Still typing", draft.read(room, sentId)!!.text)
            assertEquals(1, restarted.view().messages.count { !it.incoming })
            try { restarted.sendDraft("x".repeat(2049), nextId); fail("Oversized message accepted") } catch (_: IllegalArgumentException) {}
            assertEquals(sentId, StationEngine(store).state!!.getString("lastSentDraft"))
            assertEquals("Still typing", draft.read(room, sentId)!!.text)
        } finally { draftStore.forget(); store.forget(); store.sibling("pads").forget() }
    }
}
