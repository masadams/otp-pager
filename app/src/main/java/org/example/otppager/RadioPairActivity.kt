package org.example.otppager

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit foreground-only local Wi-Fi transfer, bootstrapped by a secret NFC tap. */
class RadioPairActivity : Activity(), NfcAdapter.ReaderCallback {
    private val handler = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private var operation: ContactOperations.Lease? = null
    private var operationKey: String? = null
    private val reading = AtomicBoolean(false)
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var ticket: RadioTicket? = null
    @Volatile private var confirmation: CountDownLatch? = null
    @Volatile private var approved = false
    @Volatile private var authenticated = false
    @Volatile private var complete = false
    private var active = false
    private var adapter: NfcAdapter? = null
    private lateinit var chat: String
    private var renew = false
    private var size = PadProtocol.MATERIAL_BYTES
    private lateinit var status: TextView
    private lateinit var share: Button
    private lateinit var read: Button
    private lateinit var confirm: Button
    private lateinit var done: Button
    private lateinit var padChoice: Button
    private fun note(text: String) = runOnUiThread { if (active && !stopped.get() && operation?.cancelled != true) status.text = text }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chat = intent.getStringExtra("chat") ?: run { finish(); return }
        renew = intent.getBooleanExtra("renew", false)
        size = intent.getIntExtra("padSize", PadProtocol.MATERIAL_BYTES)
        if (!PadProtocol.validSize(size)) { finish(); return }
        try {
            val store = SecureStore(this, chat); operationKey = store.operationKey
            operation = store.operation().also { lease -> lease.onCancel {
                stopped.set(true); confirmation?.countDown()
                runOnUiThread { if (active) finish() }
                closeLinks()
            } }
        } catch (_: Exception) { finish(); return }
        StationRuntime.pairingOpen = true
        window.protectPrivateContent(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        adapter = NfcAdapter.getDefaultAdapter(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24); setBackgroundColor(Color.WHITE) }
        fun label(text: String, sp: Float = 16f) = TextView(this).apply {
            this.text = text; textSize = sp; setTextColor(Color.BLACK); typeface = Typeface.MONOSPACE; setPadding(0, 8, 0, 16)
        }
        root.addView(label(if (renew) "Renew pad nearby" else "Pair nearby", 24f))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("Use the same private Wi-Fi on both devices. Share on one, Read on the other. Tap, then compare codes."))
        status = label("Ready · same app version required on both", 14f); body.addView(status)
        padChoice = Button(this).apply { text = "Pad size · ${size / PadProtocol.MATERIAL_BYTES} MiB"; setOnClickListener {
            val sizes = PadProtocol.SIZES
            var selected = sizes.indexOf(size)
            val dialog = AlertDialog.Builder(this@RadioPairActivity).setTitle("Choose a pad size")
                .setSingleChoiceItems(sizes.map(PadProtocol::sizeLabel).toTypedArray(), selected) { _, which -> selected = which }
                .setNegativeButton("Cancel", null).setPositiveButton("Use this size") { _, _ ->
                    size = sizes[selected]; text = "Pad size · ${size / PadProtocol.MATERIAL_BYTES} MiB"
                }.create()
            dialog.show(); dialog.window?.protectPrivateContent()
        } }; body.addView(padChoice)
        share = Button(this).apply { text = "Share · tap within 60 seconds"; isEnabled = adapter != null && packageManager.hasSystemFeature("android.hardware.nfc.hce"); setOnClickListener { startSharing() } }; body.addView(share)
        read = Button(this).apply { text = "Read other device"; isEnabled = adapter != null; setOnClickListener { startReading() } }; body.addView(read)
        body.addView(label("Keep the tap private. USB is also available.", 13f))
        body.addView(Button(this).apply { text = "About wireless security"; setOnClickListener {
            val dialog = AlertDialog.Builder(this@RadioPairActivity).setTitle("Wireless pairing")
                .setMessage("The tap shares a temporary transfer secret. NFC can be intercepted or relayed at close range. Wi-Fi carries authenticated encrypted data; the relay server receives no pad. Always compare all code groups on both physical screens.\n\nThis experimental option uses computational encryption and is not independently audited or claimed post-quantum secure. Use USB for the most controlled physical exchange. The sharing device selects the pad size.")
                .setPositiveButton("Close", null).create()
            dialog.show(); dialog.window?.protectPrivateContent()
        } })
        if (adapter == null) body.addView(label("NFC unavailable. Return to chat settings and pair by USB."))
        confirm = Button(this).apply { text = if (renew) "Codes match · Renew" else "Codes match · Pair"; isEnabled = false; filterTouchesWhenObscured = true
            setOnClickListener { approved = true; isEnabled = false; confirmation?.countDown() } }; root.addView(confirm)
        done = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }; root.addView(done)
        setContentView(root)
        handler.postDelayed({ if (!stopped.get() && !complete) { note("Transfer timed out. Close and retry on both devices."); closeLinks(); confirm.isEnabled = false } }, 180000)
    }
    override fun onResume() { super.onResume(); if (operation == null || stopped.get() || operation?.cancelled == true) { finish(); return }; active = true }
    private fun ready(): Boolean {
        if (adapter?.isEnabled != true) { status.text = "Turn NFC on in Android settings first."; return false }
        return true
    }
    private fun lockChoices() { share.isEnabled = false; read.isEnabled = false; padChoice.isEnabled = false }
    @Suppress("DEPRECATION")
    private fun wifi(): Pair<Network, Inet4Address> {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in manager.allNetworks) {
            if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) continue
            val address = manager.getLinkProperties(network)?.linkAddresses?.map { it.address }
                ?.filterIsInstance<Inet4Address>()?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            if (address != null) return network to address
        }
        error("Connect both devices to the same private Wi-Fi first. IPv4 is required; guest Wi-Fi may block device connections.")
    }
    private fun startSharing() {
        if (!ready()) return
        lockChoices(); adapter?.disableReaderMode(this)
        StationRuntime.worker.execute {
            try {
                operation?.check()
                val engine = ChatBook(this).engine(chat)
                if (renew) engine.requireRenewalIdentity() else engine.requireCableEligible()
                val (_, address) = wifi()
                val server = ServerSocket(); listener = server
                operation?.check()
                server.reuseAddress = false; server.bind(InetSocketAddress(address, 0)); server.soTimeout = 1000
                if (stopped.get()) { server.close(); return@execute }
                val offer = RadioTicket.create(address.hostAddress!!, server.localPort); ticket = offer
                val expires = SystemClock.elapsedRealtime() + 60000
                val payload = offer.encode().toByteArray(Charsets.UTF_8)
                runOnUiThread {
                    if (active && !stopped.get()) {
                        if (operation?.publish { NfcSetupExchange.publish(payload, expires, checkNotNull(operationKey)) } == true)
                            status.text = "Ready to tap · choose Read on the other device. Both must use the same Wi-Fi."
                        else payload.fill(0)
                    } else payload.fill(0)
                }
                while (!stopped.get() && operation?.cancelled != true && SystemClock.elapsedRealtime() < expires) {
                    val connection = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    socket = connection; connection.soTimeout = 5000
                    if (stopped.get() || SystemClock.elapsedRealtime() >= expires) { connection.close(); break }
                    val envelope = try { RadioHandoff.establish(true, connection.getInputStream(), connection.getOutputStream(), offer) }
                        catch (_: Exception) { connection.close(); socket = null; continue }
                    if (stopped.get() || SystemClock.elapsedRealtime() >= expires) { envelope.close(); connection.close(); break }
                    authenticated = true
                    server.close(); listener = null
                    offer.close(); ticket = null
                    runOnUiThread { operationKey?.let(NfcSetupExchange::clearFor) }
                    connection.soTimeout = 100000
                    envelope.use { transfer(true, it, engine) }
                    return@execute
                }
                error("Tap expired. Close this screen and start again.")
            } catch (e: Exception) { failed(e) }
            finally { closeLinks() }
        }
    }
    private fun startReading() {
        if (!ready()) return
        lockChoices(); operationKey?.let(NfcSetupExchange::clearFor)
        adapter?.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        status.text = "Touch the sharing device's NFC antenna. Then approve the connection shown here."
    }
    override fun onTagDiscovered(tag: Tag) {
        if (!reading.compareAndSet(false, true) || stopped.get()) return
        try {
            val card = IsoDep.get(tag) ?: error("NFC card mode unavailable")
            val offer = SecureStore(this, chat).operation().use { readOperation ->
                readOperation.onCancel { card.close() }
                card.use { RadioTicket.decode(readNfcSetupPayload(it)) }.also {
                    try { readOperation.check() } catch (failure: Exception) { it.close(); throw failure }
                }
            }
            runOnUiThread {
                if (!active || stopped.get() || operation?.cancelled == true) { offer.close(); return@runOnUiThread }
                adapter?.disableReaderMode(this)
                val dialog = AlertDialog.Builder(this).setTitle("Connect to this device?")
                    .setMessage("Local Wi-Fi: ${offer.address}\n\nOnly continue with the physical device you just tapped. You will compare confirmation codes before any pad or relay credentials transfer.")
                    .setNegativeButton("Cancel") { _, _ -> offer.close(); finish() }
                    .setOnCancelListener { offer.close(); finish() }
                    .setPositiveButton("Connect encrypted") { _, _ -> connect(offer) }.create()
                dialog.show(); dialog.window?.protectPrivateContent()
                ticket = offer
            }
        } catch (_: Exception) {
            reading.set(false); note("Could not read a wireless offer. Select Share nearby on the other device, then tap again. USB remains available.")
        }
    }
    private fun connect(offer: RadioTicket) {
        StationRuntime.worker.execute {
            try {
                check(!stopped.get())
                operation?.check()
                val engine = ChatBook(this).engine(chat)
                if (renew) engine.requireRenewalIdentity() else engine.requireCableEligible()
                val (network, _) = wifi()
                val connection = network.socketFactory.createSocket(); socket = connection
                operation?.check()
                if (stopped.get()) { connection.close(); return@execute }
                note("Connecting over local Wi-Fi…")
                connection.connect(InetSocketAddress(offer.address, offer.port), 8000); connection.soTimeout = 8000
                RadioHandoff.establish(false, connection.getInputStream(), connection.getOutputStream(), offer).use {
                    authenticated = true; offer.close(); ticket = null; connection.soTimeout = 100000
                    transfer(false, it, engine)
                }
            } catch (e: Exception) { failed(e) }
            finally { closeLinks() }
        }
    }
    private fun transfer(host: Boolean, envelope: RadioHandoff, engine: StationEngine) {
        operation?.check()
        CableProtocol.run(host, envelope.input, envelope.output, engine, review = { code, endpoint ->
            val latch = CountDownLatch(1); confirmation = latch
            if (stopped.get() || operation?.cancelled == true) latch.countDown() // Covers cancellation just before the latch was registered.
            runOnUiThread { if (active && !stopped.get()) {
                status.text = "Compare ALL groups on both devices:\n\n$code\n\n$endpoint\n\nApprove only if every group matches your friend's physical screen."
                confirm.isEnabled = true
            } }
            check(latch.await(90, TimeUnit.SECONDS) && approved && !stopped.get() && operation?.cancelled != true) { "Confirmation cancelled" }
        }, progress = { note(it.replace("USB", "local Wi-Fi")) }, renew = renew, size = size, strict = if (renew) 0L else intent.getLongExtra("strict", 0L))
        operation?.check()
        complete = true
        runOnUiThread { if (active && !stopped.get()) { done.text = "Done · open messages"; confirm.isEnabled = false } }
    }
    private fun failed(error: Exception) {
        note(if (!authenticated) error.message ?: "Connection unavailable. Close and retry, or use USB."
            else if (renew) "Renewal interrupted. Keep both devices offline; reopen Renew for this friend on BOTH devices and reconnect by USB or nearby transfer to recover. History and identity are retained."
            else "Pairing interrupted. Before use, start a fresh pairing on BOTH devices. Any exposed candidate pad must not be reused.")
        runOnUiThread { if (active) confirm.isEnabled = false }
    }
    private fun closeLinks() {
        try { listener?.close() } catch (_: Exception) {}; listener = null
        try { socket?.close() } catch (_: Exception) {}; socket = null
        ticket?.close(); ticket = null; confirmation?.countDown()
        operationKey?.let(NfcSetupExchange::clearFor)
    }
    // Explicit Cancel/Back closes sensitive resources before Android's asynchronous onStop.
    override fun finish() {
        stopped.set(true); operation?.close(); operation = null; closeLinks()
        super.finish()
    }
    override fun onStop() {
        active = false; stopped.set(true); operation?.close(); operation = null; closeLinks(); adapter?.disableReaderMode(this); handler.removeCallbacksAndMessages(null)
        StationRuntime.worker.execute { StationRuntime.pairingOpen = false }
        if (!isFinishing) finish()
        super.onStop()
    }
}
