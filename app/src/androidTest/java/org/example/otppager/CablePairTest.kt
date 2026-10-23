package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CablePairTest {
    @Test fun automaticPairingWorksWithServerSettingsOnEitherUsbSide() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (configuredHost in listOf(true, false)) {
            val sa = SecureStore(context, "cable-a"); val sb = SecureStore(context, "cable-b")
            val stores = listOf(sa, sb); stores.forEach { it.forget(); it.sibling("pads").forget() }
            val pool = Executors.newFixedThreadPool(2)
            val ain = PipedInputStream(16384); val bout = PipedOutputStream(ain)
            val bin = PipedInputStream(16384); val aout = PipedOutputStream(bin)
            try {
                val a = StationEngine(sa); val b = StationEngine(sb)
                (if (configuredHost) a else b).configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "B")
                val codes = java.util.concurrent.CopyOnWriteArrayList<String>(); val ready = CountDownLatch(2)
                val review: (String, String) -> Unit = { code, _ -> codes.add(code); ready.countDown(); check(ready.await(20, TimeUnit.SECONDS)) }
                val af = pool.submit { CableProtocol.run(true, ain, aout, a, review) {} }
                val bf = pool.submit { CableProtocol.run(false, bin, bout, b, review) {} }
                af.get(30, TimeUnit.SECONDS); bf.get(30, TimeUnit.SECONDS)
                assertEquals(2, codes.size); assertEquals(codes[0], codes[1])
                assertEquals("A", a.view().role); assertEquals("B", b.view().role)
                assertEquals(a.view().padCheck, b.view().padCheck)
                assertEquals(a.view().ownDevice, b.view().peerDevice); assertEquals(b.view().ownDevice, a.view().peerDevice)
                assertEquals(127, a.view().padRemaining); assertEquals(127, b.view().padRemaining)
                assertTrue(a.view().canSend); assertTrue(b.view().canSend)
                assertTrue(StationEngine(sa).view().pairingComplete); assertTrue(StationEngine(sb).view().pairingComplete)
                val pad = java.util.Base64.getDecoder().decode(a.state!!.getString("pad"))
                a.send("CABLE PAIRED")
                val wire = a.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet")
                assertTrue(IdentityProtocol.isSigned(PadProtocol.open(pad, wire, 'A').payload)); pad.fill(0)
                try { a.requireCableEligible(); fail("Operational pairing was replaceable") } catch (_: IllegalStateException) {}
            } finally {
                listOf(ain, bin, aout, bout).forEach { try { it.close() } catch (_: Exception) {} }
                pool.shutdownNow(); stores.forEach { it.forget(); it.sibling("pads").forget() }
            }
        }
    }
    @Test fun stagedAndInterruptedPairingRetiresPadAndCannotSend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, "cable-stage"); store.forget(); store.sibling("pads").forget()
        val other = DeviceIdentity("cable-other-test")
        try {
            val station = StationEngine(store); station.cableIdentity()
            val secret = Protocol.randomSecret(); val master = Protocol.master(secret)
            val bundle = PadProtocol.generate(PadProtocol.binding(master)); master.fill(0)
            val setup = org.json.JSONObject().put("endpoint", "https://pager.example.com").put("token", "t".repeat(43))
                .put("secret", secret).put("pad", java.util.Base64.getEncoder().encodeToString(bundle)); bundle.fill(0)
            val peer = other.card(true)
            station.stageCable(setup, "A", peer)
            assertNull(store.load()) // Staging has not activated a station vault.
            val restart = StationEngine(store)
            try { restart.stageCable(setup, "A", peer); fail("Exposed pad reused after interruption") } catch (_: IllegalArgumentException) {}
            station.commitCable(false)
            val pending = StationEngine(store)
            assertFalse(pending.view().canSend)
            try { pending.send("blocked"); fail("Partial pairing sent traffic") } catch (_: IllegalStateException) {}
            try { pending.sync(); fail("Partial pairing contacted relay") } catch (_: IllegalStateException) {}
            assertEquals(127, pending.view().padRemaining)
        } finally { other.forget(); store.forget(); store.sibling("pads").forget() }
    }
}
