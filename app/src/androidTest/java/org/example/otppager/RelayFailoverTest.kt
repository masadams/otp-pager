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

/** Pipeline item 6: backup relays are copied at pairing, read on every check and used when the primary fails. */
@RunWith(AndroidJUnit4::class)
class RelayFailoverTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private val primary = "https://primary.example.com"; private val backup = "https://backup.example.com"

    /** Two relays without claims. [down] relays refuse connections; [revoked] answers 410. */
    private class Relays : RelayTransport {
        val rooms = mutableMapOf<String, MutableList<String>>()
        val down = mutableSetOf<String>(); val revoked = mutableSetOf<String>()
        val posts = mutableMapOf<String, Int>()
        @Synchronized override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            if (endpoint in down) throw java.io.IOException("unreachable")
            if (endpoint in revoked) throw RelayHttpException(410)
            check(token == if (endpoint.contains("backup")) "b".repeat(43) else "t".repeat(43)) { "Wrong token for $endpoint" }
            val list = rooms.getOrPut("$endpoint|$room") { mutableListOf() }
            if (method == "POST") { posts[endpoint] = (posts[endpoint] ?: 0) + 1; list.add(JSONObject(body!!).getString("packet")); return "{}" }
            return JSONObject().put("protocol", 2).put("epoch", "e-$endpoint").put("messages", JSONArray(list.mapIndexedNotNull { i, p ->
                if (i + 1 > cursor) JSONObject().put("seq", i + 1).put("packet", p) else null }.take(50))).toString()
        }
    }
    private fun pair(a: StationEngine, b: StationEngine) {
        val pool = Executors.newFixedThreadPool(2)
        val ain = PipedInputStream(65536); val bout = PipedOutputStream(ain)
        val bin = PipedInputStream(65536); val aout = PipedOutputStream(bin)
        try {
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
            PadMaterial.deleteAll(context.noBackupFilesDir, id)
        }
    }

    @Test fun messagesFlowThroughABackupWhileThePrimaryIsDownAndNothingDuplicates() {
        val ra = root(); val rb = root(); val relays = Relays()
        try {
            val a = StationEngine(SecureStore(context, ra), relays); val b = StationEngine(SecureStore(context, rb), relays)
            // The phone that supplies the server (here the accessory side) also supplies its backups.
            b.configure(primary, "t".repeat(43), Protocol.randomSecret(), "B")
            b.setBackups(listOf(backup to "b".repeat(43)))
            try { b.setBackups(listOf(primary to "x".repeat(43))); fail("Primary accepted as its own backup") } catch (_: Exception) {}
            pair(a, b)
            assertEquals(listOf(backup), a.backups); assertEquals(listOf(backup), b.backups)

            // Primary fine: traffic stays on it.
            a.send("via primary"); a.sync(); b.sync(); a.sync()
            assertEquals("via primary", b.view().messages.single().text)
            assertEquals(0, relays.posts[backup] ?: 0)

            // Primary down for both: send, receive and receipt all go through the backup.
            relays.down.add(primary)
            a.send("via backup")
            assertTrue(a.sync().contains("via backup relay"))
            b.sync(); a.sync()
            assertEquals(listOf("via primary", "via backup"), b.view().messages.map { it.text })
            assertEquals("delivered", a.view().messages.last().status)
            assertTrue((relays.posts[backup] ?: 0) >= 2)

            // Primary back, and the backup packets are re-posted to it: still exactly one copy each.
            relays.down.clear()
            relays.rooms.entries.filter { it.key.startsWith(backup) }.forEach { (key, list) -> relays.rooms.getOrPut(primary + key.removePrefix(backup)) { mutableListOf() }.addAll(list) }
            b.sync(); a.sync()
            assertEquals(2, b.view().messages.size)

            // Everything down: the failure is reported, nothing is lost from the outbox.
            relays.down.addAll(listOf(primary, backup))
            a.send("waiting")
            try { a.sync(); fail("Sync claimed success with every relay down") } catch (_: java.io.IOException) {}
            assertEquals("queued", a.view().messages.last().status)

            // Revocation on the primary is final and is never routed around through a backup.
            relays.down.clear(); relays.revoked.add(primary)
            try { a.sync(); fail("Revoked chat used a backup") } catch (e: RelayHttpException) { assertEquals(410, e.status) }
            assertTrue(a.view().relayRevoked)
        } finally { cleanup(listOf(ra, rb)) }
    }
}
