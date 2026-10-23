package org.example.otppager

import java.net.URL
import javax.net.ssl.HttpsURLConnection

class RelayHttpException(val status: Int) : java.io.IOException("HTTP $status")
interface RelayTransport {
    fun request(endpoint: String, room: String, token: String, method: String, body: String? = null, cursor: Long = 0): String
}
object RelayClient : RelayTransport {
    override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
        val suffix = if (method == "GET") "?after=$cursor&limit=50" else ""
        val conn = URL("$endpoint/v1/mailbox/$room$suffix").openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw RelayHttpException(code)
            conn.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = stream.read(chunk); if (n < 0) break
                    check(buffer.size() + n <= 1024 * 1024) { "Relay response too large" }
                    buffer.write(chunk, 0, n)
                }
                buffer.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    }
}
