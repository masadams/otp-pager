package org.example.otppager

import android.app.Activity
import android.app.AlertDialog
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

internal fun readNfcSetupPayload(card: IsoDep): String {
    card.timeout = 3000; card.connect()
    check(card.transceive(NfcSetupExchange.select).contentEquals(byteArrayOf(0x90.toByte(), 0)))
    val buffer = java.io.ByteArrayOutputStream()
    while (buffer.size() <= 1024) {
        val at = buffer.size()
        val chunk = card.transceive(byteArrayOf(0x80.toByte(), 0xca.toByte(), (at shr 8).toByte(), at.toByte(), 192.toByte()))
        check(chunk.size in 2..194 && chunk[chunk.size - 2] == 0x90.toByte() && chunk.last() == 0.toByte())
        buffer.write(chunk, 0, chunk.size - 2)
        if (chunk.size < 194) break
    }
    require(buffer.size() <= 1024)
    val bytes = buffer.toByteArray()
    return try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
}

/** Explicit, short-lived setup sharing, like the secret QR. Never exports operational pads. */
object NfcSetupExchange {
    val select = byteArrayOf(0, 0xa4.toByte(), 4, 0, 7, 0xf0.toByte(), 0x46, 0x49, 0x45, 0x4c, 0x44, 1, 0)
    @Volatile var payload: ByteArray? = null
    @Volatile var expires = 0L
    @Volatile var owner: String? = null
        private set
    @Synchronized fun clear() { payload?.fill(0); payload = null; expires = 0; owner = null }
    @Synchronized fun publish(bytes: ByteArray, until: Long, key: String) {
        clear(); payload = bytes; expires = until; owner = key
    }
    @Synchronized fun clearFor(key: String) { if (owner == key) clear() }
}
class NfcSetupCard : HostApduService() {
    private var selected = false
    override fun processCommandApdu(command: ByteArray, extras: Bundle?): ByteArray {
        if (NfcSetupExchange.owner?.let { key ->
                ContactOperations.isStopped(key) || java.io.File(key).parentFile?.let(GlobalRetirement::isFrozen) == true
            } == true) return byteArrayOf(0x69, 0x85.toByte())
        val payload = NfcSetupExchange.payload
        if (payload == null || android.os.SystemClock.elapsedRealtime() >= NfcSetupExchange.expires) return byteArrayOf(0x69, 0x85.toByte())
        if (command.contentEquals(NfcSetupExchange.select)) { selected = true; return byteArrayOf(0x90.toByte(), 0) }
        if (!selected || command.size != 5 || command[0] != 0x80.toByte() || command[1] != 0xca.toByte() || command[4] != 192.toByte()) return byteArrayOf(0x6d, 0)
        val at = ((command[2].toInt() and 255) shl 8) or (command[3].toInt() and 255)
        if (at > payload.size) return byteArrayOf(0x6b, 0)
        return payload.copyOfRange(at, minOf(at + 192, payload.size)) + byteArrayOf(0x90.toByte(), 0)
    }
    override fun onDeactivated(reason: Int) { selected = false }
}
class NfcSetupActivity : Activity(), NfcAdapter.ReaderCallback {
    private val handler = Handler(Looper.getMainLooper())
    private var adapter: NfcAdapter? = null
    private var active = false
    private var sharing = false
    private var operation: ContactOperations.Lease? = null
    private var operationKey: String? = null
    @Volatile private var retired = false
    private lateinit var chat: String
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.protectPrivateContent()
        chat = intent.getStringExtra("chat") ?: run { finish(); return }
        try {
            val store = SecureStore(this, chat); operationKey = store.operationKey
            operation = store.operation().also { lease -> lease.onCancel {
                retired = true; NfcSetupExchange.clearFor(store.operationKey)
                runOnUiThread { if (active) finish() }
            } }
        } catch (_: Exception) { finish(); return }
        adapter = NfcAdapter.getDefaultAdapter(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 36, 24, 24) }
        fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setPadding(0, 8, 0, 16) }
        root.addView(label("Tap to share setup", 24f))
        root.addView(label("NFC shares the relay setup and pairing secret, like the setup QR. It does not transfer a pad or verify a device. Finish physical pairing by USB. Share only with your intended friend.", 16f))
        status = label(if (adapter == null) "NFC is not available. Use USB or QR." else "Choose Share on one device and Read on the other. Both screens must stay open and unlocked.", 15f); root.addView(status)
        root.addView(Button(this).apply { text = "Share setup · 60 seconds"; isEnabled = adapter != null && packageManager.hasSystemFeature("android.hardware.nfc.hce"); setOnClickListener { share() } })
        root.addView(Button(this).apply { text = "Read other device"; isEnabled = adapter != null; setOnClickListener {
            sharing = false; operationKey?.let(NfcSetupExchange::clearFor)
            if (adapter?.isEnabled != true) { status.text = "Turn NFC on in Android settings first."; return@setOnClickListener }
            adapter?.enableReaderMode(this@NfcSetupActivity, this@NfcSetupActivity, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
            status.text = "Hold the devices close until setup is read. You will review it before saving."
        } })
        root.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() { super.onResume(); if (!AppLock.gate(this)) return; if (operation == null || retired || operation?.cancelled == true) { finish(); return }; active = true }
    private fun share() {
        if (adapter?.isEnabled != true) { status.text = "Turn NFC on in Android settings first."; return }
        adapter?.disableReaderMode(this)
        StationRuntime.worker.execute {
            try {
                operation?.check()
                val engine = ChatBook(this).engine(chat); engine.requireCableEligible()
                val bytes = engine.pairingInvite().encode().toByteArray(Charsets.UTF_8)
                runOnUiThread {
                    if (active) {
                        if (operation?.publish { NfcSetupExchange.publish(bytes, android.os.SystemClock.elapsedRealtime() + 60000, checkNotNull(operationKey)) } != true) {
                            bytes.fill(0); return@runOnUiThread
                        }
                        sharing = true; status.text = "Sharing secret setup for 60 seconds. Hold the reading device close. USB is still needed for the pad and identities."
                        handler.postDelayed({ if (android.os.SystemClock.elapsedRealtime() >= NfcSetupExchange.expires) { operationKey?.let(NfcSetupExchange::clearFor); sharing = false; if (active) status.text = "Sharing stopped." } }, 60000)
                    } else bytes.fill(0)
                }
            } catch (e: Exception) { runOnUiThread { if (active) status.text = e.message ?: "Setup unavailable" } }
        }
    }
    override fun onTagDiscovered(tag: Tag) {
        try {
            val card = IsoDep.get(tag) ?: error("Other device does not support this NFC setup method")
            val text = SecureStore(this, chat).operation().use { readOperation ->
                readOperation.onCancel { card.close() }
                card.use { readNfcSetupPayload(it) }.also { readOperation.check() }
            }
            val invite = PairingInvite.decode(text)
            runOnUiThread {
                if (!active || sharing || retired || operation?.cancelled == true) return@runOnUiThread
                adapter?.disableReaderMode(this)
                val dialog = AlertDialog.Builder(this).setTitle("Use this setup?")
                    .setMessage("Relay: ${invite.endpoint}\nSetup token and pairing secret included. No pad or private device key included.\n\nOnly accept from your intended physical device. Continue by USB for full pairing.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Save setup") { _, _ ->
                        StationRuntime.worker.execute {
                            try { ChatBook(this).engine(chat).acceptInvite(invite); runOnUiThread { if (active) { status.text = "Setup saved. Open USB pairing on both devices." } } }
                            catch (_: Exception) { runOnUiThread { if (active) status.text = "Existing pairing preserved; use an unused chat for NFC setup." } }
                        }
                    }.create()
                dialog.window?.protectPrivateContent(); dialog.show()
            }
        } catch (_: Exception) { runOnUiThread { if (active) status.text = "NFC setup could not be read. Keep the devices close, or use USB/QR." } }
    }
    override fun onStop() { active = false; operation?.close(); operation = null; operationKey?.let(NfcSetupExchange::clearFor); adapter?.disableReaderMode(this); handler.removeCallbacksAndMessages(null); if (!isFinishing) finish(); super.onStop() }
}
