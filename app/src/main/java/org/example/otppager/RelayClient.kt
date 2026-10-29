package org.example.otppager

import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** [body] is kept only for strict-share 410 answers, which carry a signed REVOKED frame. */
class RelayHttpException(val status: Int, val body: String? = null) : java.io.IOException("HTTP $status")
interface RelayTransport {
    /** Preserve per-contact cancellation through wrappers, including background receiving. */
    fun forContact(store: SecureStore): RelayTransport = this
    /** False means an older relay without claims. Never downgrade a previously protected chat. */
    fun scope(endpoint: String, room: String, bootstrap: String, capability: String): Boolean = false
    fun request(endpoint: String, room: String, token: String, method: String, body: String? = null, cursor: Long = 0): String
    /** Strict-chat share store: PUT `/v1/share/<room>` (enroll once), POST `…/unlock` (signed lease), DELETE. */
    fun share(endpoint: String, room: String, capability: String, method: String, body: String? = null, unlock: Boolean = false): String =
        throw IllegalStateException("This connection cannot reach strict-chat key shares")
    /** The relay's strict-chat authority public key (base64 SPKI), pinned at pairing. */
    fun authority(endpoint: String): String = throw IllegalStateException("This connection cannot reach the strict-chat authority")
    /**
     * The authority's ML-DSA-65 key and the binding its P-256 key signed over it (both base64), or
     * null from a relay without one. The caller checks the binding against the pinned P-256 key.
     */
    fun pqAuthority(endpoint: String): Pair<String, String>? = null
}
object RelayClient : RelayTransport {
    /** Shared production serializer; Android JSON may escape '/' in base64 ciphertext. */
    internal fun packetBody(packet: String): String = org.json.JSONObject().put("packet", packet).toString()
    override fun forContact(store: SecureStore): RelayTransport = forStore(store)
    internal fun forStore(store: SecureStore, connection: (URL) -> HttpsURLConnection = ::openConnection): RelayTransport = object : RelayTransport {
        override fun scope(endpoint: String, room: String, bootstrap: String, capability: String): Boolean =
            ContactOperations.open(store.operationKey, store::requireActive).use { scopeWith(endpoint, room, bootstrap, capability, it, connection) }
        override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String =
            ContactOperations.open(store.operationKey, store::requireActive).use { exchange(endpoint, room, token, method, body, cursor, lease = it, connection = connection) }
        override fun share(endpoint: String, room: String, capability: String, method: String, body: String?, unlock: Boolean): String =
            ContactOperations.open(store.operationKey, store::requireActive).use { exchange(endpoint, room, capability, method, body, 0, if (unlock) "/unlock" else "", lease = it, connection = connection, kind = "share") }
        override fun authority(endpoint: String): String = this@RelayClient.authority(endpoint)
        override fun pqAuthority(endpoint: String): Pair<String, String>? = this@RelayClient.pqAuthority(endpoint)
    }
    override fun share(endpoint: String, room: String, capability: String, method: String, body: String?, unlock: Boolean): String =
        exchange(endpoint, room, capability, method, body, 0, if (unlock) "/unlock" else "", kind = "share")
    private fun authorityDocument(endpoint: String): org.json.JSONObject {
        val conn = openConnection(URL("${Protocol.endpoint(endpoint)}/v1/strict/authority"))
        conn.instanceFollowRedirects = false; conn.connectTimeout = 8000; conn.readTimeout = 10000
        try {
            if (conn.responseCode != 200) throw RelayHttpException(conn.responseCode)
            val text = conn.inputStream.use { readCapped(it, SMALL_BODY) }
            return org.json.JSONObject(text)
        } finally { conn.disconnect() }
    }
    override fun authority(endpoint: String): String = authorityDocument(endpoint).getString("key").also { IdentityProtocol.publicKey(it) }
    override fun pqAuthority(endpoint: String): Pair<String, String>? = authorityDocument(endpoint).let { d ->
        if (!d.has("pq")) null else d.getString("pq") to d.getString("binding")
    }
    override fun scope(endpoint: String, room: String, bootstrap: String, capability: String): Boolean =
        scopeWith(endpoint, room, bootstrap, capability, null)
    private fun scopeWith(endpoint: String, room: String, bootstrap: String, capability: String, lease: ContactOperations.Lease?, connection: (URL) -> HttpsURLConnection = ::openConnection): Boolean {
        try {
            val existing = org.json.JSONObject(exchange(endpoint, room, capability, "GET", null, 0, lease = lease, connection = connection))
            check(existing.optString("access") == "chat") { "Relay did not confirm chat access" }
            return true
        } catch (e: RelayHttpException) { if (e.status != 401) throw e }
        return try {
            val result = org.json.JSONObject(exchange(endpoint, room, bootstrap, "POST", org.json.JSONObject().put("capability", capability).toString(), 0, "/claim", lease, connection))
            check(result.optString("access") == "chat") { "Relay did not confirm chat access" }
            true
        } catch (e: RelayHttpException) { if (e.status == 404) false else throw e }
    }
    override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String =
        exchange(endpoint, room, token, method, body, cursor)
    private fun openConnection(url: URL) = RelayTls.apply(url.openConnection() as HttpsURLConnection)
    /**
     * Cap for small relay answers (authority keys, signed REVOKED): a hybrid answer with its
     * ML-DSA signature is about 7 KB. Anything longer is cut off, which fails closed.
     */
    internal const val SMALL_BODY = 16 * 1024
    internal fun readCapped(stream: java.io.InputStream, max: Int): String {
        val buffer = ByteArray(max); var size = 0
        while (size < buffer.size) { val n = stream.read(buffer, size, buffer.size - size); if (n < 0) break; size += n }
        return String(buffer, 0, size, Charsets.UTF_8)
    }
    private fun exchange(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long, path: String = "", lease: ContactOperations.Lease? = null, connection: (URL) -> HttpsURLConnection = ::openConnection, kind: String = "mailbox"): String {
        require(kind in listOf("mailbox", "share"))
        val suffix = if (method == "GET" && kind == "mailbox") "?after=$cursor&limit=50" else ""
        val conn = connection(URL("$endpoint/v1/$kind/$room$path$suffix"))
        lease?.onCancel { conn.disconnect() }
        lease?.check()
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            lease?.check()
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { lease?.check(); it.write(bytes); lease?.check() }
            }
            lease?.check()
            val code = conn.responseCode
            lease?.check()
            if (code !in 200..299) throw RelayHttpException(code, if (kind == "share" && code == 410) conn.errorStream?.use { readCapped(it, SMALL_BODY) } else null)
            conn.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    lease?.check()
                    val n = stream.read(chunk); lease?.check(); if (n < 0) break
                    check(buffer.size() + n <= 1024 * 1024) { "Relay response too large" }
                    buffer.write(chunk, 0, n)
                }
                lease?.check()
                buffer.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    }
}
