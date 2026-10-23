package org.example.otppager

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.graphics.Color
import android.graphics.Typeface
import android.widget.*
import android.view.ViewGroup
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors

/** EXPERIMENTAL AES-GCM relay client. NOT OTP, NOT AUDITED, NOT JERICHO COMPATIBLE. */
class NetworkActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val rng = SecureRandom()
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var secretField: EditText
    private lateinit var messageField: EditText
    private lateinit var log: TextView
    private lateinit var station: Spinner
    private var cursor = 0L
    private var linkIdentity = ""
    private val seen = mutableSetOf<String>()
    private val bg = Color.rgb(14, 24, 17)
    private val fg = Color.rgb(169, 219, 158)
    private fun line(text: String) = TextView(this).apply {
        setTextColor(fg); typeface = Typeface.MONOSPACE; textSize = 14f
        this.text = text; setPadding(0, 12, 0, 12)
    }
    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint; setHintTextColor(Color.rgb(100, 130, 103))
        setTextColor(fg); typeface = Typeface.MONOSPACE; textSize = 14f
        setSingleLine(hint != "MESSAGE")
    }
    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title; typeface = Typeface.MONOSPACE; setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = bg; window.navigationBarColor = bg
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(28, 32, 28, 28)
        }
        layout.addView(line("OTP PAGER / FIELD TERMINAL").apply { textSize = 20f })
        layout.addView(line("STATION LINK  //  EXPERIMENTAL AES-GCM"))
        urlField = field("HTTPS RELAY URL").apply { setText("https://") }
        layout.addView(urlField)
        tokenField = field("RELAY ACCESS TOKEN").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        layout.addView(tokenField)
        secretField = field("SHARED SECRET (BASE64 URL SAFE)").apply {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        layout.addView(secretField)
        layout.addView(button("GENERATE PAIRING SECRET") {
            val key = ByteArray(32).also(rng::nextBytes)
            secretField.setText(Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            key.fill(0); report("SECRET GENERATED. EXCHANGE IN PERSON.")
        })
        station = Spinner(this)
        station.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("STATION A", "STATION B"))
        layout.addView(station)
        messageField = field("MESSAGE")
        layout.addView(messageField)
        layout.addView(button("TEST LINK") { perform(false, true) })
        layout.addView(button("TRANSMIT") { perform(true) })
        layout.addView(button("RECEIVE") { perform(false) })
        log = line("READY. NOT FOR SECRETS.")
        layout.addView(log)
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    private fun report(value: String) = runOnUiThread { log.text = value }
    private fun perform(send: Boolean, testOnly: Boolean = false) {
        val endpoint = urlField.text.toString().trim().trimEnd('/')
        val token = tokenField.text.toString().trim()
        val secret = secretField.text.toString().trim()
        val msg = messageField.text.toString()
        val role = station.selectedItemPosition
        if (!endpoint.startsWith("https://")) { report("ERROR: HTTPS REQUIRED"); return }
        if (!Regex("^[A-Za-z0-9_-]{43,128}$").matches(token)) {
            report("ERROR: ENTER RELAY ACCESS TOKEN"); return
        }
        worker.execute {
            var master = ByteArray(0)
            try {
                master = Base64.decode(secret, Base64.URL_SAFE or Base64.NO_WRAP)
                require(master.size == 32) { "PAIRING SECRET MUST BE 32 BYTES" }
                val room = hex(sha256("room".toByteArray() + master))
                val path = "$endpoint/v1/mailbox/$room"
                val identity = "$endpoint:$room:$role"
                if (identity != linkIdentity) {
                    cursor = 0L; seen.clear(); linkIdentity = identity
                }
                if (testOnly) {
                    request("$path?after=$cursor", "GET", null, token)
                    report("LINK VERIFIED // AUTHENTICATED HTTPS"); return@execute
                }
                val outbound = if (role == 0) "A" else "B"
                val inbound = if (role == 0) "B" else "A"
                if (send) {
                    val iv = ByteArray(12).also(rng::nextBytes)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sha256("key:$outbound".toByteArray() + master), "AES"), GCMParameterSpec(128, iv))
                    cipher.updateAAD(outbound.toByteArray())
                    val data = cipher.doFinal(msg.toByteArray(Charsets.UTF_8))
                    val packet = outbound.toByteArray() + iv + data
                    val encoded = Base64.encodeToString(packet, Base64.NO_WRAP)
                    request(path, "POST", JSONObject().put("packet", encoded).toString(), token)
                    report("TRANSMITTED // ${msg.toByteArray().size} BYTES")
                } else {
                    val response = JSONObject(request("$path?after=$cursor", "GET", null, token))
                    val messages = response.getJSONArray("messages")
                    val received = mutableListOf<String>()
                    for (i in 0 until messages.length()) {
                        val item = messages.getJSONObject(i)
                        cursor = maxOf(cursor, item.getLong("seq"))
                        val encoded = item.getString("packet")
                        if (!seen.add(encoded)) continue
                        try {
                            val packet = Base64.decode(encoded, Base64.DEFAULT)
                            if (packet.size < 29 || packet[0].toInt().toChar().toString() != inbound) continue
                            val iv = packet.copyOfRange(1, 13)
                            val ciphertext = packet.copyOfRange(13, packet.size)
                            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sha256("key:$inbound".toByteArray() + master), "AES"), GCMParameterSpec(128, iv))
                            cipher.updateAAD(inbound.toByteArray())
                            received.add(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
                        } catch (_: Exception) { received.add("[AUTHENTICATION FAILED]") }
                    }
                    report(if (received.isEmpty()) "NO NEW TRAFFIC" else received.joinToString("\n---\n"))
                }
            } catch (e: Exception) { report("LINK ERROR: ${e.message}") }
            finally { master.fill(0) }
        }
    }
    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it) }
    private fun request(url: String, method: String, body: String?, token: String): String {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.requestMethod = method; conn.connectTimeout = 10000; conn.readTimeout = 10000
        return try {
          if (body != null) {
            conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
            val status = conn.responseCode
            if (status !in 200..299) error("HTTP $status")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}

