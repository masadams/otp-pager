package org.example.otppager

import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * TLS for every relay connection through a bundled Conscrypt (BoringSSL), so all Android
 * versions offer the hybrid post-quantum key exchange X25519MLKEM768 first. A relay or proxy
 * that supports it (for example Nginx on OpenSSL 3.5+) then protects recorded traffic (relay
 * tokens, mailbox IDs, timing) against a future quantum computer. Others fall back to X25519.
 * Message text never depends on TLS. Certificates are checked against the phone's own trusted
 * CAs and hostnames by the platform verifier, exactly as before.
 *
 * Downgrade protection (0.19): once a server has accepted the hybrid group (connection test, or
 * the daily check after a successful sync), this phone offers only that group to it, so a network
 * that strips it gets a failed handshake instead of classical TLS. "Allow classical TLS" in the
 * connection test undoes it, for a proxy that was changed on purpose.
 */
internal object RelayTls {
    const val HYBRID_GROUP = "X25519MLKEM768"
    private val GROUPS = arrayOf(HYBRID_GROUP, "X25519", "P-256", "P-384")

    /**
     * The platform's default trust manager (null [trust]: the phone's system CAs plus this app's
     * network security config, so user-installed CAs stay untrusted). Tests pass their own store.
     */
    private fun context(trust: KeyStore?): SSLContext? = try {
        val provider = org.conscrypt.Conscrypt.newProvider()
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        SSLContext.getInstance("TLS", provider).apply { init(null, managers.trustManagers, null) }
    } catch (_: Throwable) { null } // No native library for this ABI: keep the platform's TLS rather than fail.
    /** [hybridOnly]: offer nothing but [HYBRID_GROUP] (servers that proved support for it). */
    internal fun factory(trust: KeyStore? = null, hybridOnly: Boolean = false): SSLSocketFactory? =
        context(trust)?.socketFactory?.also { org.conscrypt.Conscrypt.setNamedGroups(it, if (hybridOnly) arrayOf(HYBRID_GROUP) else GROUPS) }

    private val bundled: SSLSocketFactory? by lazy { factory() }
    private val hybridOnly: SSLSocketFactory? by lazy { factory(hybridOnly = true) }
    /** False if this phone couldn't load the bundled TLS library; the platform TLS is used then. */
    val available: Boolean get() = bundled != null

    fun apply(connection: HttpsURLConnection): HttpsURLConnection {
        val factory = if (isRequired(connection.url)) hybridOnly else bundled
        factory?.let { connection.sslSocketFactory = it }
        return connection
    }

    // Servers that must use the hybrid group, as "host:port", kept in no-backup storage.
    private val required = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var store: File? = null
    private val probed = ConcurrentHashMap<String, Long>()
    private val HOST = Regex("[a-z0-9.-]{1,253}:[0-9]{1,5}")
    /** Application start: loads the list (an unreadable file leaves it empty, as before 0.19). */
    fun init(directory: File) {
        val file = File(directory, "pq-tls-required"); store = file
        required.clear()
        try { if (file.exists()) file.readLines().filter(HOST::matches).forEach { required.add(it) } } catch (_: Exception) {}
    }
    private fun key(host: String, port: Int) = "${host.lowercase().trimEnd('.')}:${if (port > 0) port else 443}"
    private fun isRequired(url: URL) = key(url.host, url.port) in required
    fun isRequired(host: String, port: Int) = key(host, port) in required
    @Synchronized private fun save() {
        val file = store ?: return
        val tmp = File(file.path + ".tmp")
        java.io.FileOutputStream(tmp).use { out -> out.write(required.sorted().joinToString("\n").toByteArray()); out.fd.sync() }
        check(tmp.renameTo(file)) { "Could not save the post-quantum TLS list" }
    }
    fun requireHybrid(host: String, port: Int) { if (required.add(key(host, port))) save() }
    fun allowClassical(host: String, port: Int) { if (required.remove(key(host, port))) save() }
    /**
     * After a successful sync: at most once a day per server, checks whether it accepts the hybrid
     * group and, if it does, requires it from now on. Network; never on the UI thread.
     */
    fun learn(endpoint: String) {
        if (!available) return
        val url = URL(endpoint); val port = if (url.port > 0) url.port else 443
        val name = key(url.host, port)
        if (name in required) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = probed[name]
        if (last != null && now - last < 24 * 3600_000L) return
        probed[name] = now
        if (serverSupportsHybrid(url.host, port) == true) requireHybrid(url.host, port)
    }

    /**
     * Connection test only: does this server agree to the hybrid group? Offers nothing else, so
     * a successful handshake proves it. Null when the bundled library isn't available.
     */
    fun serverSupportsHybrid(host: String, port: Int, trust: KeyStore? = null): Boolean? {
        val factory = context(trust)?.socketFactory ?: return null
        org.conscrypt.Conscrypt.setNamedGroups(factory, arrayOf(HYBRID_GROUP))
        return try {
            (factory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(host, port), 8000); socket.soTimeout = 8000
                org.conscrypt.Conscrypt.setHostname(socket, host)
                socket.startHandshake()
                // Same rule the HTTPS request applies: the certificate must match the relay name.
                javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)
            }
        } catch (_: Exception) { false }
    }
}
