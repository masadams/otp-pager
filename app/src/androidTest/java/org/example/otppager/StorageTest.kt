package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun pairingAndOutboxSurviveRestartWithoutPlaintextOnDisk() {
        val store = SecureStore(context); store.forget()
        try {
            var engine = StationEngine(store)
            val secret = Protocol.randomSecret(); val token = "t".repeat(43)
            engine.configure("https://pager.example.com", token, secret, "A")
            legacyQueuedText(store, "TEST MESSAGE SHOULD NEVER BE PLAINTEXT ON DISK")
            engine = StationEngine(store)
            val before = engine.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet")
            engine = StationEngine(SecureStore(context))
            assertEquals(1L, engine.state!!.getLong("counter"))
            assertEquals(before, engine.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet"))
            assertEquals("queued", engine.state!!.getJSONArray("outbox").getJSONObject(0).getString("status"))
            assertEquals("TEST MESSAGE SHOULD NEVER BE PLAINTEXT ON DISK", engine.view().messages[0].text)
            val raw = File(context.noBackupFilesDir, "station-v2.bin").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains(secret)); assertFalse(raw.contains(token)); assertFalse(raw.contains("TEST MESSAGE SHOULD"))
            engine.clearHistory() // Pending message must not be dropped by Clear.
            assertEquals(1, engine.state!!.getJSONArray("outbox").length())
        } finally { store.forget() }
    }
    @Test fun corruptVaultFailsClosedAndReplayWindowSurvivesReopen() {
        val store = SecureStore(context); store.forget()
        try {
            val engine = StationEngine(store)
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val s = JSONObject(engine.state.toString()).put("high", 3L).put("seen", org.json.JSONArray(listOf(1L, 3L)))
            store.save(s)
            val loaded = StationEngine(SecureStore(context)).state!!
            assertEquals(3L, loaded.getLong("high")); assertEquals(2, loaded.getJSONArray("seen").length())
            File(context.noBackupFilesDir, "station-v2.bin").writeBytes(byteArrayOf(1,2,3))
            try { SecureStore(context).load(); fail("Corrupt vault was accepted") } catch (_: IllegalArgumentException) {}
            assertEquals(3, File(context.noBackupFilesDir, "station-v2.bin").length().toInt())
        } finally { store.forget() }
    }
    @Test fun unreadableVaultPathIsNotTreatedAsANewPairing() {
        val store = SecureStore(context, "test-broken"); store.forget()
        val path = File(context.noBackupFilesDir, "test-broken.bin")
        try {
            path.mkdir()
            try { store.load(); fail("Directory was treated as an empty vault") } catch (_: java.io.IOException) {}
            assertTrue(path.isDirectory)
        } finally { store.forget() }
    }

    @Test fun missingVaultWithExistingKeyCannotResetReservations() {
        val store = SecureStore(context, "test-missing"); store.forget()
        try {
            store.save(JSONObject().put("version", 2).put("counter", 12))
            File(context.noBackupFilesDir, "test-missing.bin").delete()
            try { store.load(); fail("Missing vault silently reset") } catch (_: IllegalStateException) {}
        } finally { store.forget() }
    }

}
