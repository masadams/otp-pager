package org.example.otppager

import android.os.SystemClock
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Read-only relay connection test, step by step, so a failure says where it stopped: name lookup,
 * secure connection, relay health, then (for a saved chat) whether this chat's access is accepted.
 * Never claims, sends, advances cursors or changes stored state. Network; never on the UI thread.
 */
internal object RelayProbe {
    /** Largest cursor the relay accepts: the access read returns no messages. */
    const val PROBE_CURSOR = 9_007_199_254_740_991L
    /** [ok] null means the step was skipped because an earlier one failed. [warn]: works, but worth fixing. */
    data class Step(val name: String, val ok: Boolean?, val detail: String, val warn: Boolean = false)
    data class Report(val label: String, val endpoint: String, val steps: List<Step>) {
        val ok: Boolean get() = steps.none { it.ok == false }
        fun describe() = "$label\n$endpoint\n" + steps.joinToString("\n") {
            "${when { it.ok == true && it.warn -> "!"; it.ok == true -> "✓"; it.ok == false -> "✗"; else -> "–" }} ${it.name}: ${it.detail}"
        }
    }

    fun check(label: String, endpoint: String, access: (() -> String)? = null): Report {
        val steps = mutableListOf<Step>()
        fun skipRest(vararg names: String) = names.forEach { steps.add(Step(it, null, "not tested")) }
        val accessName = "Chat access"
        val later = if (access != null) arrayOf("Relay health", accessName) else arrayOf("Relay health")

        val host = URI(endpoint).host
        var started = SystemClock.elapsedRealtime()
        try {
            val addresses = InetAddress.getAllByName(host)
            steps.add(Step("Address", true, "${addresses.size} found · ${SystemClock.elapsedRealtime() - started} ms"))
        } catch (_: UnknownHostException) {
            steps.add(Step("Address", false, "\"$host\" was not found. Check the address and that this phone is online."))
            skipRest("Secure connection", *later); return Report(label, endpoint, steps)
        }

        started = SystemClock.elapsedRealtime()
        val conn = RelayTls.apply(URL("$endpoint/health").openConnection() as HttpsURLConnection)
        conn.instanceFollowRedirects = false; conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = try { conn.responseCode } catch (e: Exception) {
                steps.add(Step("Secure connection", false, when (e) {
                    is SocketTimeoutException -> "No answer within 8 seconds. The server or its proxy may be down, or a firewall is blocking it."
                    is ConnectException -> "Connection refused. Nothing is accepting HTTPS on this address and port."
                    is SSLException -> "Secure connection failed. The certificate isn't trusted for \"$host\" or TLS is misconfigured."
                    else -> e.message ?: "Connection failed"
                }))
                skipRest(*later); return Report(label, endpoint, steps)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val suite = try { conn.cipherSuite } catch (_: Exception) { "" }
            val tls = if (suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_")) "TLS 1.3" else "TLS 1.2 or older"
            steps.add(Step("Secure connection", true, "$tls · $elapsed ms"))
            val port = URI(endpoint).port.takeIf { it > 0 } ?: 443
            steps.add(when (RelayTls.serverSupportsHybrid(host, port)) {
                true -> Step("Post-quantum TLS", true, "Hybrid ${RelayTls.HYBRID_GROUP} key exchange")
                false -> Step("Post-quantum TLS", true, "Not offered by the server, so it used classical X25519. Messages are still OTP-protected; enable ${RelayTls.HYBRID_GROUP} on your proxy to protect tokens and metadata too.", warn = true)
                null -> Step("Post-quantum TLS", true, "Not available on this phone; it used Android's own TLS.", warn = true)
            })
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { stream ->
                    val buffer = ByteArray(4096); var size = 0
                    while (size < buffer.size) { val n = stream.read(buffer, size, buffer.size - size); if (n < 0) break; size += n }
                    String(buffer, 0, size, Charsets.UTF_8)
                } ?: ""
            } catch (_: Exception) { "" }
            val status = try { org.json.JSONObject(body).optString("status") } catch (_: Exception) { "" }
            val healthy = code == 200 && status == "ok"
            steps.add(Step("Relay health", healthy, when {
                healthy -> "Relay is running"
                code == 503 -> "The relay is up but reports its storage is unavailable. Check the server's data volume."
                code in 300..399 -> "The server redirected (HTTP $code). Use the final relay address."
                else -> "HTTP $code. This address answers, but not like an OTP Pager relay. Check the address and proxy routing."
            }))
            if (!healthy) { if (access != null) skipRest(accessName); return Report(label, endpoint, steps) }
        } finally { conn.disconnect() }

        if (access != null) steps.add(try {
            val response = org.json.JSONObject(access())
            when {
                response.optInt("protocol") != 2 -> Step(accessName, false, "The relay is an older version. Update the relay container.")
                response.optString("access") == "chat" -> Step(accessName, true, "Accepted · protected chat access")
                else -> Step(accessName, true, "Accepted · shared setup access")
            }
        } catch (e: RelayHttpException) {
            Step(accessName, false, when (e.status) {
                401 -> "Not accepted. The relay may have been reset or the access token changed. Open Chat relay access."
                403 -> "Refused for this chat."
                410 -> "This chat's access was revoked. Pair a fresh chat on both phones."
                else -> "The relay answered HTTP ${e.status}."
            })
        } catch (e: Exception) { Step(accessName, false, e.message ?: "Access check failed") })
        return Report(label, endpoint, steps)
    }
}
