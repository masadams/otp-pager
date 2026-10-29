package org.example.otppager

import java.net.InetSocketAddress
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
    internal fun factory(trust: KeyStore? = null): SSLSocketFactory? =
        context(trust)?.socketFactory?.also { org.conscrypt.Conscrypt.setNamedGroups(it, GROUPS) }

    private val bundled: SSLSocketFactory? by lazy { factory() }
    /** False if this phone couldn't load the bundled TLS library; the platform TLS is used then. */
    val available: Boolean get() = bundled != null

    fun apply(connection: HttpsURLConnection): HttpsURLConnection {
        bundled?.let { connection.sslSocketFactory = it }
        return connection
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
