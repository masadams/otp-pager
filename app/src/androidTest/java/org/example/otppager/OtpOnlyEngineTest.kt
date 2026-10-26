package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OtpOnlyEngineTest {
    @Test fun missingPadRejectsNewTextAndDraftAcrossRestartWithoutChangingSavedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, "otp-only-test")
        val draftStore = store.sibling("draft")
        store.forget(); draftStore.forget(); store.sibling("pads").forget()
        try {
            var station = StationEngine(store)
            station.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val master = Protocol.master(station.state!!.getString("secret"))
            val room = try { Protocol.room(master) } finally { master.fill(0) }
            val draftId = UUID.randomUUID().toString()
            val draft = ChatDraft(draftStore)
            draft.save(room, draftId, "Keep this unsent draft")
            repeat(2) {
                val state = station.state.toString()
                val vaultBytes = File(context.noBackupFilesDir, "otp-only-test.bin").readBytes()
                assertFalse(station.view().canSend)
                for (send in listOf<() -> Unit>(
                    { station.send("Must not use AES") },
                    { station.sendDraft("Keep this unsent draft", draftId) }
                )) {
                    try { send(); fail("No-pad engine accepted new message") }
                    catch (e: IllegalStateException) { assertTrue(e.message!!.contains("Physically pair")) }
                    assertEquals(state, station.state.toString())
                    assertArrayEquals(vaultBytes, File(context.noBackupFilesDir, "otp-only-test.bin").readBytes())
                    assertEquals(0L, station.state!!.getLong("counter"))
                    assertEquals(0, station.state!!.getJSONArray("outbox").length())
                    assertFalse(station.state!!.has("lastSentDraft"))
                    assertEquals("Keep this unsent draft", draft.read(room)!!.text)
                }
                station = StationEngine(store)
            }
        } finally { draftStore.forget(); store.forget(); store.sibling("pads").forget() }
    }
}
