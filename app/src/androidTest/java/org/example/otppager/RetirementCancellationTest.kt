package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class RetirementCancellationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Blocked(url: URL) : HttpsURLConnection(url) {
        val reading = CountDownLatch(1); val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1); val releaseRead = CountDownLatch(1)
        override fun getResponseCode(): Int { reading.countDown(); check(releaseRead.await(10, TimeUnit.SECONDS)); return 200 }
        override fun disconnect() { closing.countDown(); releaseClose.await(10, TimeUnit.SECONDS); releaseRead.countDown() }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "test-only"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    @Test fun retiringAContactDoesNotWaitForBlockedHttpsDisconnectAndPreventsAnyRetry() {
        val root = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
        val store = SecureStore(context, root)
        val threads = Executors.newFixedThreadPool(2)
        val conn = Blocked(URL("https://pager.example.com"))
        val relay = RelayClient.forStore(store) { conn }
        try {
            store.save(JSONObject().put("version", 2).put("text", "Encrypted fixture"))
            val request = threads.submit<Boolean> {
                try { relay.request("https://pager.example.com", "0".repeat(64), "t".repeat(43), "GET"); false }
                catch (_: IllegalStateException) { true }
            }
            assertTrue(conn.reading.await(5, TimeUnit.SECONDS))
            val cleanup = threads.submit<VaultRetirement.Result> { VaultRetirement.retireContact(context, root) }
            assertTrue(cleanup.get(5, TimeUnit.SECONDS).completed)
            assertTrue(conn.closing.await(5, TimeUnit.SECONDS))
            assertEquals(1L, conn.releaseRead.count) // A deliberately blocked disconnect has not completed.
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("otp-pager-$root"))
            conn.releaseClose.countDown(); assertTrue(request.get(5, TimeUnit.SECONDS))
            try { relay.request("https://pager.example.com", "0".repeat(64), "t".repeat(43), "GET"); fail("Retired relay retried") }
            catch (_: IllegalStateException) {}
        } finally {
            conn.releaseClose.countDown(); conn.releaseRead.countDown(); threads.shutdownNow()
            store.forget(); VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, root)).forEach { it.delete() }
        }
    }
    @Test fun cancellationCannotClearAnotherContactsNfcOffer() {
        val first = UUID.randomUUID().toString(); val second = UUID.randomUUID().toString()
        try {
            NfcSetupExchange.publish(byteArrayOf(7), Long.MAX_VALUE, second)
            NfcSetupExchange.clearFor(first)
            assertArrayEquals(byteArrayOf(7), NfcSetupExchange.payload)
            ContactOperations.cancel(second)
            lateinit var service: NfcSetupCard
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service = NfcSetupCard() }
            assertArrayEquals(byteArrayOf(0x69, 0x85.toByte()), service.processCommandApdu(NfcSetupExchange.select, null))
            NfcSetupExchange.clearFor(second); assertNull(NfcSetupExchange.payload)
        } finally { NfcSetupExchange.clear() }
    }
}
