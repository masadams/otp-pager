package org.example.otppager

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Host runs seed, installs a higher release APK with -r, rejects bad updates, then verifies. */
@RunWith(AndroidJUnit4::class)
class UpgradeStateTest {
    private fun digest(s: JSONObject) = PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(s.toString().toByteArray(Charsets.UTF_8)))
    @Test fun releaseReplacementPreservesOperationalState() {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue("Only the host-driven signed update job runs this fixture", phase in listOf("seed", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val marker = File(context.noBackupFilesDir, "upgrade-reference.json")
        val first = "upgrade-first"
        val index = "upgrade-index"
        if (phase == "seed") {
            assertFalse("Fixture must start on a clean test device", marker.exists())
            val book = ChatBook(context, index, first)
            book.rename("First friend")
            val station = book.engine()
            station.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            station.generatePad().fill(0)
            station.confirmPad(station.view().padCheck!!.takeLast(8))
            // Stand in for a peer's verified readiness; never contact a real relay.
            val store = SecureStore(context, first)
            val ready = JSONObject(station.state.toString()).put("peerReady", true).put("high", 17).put("seen", JSONArray().put(17))
            ready.getJSONArray("outbox").getJSONObject(0).put("status", "relayed")
            store.save(ready)
            val bound = book.engine(first)
            bound.prepareIdentity()
            val peer = DeviceIdentity("upgrade-fixture-peer").card(true)
            bound.pinDevice(peer, IdentityProtocol.fingerprint(peer).takeLast(8))
            bound.send("Queued before the update")
            val second = book.add("Second friend")
            book.select(second.id)
            val reference = JSONObject().put("active", second.id).put("first", first)
                .put("counter", bound.state!!.getLong("counter")).put("ownDevice", bound.view().ownDevice)
                .put("packet", bound.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet"))
            for (name in listOf("$first-pads", index, "$index-claims", second.id)) {
                reference.put(name, digest(checkNotNull(SecureStore(context, name).load())))
            }
            marker.writeText(reference.toString()) // Only hashes/public identity/IDs, never pads or credentials.
        } else {
            assertTrue("Update lost the original app data", marker.isFile)
            val reference = JSONObject(marker.readText())
            val book = ChatBook(context, index, first)
            assertEquals(reference.getString("active"), book.active().id)
            assertEquals(listOf("First friend", "Second friend"), book.chats().map { it.name })
            for (name in listOf("$first-pads", index, "$index-claims", book.active().id)) {
                val saved = checkNotNull(SecureStore(context, name).load())
                if (name == index) {
                    assertEquals(3, saved.getInt("version"))
                    val entries = saved.getJSONArray("chats")
                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONObject(i)
                        assertEquals(entry.getString("id"), entry.getString("entryId"))
                        entry.remove("entryId")
                    }
                    saved.put("version", 2)
                }
                assertEquals("Update changed $name beyond the index metadata migration", reference.getString(name), digest(saved))
            }
            val station = book.engine(first)
            assertTrue(station.view().setupComplete)
            assertEquals(5, station.state!!.getInt("version"))
            assertEquals(reference.getString("packet"), station.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet"))
            assertFalse("Update left the pad in the vault", station.state!!.has("pad"))
            val material = station.padSnapshot()
            assertTrue(material.take(reference.getLong("counter").toInt() * PadProtocol.STRIDE).all { it == 0.toByte() })
            material.fill(0)
            assertEquals(17L, station.state!!.getLong("high"))
            assertEquals("Queued before the update", station.view().messages.single().text)
            assertEquals("queued", station.view().messages.single().status)
            val identity = SecureStore(context, first).identity()
            val card = reference.getString("ownDevice")
            assertEquals(card, identity.card())
            val challenge = "sign after Android install -r".toByteArray()
            assertTrue(IdentityProtocol.verify(card, challenge, identity.sign(challenge)))
            val before = reference.getLong("counter")
            val id = station.state!!.getString("padId")
            val ledger = PadLedger(SecureStore(context, "$first-pads"))
            assertEquals(before, ledger.high(id))
            station.send("Queued after the update")
            assertEquals(before + 1, station.state!!.getLong("counter"))
            assertEquals(before + 1, ledger.high(id))
            assertEquals(2, station.view().messages.size)
            try { ledger.register(id); fail("Upgrade forgot pad retirement") } catch (_: IllegalArgumentException) {}
        }
    }
}
