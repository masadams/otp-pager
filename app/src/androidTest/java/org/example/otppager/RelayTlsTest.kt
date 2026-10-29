package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Bundled Conscrypt + this phone's own trust manager, end to end: a local TLS 1.3 server that
 * accepts only the hybrid X25519MLKEM768 group (or only classical X25519) on loopback.
 */
@RunWith(AndroidJUnit4::class)
class RelayTlsTest {
    /** TEST-ONLY self-signed localhost certificate and key (PKCS12, password "test"). */
    private val identity = java.util.Base64.getDecoder().decode("MIIDmgIBAzCCA2AGCSqGSIb3DQEHAaCCA1EEggNNMIIDSTCCAj8GCSqGSIb3DQEHBqCCAjAwggIsAgEAMIICJQYJKoZIhvcNAQcBMBwGCiqGSIb3DQEMAQMwDgQI/u0UgMURn50CAggAgIIB+Ltc8KWTEo648KwMaB25qqg8oSpGCEu9C82eWAlVTTyYacFxXk07q5nBI02BUwCGUBGdxzVxReko1U7vsvEPiRRataZpKHCuXz1zjscKMeSsoQaYu/sICgOkHx9uhqjD2f/TKyQRQQ61A1NjGSPzHuYLBLfHVQt4CxfRWHtvvF4ywfDMDN3PHTCq7saFaPlqBc6RNXh88uZIaq5rIP9iuTqjG420YdhkJY2rd68ssdLC8Vh4Emvg+0IOFygI4JVsXOFpnNgIvPtf55uPCgZMo6L/CT9eevv4SSO6KlWgcnvG6nZOa+qzevpZ4zlkpWoLU8nbnDb6Zg/i6Vbtv9hn23abNRQ1v8v+vMYTcEe94sFlXPh/lnG2g0fLjms0GmropwQou1pdczy0ld4ITG0XNGlx5VfssVOLPG/r5UBbHL+Jcj0cU1/EBUd4DvEns884H3Bn/TJ+YkM3aY1qO6P4d4YtWjCN07n2h1o9xGZj32QqDsizIzFhcUD8blbmJUsU3ND75fCbfJ4FaF7Wd5IVqhyBuC4BF15DX88HcWAYuRjuAAKwtFtkmi+EpNQvm+m7TQZGEN2cY5FgcenS3MecKWAo/L9nHsyevFMr2PD59tuNQnDU/WvoMlJmmD65E57MARtXLnXHVN2QDSveWo1gR5ClhpZdPSJo2jCCAQIGCSqGSIb3DQEHAaCB9ASB8TCB7jCB6wYLKoZIhvcNAQwKAQKggbQwgbEwHAYKKoZIhvcNAQwBAzAOBAjX+UKG6jtu/wICCAAEgZA2O9wI7jDSE8XXtvz3RkK7ZGlKyXY57Cj6SGEqWG5ef7KFgbkx9J1QXnViwY8EVsusFPy8wNUGGpQe6Gqge0mBPvXpxFjLX0bSl3GmbKSmA4Lhlzc/aoNGKMjB+oz62gcrNOLJRX46e8GzgI/o1Hzxp8uhtntqQuOI1RXxbpf/Vfjfv/C6N7+mu8QkyoQ12m4xJTAjBgkqhkiG9w0BCRUxFgQUNtvRpdYtId6FpDkOzcTQ2Hr+PkUwMTAhMAkGBSsOAwIaBQAEFJ7K8jqcU9OlMxtT4CSZglbjeFH3BAhtInDhxPEWQgICCAA=")
    private fun serverStore() = KeyStore.getInstance("PKCS12").apply { load(identity.inputStream(), "test".toCharArray()) }
    private fun trust() = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null); setCertificateEntry("relay", serverStore().getCertificate(serverStore().aliases().nextElement()))
    }
    private fun withServer(group: String, block: (Int) -> Unit) {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(serverStore(), "test".toCharArray()) }
        val context = SSLContext.getInstance("TLS", org.conscrypt.Conscrypt.newProvider()).apply { init(keys.keyManagers, null, null) }
        // IPv4 explicitly: Android's getLoopbackAddress() is ::1, while "localhost" resolves to 127.0.0.1.
        val server = context.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        val pool = Executors.newSingleThreadExecutor()
        pool.execute {
            while (!server.isClosed) try {
                (server.accept() as SSLSocket).use { socket ->
                    org.conscrypt.Conscrypt.setNamedGroups(socket, arrayOf(group))
                    socket.soTimeout = 5000
                    try {
                        val input = socket.inputStream; var tail = 0
                        while (tail != 0x0d0a0d0a) { val b = input.read(); if (b < 0) break; tail = (tail shl 8) or b }
                        socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"status\":\"ok\"}".toByteArray())
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        try { block(server.localPort) } finally { server.close(); pool.shutdownNow() }
    }
    private fun get(port: Int): Int {
        val conn = URL("https://localhost:$port/health").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = checkNotNull(RelayTls.factory(trust())); conn.connectTimeout = 5000; conn.readTimeout = 5000
        return try { conn.responseCode } finally { conn.disconnect() }
    }

    @Test fun bundledTlsLoadsOnThisDevice() = assertTrue(RelayTls.available)

    @Test fun negotiatesHybridPostQuantumKeyExchangeWithThePlatformTrustManager() = withServer(RelayTls.HYBRID_GROUP) { port ->
        assertEquals(200, get(port)) // The server offered nothing but X25519MLKEM768.
        assertEquals(true, RelayTls.serverSupportsHybrid("localhost", port, trust()))
    }

    @Test fun fallsBackToClassicalAndReportsIt() = withServer("X25519") { port ->
        assertEquals(200, get(port))
        assertEquals(false, RelayTls.serverSupportsHybrid("localhost", port, trust()))
    }

    @Test fun aServerThatProvedHybridSupportCannotBeDowngraded() {
        fun hybridOnlyGet(port: Int): Int {
            val conn = URL("https://localhost:$port/health").openConnection() as HttpsURLConnection
            conn.sslSocketFactory = checkNotNull(RelayTls.factory(trust(), hybridOnly = true)); conn.connectTimeout = 5000; conn.readTimeout = 5000
            return try { conn.responseCode } finally { conn.disconnect() }
        }
        withServer(RelayTls.HYBRID_GROUP) { port -> assertEquals(200, hybridOnlyGet(port)) }
        withServer("X25519") { port ->
            try { hybridOnlyGet(port); fail("Fell back to classical TLS for a hybrid-only server") } catch (_: javax.net.ssl.SSLException) {}
        }
    }

    @Test fun theRequiredListPersistsAndCanBeUndone() {
        val dir = java.nio.file.Files.createTempDirectory("pq-tls").toFile()
        try {
            RelayTls.init(dir)
            assertFalse(RelayTls.isRequired("Relay.Example.com", 443))
            RelayTls.requireHybrid("Relay.Example.com", 443)
            RelayTls.init(dir) // As after a restart.
            assertTrue(RelayTls.isRequired("relay.example.com", -1)) // Case and default port normalised.
            assertFalse(RelayTls.isRequired("relay.example.com", 8443))
            val conn = RelayTls.apply(URL("https://relay.example.com/health").openConnection() as HttpsURLConnection)
            assertNotNull(conn.sslSocketFactory)
            RelayTls.allowClassical("relay.example.com", 443)
            RelayTls.init(dir)
            assertFalse(RelayTls.isRequired("relay.example.com", 443))
        } finally {
            dir.deleteRecursively()
            RelayTls.init(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir)
        }
    }

    /**
     * Production path: Android's own trust manager and network security config with a publicly
     * trusted certificate, which only a real HTTPS site provides. Skipped only without internet.
     */
    @Test fun publicHttpsWorksThroughThePhonesOwnTrustStore() {
        val conn = URL("https://www.google.com/generate_204").openConnection() as HttpsURLConnection
        RelayTls.apply(conn); conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.instanceFollowRedirects = false
        val code = try { conn.responseCode } catch (e: java.net.UnknownHostException) {
            org.junit.Assume.assumeNoException("No internet on this emulator", e); return
        } finally { conn.disconnect() }
        assertTrue("HTTP $code", code in 200..399)
    }
    @Test fun stillRejectsAnUntrustedCertificate() = withServer(RelayTls.HYBRID_GROUP) { port ->
        val conn = URL("https://localhost:$port/health").openConnection() as HttpsURLConnection
        conn.sslSocketFactory = checkNotNull(RelayTls.factory()) // The phone's own CAs: the test certificate isn't one.
        try { conn.responseCode; fail("Untrusted certificate accepted") } catch (_: javax.net.ssl.SSLException) {} finally { conn.disconnect() }
    }
}
