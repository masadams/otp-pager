package org.example.otppager

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiveTest {
    private class Mailbox : RelayTransport {
        val packets = mutableListOf<String>()
        var requests = 0
        override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
            requests++
            if (method == "GET") return JSONObject().put("protocol", 2).put("epoch", "receiver-test").put("messages", JSONArray(packets.mapIndexedNotNull { i, p -> if (i + 1 > cursor) JSONObject().put("seq", i + 1).put("packet", p) else null })).toString()
            val wire = JSONObject(body!!).getString("packet"); if (wire !in packets) packets.add(wire)
            return "{}"
        }
    }
    @Test fun receiverPersistsUnreadAndReceiptsWithoutAnActivityAndHonorsPause() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sender = SecureStore(context, "receiver-sender"); val receiver = SecureStore(context, "receiver-one")
        val extra = listOf(SecureStore(context, "receiver-index"), SecureStore(context, "receiver-index-claims"))
        (listOf(sender, receiver) + extra).forEach { it.forget() }; listOf(sender, receiver).forEach { it.sibling("pads").forget() }
        try {
            val relay = Mailbox(); val secret = Protocol.randomSecret()
            val a = StationEngine(sender, relay); val book = ChatBook(context, "receiver-index", "receiver-one"); val b = book.engine(relay = relay)
            a.configure("https://pager.example.com", "t".repeat(43), secret, "A"); b.configure("https://pager.example.com", "t".repeat(43), secret, "B")
            val bundle = a.generatePad(); b.installPad(bundle); bundle.fill(0)
            a.confirmPad(a.view().padCheck!!.takeLast(8)); b.confirmPad(b.view().padCheck!!.takeLast(8)); a.sync(); b.sync(); a.sync()
            a.send("Received with no activity"); a.sync()
            val cycle = ReceiveCycle(); val result = cycle.poll(book, relay)
            assertTrue(result.newMessages); assertEquals(1, result.unread); assertEquals(0, result.failed)
            assertEquals("Received with no activity", StationEngine(receiver, relay).view().messages.single().text)
            a.sync(); assertEquals("delivered", a.view().messages.single().status)
            assertFalse(ReceiveCycle().poll(book, relay).newMessages)
            a.send("Paused during USB"); a.sync()
            val requests = relay.requests
            assertEquals(0, cycle.poll(book, relay, allowed = { false }).attempted)
            assertEquals(requests, relay.requests)
            assertTrue(cycle.poll(book, relay).newMessages)
            assertEquals(2, StationEngine(receiver, relay).view().unread)
        } finally { (listOf(sender, receiver) + extra).forEach { it.forget() }; listOf(sender, receiver).forEach { it.sibling("pads").forget() } }
    }
    @Test fun userStartedForegroundReceiverStartsAndStopsOnSupportedAndroidVersions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        var activity: android.app.Activity? = null
        try {
            ReceiveService.stop(context)
            activity = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { ReceiveService.start(activity!!) }
            val until = System.currentTimeMillis() + 5000
            while (!ReceiveService.running && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue("Foreground receiver did not start", ReceiveService.running)
            assertTrue(ReceiveService.enabled(context))
            instrumentation.runOnMainSync { ReceiveService.stop(context) }
            val stopped = System.currentTimeMillis() + 5000
            while (ReceiveService.running && System.currentTimeMillis() < stopped) Thread.sleep(50)
            assertFalse(ReceiveService.running); assertFalse(ReceiveService.enabled(context))
        } finally {
            ReceiveService.stop(context)
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.waitForIdleSync()
        }
    }
    @Test fun nfcSetupRequiresExplicitLiveSharingAndHandlesBoundedChunks() {
        lateinit var card: NfcSetupCard
        InstrumentationRegistry.getInstrumentation().runOnMainSync { card = NfcSetupCard() }
        NfcSetupExchange.clear()
        assertFalse(card.processCommandApdu(NfcSetupExchange.select, null).contentEquals(byteArrayOf(0x90.toByte(), 0)))
        try {
            val bytes = ByteArray(450) { (it % 127).toByte() }
            NfcSetupExchange.payload = bytes; NfcSetupExchange.expires = android.os.SystemClock.elapsedRealtime() + 10000
            assertTrue(card.processCommandApdu(NfcSetupExchange.select, null).contentEquals(byteArrayOf(0x90.toByte(), 0)))
            val stream = java.io.ByteArrayOutputStream()
            for (at in listOf(0, 192, 384)) {
                val result = card.processCommandApdu(byteArrayOf(0x80.toByte(), 0xca.toByte(), (at shr 8).toByte(), at.toByte(), 192.toByte()), null)
                assertEquals(0x90.toByte(), result[result.size - 2]); stream.write(result, 0, result.size - 2)
            }
            assertArrayEquals(bytes, stream.toByteArray())
            NfcSetupExchange.expires = 0
            assertFalse(card.processCommandApdu(NfcSetupExchange.select, null).contentEquals(byteArrayOf(0x90.toByte(), 0)))
        } finally { NfcSetupExchange.clear() }
    }
}
