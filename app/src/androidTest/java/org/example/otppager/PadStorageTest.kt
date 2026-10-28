package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Separate pad storage (docs/PAD_STORAGE.md): small vault, per-slot erasure, restart, cleanup. */
@RunWith(AndroidJUnit4::class)
class PadStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private val relay = object : RelayTransport {
        val packets = mutableListOf<String>()
        override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            if (method == "POST") { packets.add(JSONObject(body!!).getString("packet")); return "{}" }
            return JSONObject().put("cursor", cursor).put("packets", org.json.JSONArray()).toString()
        }
    }

    @Test fun largePadLivesOutsideTheVaultAndEachSendErasesOneSlotKey() {
        val id = root(); val store = SecureStore(context, id)
        try {
            val a = StationEngine(store, relay)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val bundle = a.generatePad(4 * PadProtocol.MATERIAL_BYTES)
            val original = bundle.copyOfRange(40, bundle.size); bundle.fill(0)
            val s = a.state!!; val padId = s.getString("padId")
            assertFalse(s.has("pad")); assertTrue(s.has("padKeys"))
            val vault = File(context.noBackupFilesDir, "$id.bin")
            assertTrue("Vault still carries pad-sized data: ${vault.length()}", vault.length() < 120_000)
            val material = PadMaterial.file(context.noBackupFilesDir, id, padId)
            assertEquals(PadMaterial.HEADER + 2L * PadProtocol.MAX_SLOTS * PadMaterial.RECORD, material.length())
            val before = material.readBytes()
            assertEquals(PadProtocol.MAX_SLOTS, a.view().padRemaining)
            assertArrayEquals(original.copyOf(PadProtocol.STRIDE), a.padSnapshot().copyOf(PadProtocol.STRIDE))

            a.confirmPad(padId.takeLast(8)) // Uses A1 for the readiness packet.
            val ready = a.state!!.getString("readyPacket")
            assertEquals(1L, PadProtocol.counter(ready, 'A', PadProtocol.id(original)))
            val reopened = StationEngine(store, relay)
            val snapshot = reopened.padSnapshot()
            assertTrue("A1 key was not erased", snapshot.copyOf(PadProtocol.STRIDE).all { it == 0.toByte() })
            assertArrayEquals(original.copyOfRange(PadProtocol.STRIDE, 2 * PadProtocol.STRIDE), snapshot.copyOfRange(PadProtocol.STRIDE, 2 * PadProtocol.STRIDE))
            assertArrayEquals("Material file was rewritten", before, material.readBytes())
            assertEquals(PadProtocol.MAX_SLOTS - 1, reopened.view().padRemaining)
            // The peer can still open what was sent: the wire format is unchanged.
            assertTrue(PadProtocol.open(original, ready, 'A').payload.contains("ready"))
            original.fill(0)

            // Unreferenced material may belong to another live owner's staged pairing: never pruned then.
            val stale = PadMaterial.file(context.noBackupFilesDir, id, "0".repeat(32)); stale.writeBytes(ByteArray(8))
            StationEngine(store, relay)
            assertTrue(stale.exists()); assertTrue(material.exists())
            // Missing material fails closed instead of silently losing the pad.
            val saved = material.readBytes(); material.delete()
            try { StationEngine(store, relay); fail("Opened without pad material") } catch (_: IllegalStateException) {}
            material.writeBytes(saved)
            assertEquals(PadProtocol.MAX_SLOTS - 1, StationEngine(store, relay).view().padRemaining)

            StationEngine(store, relay).forget()
            assertTrue("Forget left pad material", PadMaterial.gone(context.noBackupFilesDir, id))
        } finally {
            store.forget(); store.sibling("pads").forget(); PadMaterial.deleteAll(context.noBackupFilesDir, id)
        }
    }

    @Test fun inVaultPadMigratesWithUsedSlotsAlreadyErased() {
        val id = root(); val store = SecureStore(context, id)
        try {
            val a = StationEngine(store, relay)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val bundle = a.generatePad(); val original = bundle.copyOfRange(40, bundle.size); bundle.fill(0)
            val padId = a.state!!.getString("padId")
            PadLedger(store.sibling("pads")).reserve(padId); PadLedger(store.sibling("pads")).reserve(padId)
            val legacyPad = original.copyOf()
            // A pre-migration vault: whole pad inline, A1 zeroed by an earlier save, A2 reserved but not yet zeroed.
            legacyPad.fill(0, 0, PadProtocol.STRIDE)
            val legacy = JSONObject(a.state.toString()).put("version", 5).put("counter", 2)
                .put("pad", java.util.Base64.getEncoder().encodeToString(legacyPad))
            legacy.remove("padKeys"); store.save(legacy); legacyPad.fill(0)
            PadMaterial.deleteAll(context.noBackupFilesDir, id)

            val migrated = StationEngine(store, relay)
            assertFalse(migrated.state!!.has("pad")); assertTrue(migrated.state!!.has("padKeys"))
            assertFalse(File(context.noBackupFilesDir, "$id.bin").readBytes().size > 120_000)
            val snapshot = migrated.padSnapshot()
            assertTrue(snapshot.copyOf(2 * PadProtocol.STRIDE).all { it == 0.toByte() }) // A1 and reserved A2.
            assertArrayEquals(original.copyOfRange(2 * PadProtocol.STRIDE, 3 * PadProtocol.STRIDE), snapshot.copyOfRange(2 * PadProtocol.STRIDE, 3 * PadProtocol.STRIDE))
            val half = PadProtocol.MATERIAL_BYTES / 2
            assertArrayEquals(original.copyOfRange(half, half + PadProtocol.STRIDE), snapshot.copyOfRange(half, half + PadProtocol.STRIDE))
            assertEquals(PadProtocol.SLOTS - 2, migrated.view().padRemaining)
            original.fill(0)
        } finally {
            store.forget(); store.sibling("pads").forget(); PadMaterial.deleteAll(context.noBackupFilesDir, id)
        }
    }
}
