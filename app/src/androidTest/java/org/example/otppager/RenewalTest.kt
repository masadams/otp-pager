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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RenewalTest {
    private fun cable(a: StationEngine, b: StationEngine, renew: Boolean = false, size: Int = PadProtocol.MATERIAL_BYTES) {
        val pool = Executors.newFixedThreadPool(2)
        val ai = PipedInputStream(16384); val bo = PipedOutputStream(ai)
        val bi = PipedInputStream(16384); val ao = PipedOutputStream(bi)
        try {
            val one = pool.submit { CableProtocol.run(true, ai, ao, a, { _, _ -> }, {}, renew, size) }
            val two = pool.submit { CableProtocol.run(false, bi, bo, b, { _, _ -> }, {}, renew, size) }
            one.get(60, TimeUnit.SECONDS); two.get(60, TimeUnit.SECONDS)
        } finally { listOf(ai, bi, ao, bo).forEach { it.close() }; pool.shutdownNow() }
    }
    @Test fun renewalKeepsIdentityAndHistoryChangesPadAndRecoversBothInterruptedStates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sa = SecureStore(context, "renew-a"); val sb = SecureStore(context, "renew-b")
        val stores = listOf(sa, sb); stores.forEach { it.forget(); it.sibling("pads").forget() }
        try {
            var a = StationEngine(sa); var b = StationEngine(sb)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            cable(a, b)
            val card = a.view().ownDevice; val peer = a.view().peerDevice; val secret = a.state!!.getString("secret")
            a.send("Saved history")
            val saved = JSONObject(a.state.toString())
            saved.getJSONArray("outbox").getJSONObject(0).put("status", "delivered"); sa.save(saved)
            a = StationEngine(sa)
            val oldId = a.view().padCheck
            cable(a, b, true, 4 * PadProtocol.MATERIAL_BYTES)
            assertNotEquals(oldId, a.view().padCheck); assertEquals(a.view().padCheck, b.view().padCheck)
            assertEquals(card, a.view().ownDevice); assertEquals(peer, a.view().peerDevice); assertEquals(secret, a.state!!.getString("secret"))
            assertEquals("Saved history", a.view().messages.single().text); assertTrue(a.view().messages.single().deviceVerified)
            assertEquals(508, a.view().padRemaining)
            a = StationEngine(sa); b = StationEngine(sb)
            assertEquals("Saved history", a.view().messages.single().text)
            // Paused before either new pad commits: recover the existing pad without resetting allocations.
            a.beginRenewal(); b.beginRenewal()
            val current = a.view().padCheck
            cable(a, b, true)
            assertEquals(current, a.view().padCheck); assertTrue(a.view().setupComplete)
            // One device persisted the new candidate, the other still has the old one: both stay paused.
            a.beginRenewal(); b.beginRenewal()
            val master = Protocol.master(secret); val bundle = PadProtocol.generate(PadProtocol.binding(master))
            master.fill(0)
            val setup = JSONObject().put("endpoint", a.state!!.getString("endpoint")).put("token", a.state!!.getString("token"))
                .put("secret", secret).put("pad", java.util.Base64.getEncoder().encodeToString(bundle)); bundle.fill(0)
            b.stageRenewal(setup, b.view().peerDevice!!); b.commitCable(false)
            a = StationEngine(sa); b = StationEngine(sb)
            assertFalse(a.view().canSend); assertFalse(b.view().canSend)
            cable(a, b, true)
            assertTrue(a.view().setupComplete); assertTrue(b.view().setupComplete)
            assertEquals(a.view().padCheck, b.view().padCheck)
            assertEquals("Saved history", a.view().messages.single().text)
            a.send("After renewal"); assertEquals(2, a.view().messages.size)
            val n = a.state!!.getLong("counter")
            try { a.beginRenewal(); fail("Renewal discarded outstanding traffic") } catch (_: IllegalStateException) {}
            assertEquals(n, a.state!!.getLong("counter"))
            val before = a.view().padCheck
            try { a.installPad(java.util.Base64.getDecoder().decode(setup.getString("pad"))); fail("Old pad reinstalled") } catch (_: IllegalStateException) {}
            assertEquals(before, a.view().padCheck)
        } finally { stores.forEach { it.forget(); it.sibling("pads").forget() } }
    }
}
