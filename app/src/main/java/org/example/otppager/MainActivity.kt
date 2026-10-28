package org.example.otppager

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Educational, single-device demonstration. NOT a secure messaging client.
 * Two roles are simulated in one process. No network, no physical pairing,
 * no persistent key accounting.
 */
class MainActivity : Activity() {
    private val random = SecureRandom()
    private lateinit var output: TextView
    private lateinit var input: EditText
    private var packet: Packet? = null
    private var receiverPad: ByteArray? = null
    private var receiverAuth: ByteArray? = null

    private data class Packet(val cipher: ByteArray, val tag: ByteArray)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 24)
        }
        layout.addView(TextView(this).apply { text = "OTP PAGER — OFFLINE DEMO"; textSize = 22f })
        layout.addView(TextView(this).apply {
            text = "One phone simulates sender and receiver. No internet, no real pairing. NOT FOR SECRETS."
        })
        input = EditText(this).apply {
            hint = "Message"
            setText("Meet tomorrow at 10.")
        }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = "Encrypt as sender"
            setOnClickListener { encrypt() }
        })
        layout.addView(Button(this).apply {
            text = "Open once as receiver"
            setOnClickListener { decrypt() }
        })
        layout.addView(Button(this).apply {
            text = "Clear demo"
            setOnClickListener { clear() }
        })
        output = TextView(this).apply { textSize = 15f; text = "Ready"; setTextIsSelectable(true) }
        layout.addView(output)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun encrypt() {
        clearSecrets()
        val plaintext = input.text.toString().toByteArray(Charsets.UTF_8)
        if (plaintext.isEmpty() || plaintext.size > 2048) {
            plaintext.fill(0)
            output.text = "Message must be 1–2048 UTF-8 bytes"
            return
        }
        val pad = ByteArray(plaintext.size).also(random::nextBytes)
        val auth = ByteArray(32).also(random::nextBytes)
        val ciphertext = ByteArray(plaintext.size) { i ->
            (plaintext[i].toInt() xor pad[i].toInt()).toByte()
        }
        val tag = mac(auth, ciphertext)
        // The simulated receiver gets an independent copy before sender wipes its local bytes.
        receiverPad = pad.copyOf()
        receiverAuth = auth.copyOf()
        packet = Packet(ciphertext, tag)
        plaintext.fill(0)
        pad.fill(0)
        auth.fill(0)
        output.text = "Ciphertext (Base64):\n" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP) +
            "\n\nAuthentication tag:\n" +
            Base64.encodeToString(tag, Base64.NO_WRAP) +
            "\n\nTap Open once as receiver."
    }

    private fun decrypt() {
        val p = packet ?: run { output.text = "Encrypt first"; return }
        val pad = receiverPad ?: run { output.text = "Key already consumed"; return }
        val auth = receiverAuth ?: run { output.text = "Authentication key unavailable"; return }
        val expected = mac(auth, p.cipher)
        val valid = MessageDigest.isEqual(expected, p.tag)
        expected.fill(0)
        if (!valid) {
            output.text = "Authentication failed"
            clearSecrets()
            return
        }
        val plaintext = ByteArray(p.cipher.size) { i ->
            (p.cipher[i].toInt() xor pad[i].toInt()).toByte()
        }
        output.text = "Received message:\n${String(plaintext, Charsets.UTF_8)}\n\nKey consumed; cannot reopen."
        plaintext.fill(0)
        clearSecrets()
    }

    private fun mac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun clearSecrets() {
        receiverPad?.fill(0)
        receiverAuth?.fill(0)
        receiverPad = null
        receiverAuth = null
        packet = null
    }

    private fun clear() {
        clearSecrets()
        input.text.clear()
        output.text = "Cleared (not forensic erasure)"
    }

    override fun onDestroy() {
        clearSecrets()
        super.onDestroy()
    }
}
