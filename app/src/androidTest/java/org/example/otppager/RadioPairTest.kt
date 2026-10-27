package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RadioPairTest {
    @Test fun cancellingWizardClosesListenerSynchronouslyAndWipesOffer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext, RadioPairActivity::class.java)
            .putExtra("chat", "radio-ui-fixture").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val server = ServerSocket(0)
        val offer = RadioTicket.create("10.1.2.3", server.localPort)
        try {
            instrumentation.runOnMainSync {
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                RadioPairActivity::class.java.getDeclaredField("listener").apply { isAccessible = true }.set(activity, server)
                RadioPairActivity::class.java.getDeclaredField("ticket").apply { isAccessible = true }.set(activity, offer)
                NfcSetupExchange.publish(offer.encode().toByteArray(), android.os.SystemClock.elapsedRealtime() + 60000,
                    SecureStore(instrumentation.targetContext, "radio-ui-fixture").operationKey)
                activity.finish()
                assertTrue(server.isClosed); assertNull(NfcSetupExchange.payload)
                assertTrue(offer.secret.all { it == 0.toByte() })
            }
            instrumentation.waitForIdleSync()
        } finally { server.close(); offer.close(); instrumentation.runOnMainSync { activity.finish() } }
    }
    private fun transfer(a: StationEngine, b: StationEngine, renew: Boolean, cancel: Boolean = false) {
        val pool = Executors.newFixedThreadPool(2)
        ServerSocket(0).use { server ->
            val ticket = RadioTicket.create("10.1.2.3", server.localPort)
            val peer = RadioTicket.decode(ticket.encode())
            val codes = java.util.concurrent.ConcurrentLinkedQueue<String>()
            fun approve(code: String, endpoint: String) { codes.add(code); check(endpoint.contains("pager.example.com")); check(!cancel) { "User cancelled" } }
            val host = pool.submit { server.accept().use { socket ->
                socket.soTimeout = 60000
                RadioHandoff.establish(true, socket.getInputStream(), socket.getOutputStream(), ticket).use { h ->
                    CableProtocol.run(true, h.input, h.output, a, ::approve, {}, renew, 4 * PadProtocol.MATERIAL_BYTES)
                }
            } }
            val reader = pool.submit { Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 60000
                RadioHandoff.establish(false, socket.getInputStream(), socket.getOutputStream(), peer).use { h ->
                    CableProtocol.run(false, h.input, h.output, b, ::approve, {}, renew, PadProtocol.MATERIAL_BYTES)
                }
            } }
            try {
                if (cancel) for (future in listOf(host, reader)) {
                    try { future.get(90, TimeUnit.SECONDS); fail("Cancelled pairing finished") } catch (_: java.util.concurrent.ExecutionException) {}
                } else { host.get(90, TimeUnit.SECONDS); reader.get(90, TimeUnit.SECONDS) }
                assertEquals(2, codes.size); assertEquals(1, codes.toSet().size)
            } finally { ticket.close(); peer.close(); pool.shutdownNow() }
        }
    }
    @Test fun fourMiBPairingAndRenewalOverAuthenticatedRadioPreservesIdentityAndTraffic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sa = SecureStore(context, "radio-a"); val sb = SecureStore(context, "radio-b")
        val stores = listOf(sa, sb); stores.forEach { it.forget(); it.sibling("pads").forget() }
        try {
            var a = StationEngine(sa); var b = StationEngine(sb)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            transfer(a, b, false, cancel = true)
            assertNull(a.view().padCheck); assertNull(b.view().padCheck)
            transfer(a, b, false)
            assertEquals(508, a.view().padRemaining); assertEquals(a.view().padCheck, b.view().padCheck)
            val own = a.view().ownDevice; val peer = a.view().peerDevice; val id = a.view().padCheck
            a.send("Confirmed over wireless renewal")
            transfer(a, b, true)
            a = StationEngine(sa); b = StationEngine(sb)
            assertEquals(own, a.view().ownDevice); assertEquals(peer, a.view().peerDevice)
            assertNotEquals(id, a.view().padCheck); assertEquals(a.view().padCheck, b.view().padCheck)
            assertEquals("delivered", a.view().messages.single().status)
            assertEquals("Confirmed over wireless renewal", b.view().messages.single().text)
            assertTrue(b.view().messages.single().deviceVerified)
        } finally { stores.forEach { it.forget(); it.sibling("pads").forget() } }
    }
}
