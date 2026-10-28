package org.example.otppager

import java.net.URL
import javax.net.ssl.HttpsURLConnection

class RelayHttpException(val status: Int) : java.io.IOException("HTTP $status")
interface RelayTransport {
    /** Preserve per-contact cancellation through wrappers, including background receiving. */
    fun forContact(store: SecureStore): RelayTransport = this
    /** False means an older relay without claims. Never downgrade a previously protected chat. */
    fun scope(endpoint: String, room: String, bootstrap: String, capability: String): Boolean = false
    fun request(endpoint: String, room: String, token: String, method: String, body: String? = null, cursor: Long = 0): String
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
    private fun openConnection(url: URL) = url.openConnection() as HttpsURLConnection
    private fun exchange(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long, path: String = "", lease: ContactOperations.Lease? = null, connection: (URL) -> HttpsURLConnection = ::openConnection): String {
        val suffix = if (method == "GET") "?after=$cursor&limit=50" else ""
        val conn = connection(URL("$endpoint/v1/mailbox/$room$path$suffix"))
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
            if (code !in 200..299) throw RelayHttpException(code)
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
