package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Unreviewed peer wipe (docs/PEER_WIPE_PROTOCOL.md): provisioning, request, recipient action, honest status. */
@RunWith(AndroidJUnit4::class)
class PeerWipeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    /** In-memory relay without claims (an older relay): every room shares the setup token. */
    private class Relay : RelayTransport {
        val rooms = mutableMapOf<String, MutableList<String>>()
        @Synchronized override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            val list = rooms.getOrPut(room) { mutableListOf() }
            if (method == "POST") { list.add(JSONObject(body!!).getString("packet")); return "{}" }
            return JSONObject().put("protocol", 2).put("epoch", "peer-wipe-test").put("messages", JSONArray(list.mapIndexedNotNull { i, p ->
                if (i + 1 > cursor) JSONObject().put("seq", i + 1).put("packet", p) else null
            }.take(50))).toString()
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
    private fun cleanup(roots: List<String>) {
        for (id in roots) {
            listOf(id, "$id-draft", "$id-pads").forEach { SecureStore(context, it).forget() }
            VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, id)).forEach { it.delete() }
            PadMaterial.deleteAll(context.noBackupFilesDir, id)
            File(context.noBackupFilesDir, "peerwipe").listFiles()?.filter { it.readText().contains(id) }?.forEach { it.delete() }
        }
    }

    @Test fun wipeRequestRemovesOnlyThatChatOnTheFriendsPhoneAndReportsWhatItSaid() {
        val ra = root(); val rb = root(); val bystander = root()
        val before = PeerWipe.enabled(context)
        try {
            PeerWipe.setEnabled(context, true)
            val relay = Relay()
            var ackPosts = 0
            val recipientRelay = object : RelayTransport {
                override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
                    if (method == "POST") {
                        assertTrue("Recipient posted before local retirement", VaultLifecycle.isRetired(context.noBackupFilesDir, rb))
                        ackPosts++
                    }
                    return relay.request(endpoint, room, token, method, body, cursor)
                }
            }
            val a = StationEngine(SecureStore(context, ra), relay); val b = StationEngine(SecureStore(context, rb), recipientRelay)
            pair(a, b)
            val ca = a.state!!.getJSONObject("control"); val cb = b.state!!.getJSONObject("control")
            assertEquals(ca.getString("generation"), cb.getString("generation")); assertEquals(ca.getString("credential"), cb.getString("credential"))
            assertEquals(ca.getString("ctlOut"), cb.getString("ctlIn")); assertEquals(ca.getString("ackIn"), cb.getString("ackOut"))
            val other = StationEngine(SecureStore(context, bystander), relay)
            other.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")

            // Initiator: seal the request first, then destroy locally (ChatBook.wipeLocal order).
            assertTrue(PeerWipe.prepare(context, ra, a.state))
            assertTrue(VaultRetirement.retireContact(context, ra).completed)
            assertEquals(PeerWipe.Status.QUEUED, PeerWipe.status(context, ra))
            val record = File(context.noBackupFilesDir, "peerwipe").listFiles()!!.single { it.name.startsWith("out-") && it.readText().contains(ra) }.readText()
            for (secret in listOf(ca.getString("ctlOut"), ca.getString("ctlIn"), ca.getString("ackIn"), ca.getString("ackOut"), ca.getString("credential")))
                assertFalse("Dispatch record kept a control key", record.contains(secret))
            PeerWipe.pump(context, relay)
            assertEquals(PeerWipe.Status.ACCEPTED, PeerWipe.status(context, ra))

            // Recipient: the next check finds the request and removes only this chat.
            val notice = b.sync()
            assertTrue(notice.contains("asked to remove this chat"))
            assertTrue("Final acknowledgement was not dispatched", ackPosts > 0)
            assertTrue(VaultLifecycle.isRetired(context.noBackupFilesDir, rb))
            assertEquals(VaultRetirement.LocalState.COMPLETE, VaultRetirement.inspect(context, rb).state)
            assertTrue(PadMaterial.gone(context.noBackupFilesDir, rb))
            assertTrue(PeerWipe.removedByFriend(context, rb))
            assertFalse(VaultLifecycle.isRetired(context.noBackupFilesDir, bystander))
            assertNotNull(StationEngine(SecureStore(context, bystander), relay).state)

            PeerWipe.pump(context, relay)
            assertEquals(PeerWipe.Status.WIPED, PeerWipe.status(context, ra))
            // Replays change nothing and statuses never move backwards.
            relay.rooms.values.forEach { it.addAll(it.toList()) }
            PeerWipe.pump(context, relay)
            assertEquals(PeerWipe.Status.WIPED, PeerWipe.status(context, ra))
        } finally {
            PeerWipe.setEnabled(context, before)
            cleanup(listOf(ra, rb, bystander))
        }
    }

    @Test fun recipientIgnoresRequestsWhenOffForgedOrForAnotherPairing() {
        val ra = root(); val rb = root()
        val before = PeerWipe.enabled(context)
        try {
            PeerWipe.setEnabled(context, true)
            val relay = Relay()
            val a = StationEngine(SecureStore(context, ra), relay); val b = StationEngine(SecureStore(context, rb), relay)
            pair(a, b)
            val c = a.state!!.getJSONObject("control")
            fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val room = ControlProtocol.mailbox(bytes(c.getString("credential")))
            fun post(frame: ByteArray) = relay.request("", room, "", "POST", JSONObject().put("packet", java.util.Base64.getEncoder().encodeToString(frame)).toString())
            val generation = bytes(c.getString("generation"))
            post(ControlProtocol.wipe(ByteArray(32) { 7 }, ControlProtocol.Direction.A_TO_B, generation, ByteArray(16), 0)) // Forged key.
            post(ControlProtocol.wipe(bytes(c.getString("ctlOut")), ControlProtocol.Direction.A_TO_B, ByteArray(16) { 1 }, ByteArray(16), 0)) // Other pairing.
            post(ControlProtocol.wipe(bytes(c.getString("ctlOut")), ControlProtocol.Direction.B_TO_A, generation, ByteArray(16), 0)) // Reflected.
            post(ByteArray(ControlProtocol.WIPE_BYTES)) // Garbage of the right size.
            try { b.sync() } catch (_: Exception) {} // Ordinary sync may fail on the bare relay; control must not act.
            assertFalse(VaultLifecycle.isRetired(context.noBackupFilesDir, rb))

            // A genuine request while the recipient has the setting off is ignored.
            PeerWipe.setEnabled(context, false)
            post(ControlProtocol.wipe(bytes(c.getString("ctlOut")), ControlProtocol.Direction.A_TO_B, generation, ByteArray(16) { 2 }, 0))
            try { b.sync() } catch (_: Exception) {}
            assertFalse(VaultLifecycle.isRetired(context.noBackupFilesDir, rb))
            assertFalse(PeerWipe.prepare(context, ra, a.state)) // And the initiator does not seal one either.
        } finally {
            PeerWipe.setEnabled(context, before)
            cleanup(listOf(ra, rb))
        }
    }
}
