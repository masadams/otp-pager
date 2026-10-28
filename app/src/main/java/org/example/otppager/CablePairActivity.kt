package org.example.otppager

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground-only physical pairing wizard. Never starts merely because a cable is attached. */
class CablePairActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = StationRuntime.worker
    private val cancelled = AtomicBoolean(false)
    private var operation: ContactOperations.Lease? = null
    private lateinit var manager: UsbManager
    private lateinit var status: TextView
    private lateinit var confirm: Button
    private lateinit var finish: Button
    private var awaitingPermission = false
    private var busy = false
    private var active = false
    @Volatile private var channel: UsbPairChannel? = null
    @Volatile private var confirmation: CountDownLatch? = null
    @Volatile private var approved = false
    private var expireAt = 0L
    private lateinit var chatId: String
    private var renewal = false
    private var padSize = PadProtocol.MATERIAL_BYTES
    private val permissionAction get() = "$packageName.USB_PAIR_PERMISSION"
    private fun note(value: String) { runOnUiThread { if (active && !cancelled.get() && operation?.cancelled != true) status.text = value } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action in listOf(UsbManager.ACTION_USB_DEVICE_DETACHED, UsbManager.ACTION_USB_ACCESSORY_DETACHED) && channel != null) {
                channel?.close(); confirmation?.countDown()
                note("USB disconnected · pairing stopped")
                return
            }
            if (intent.action == permissionAction) {
                awaitingPermission = false
                if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    busy = true; note("USB permission declined. Close and retry when ready.")
                } else inspect()
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() { if (active && !cancelled.get()) { inspect(); handler.postDelayed(this, 1000) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renewal = intent.getBooleanExtra("renew", false)
        padSize = intent.getIntExtra("padSize", PadProtocol.MATERIAL_BYTES)
        require(PadProtocol.validSize(padSize))
        chatId = intent.getStringExtra("chat") ?: run { finish(); return }
        try {
            operation = SecureStore(this, chatId).operation().also { lease -> lease.onCancel {
                cancelled.set(true); confirmation?.countDown()
                runOnUiThread { if (active) finish() }
                channel?.close()
            } }
        } catch (_: Exception) { finish(); return }
        StationRuntime.pairingOpen = true
        window.protectPrivateContent()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = getSystemService(UsbManager::class.java)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 24); setBackgroundColor(Color.WHITE) }
        fun label(value: String, size: Float = 17f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.BLACK); typeface = Typeface.MONOSPACE; setPadding(0, 12, 0, 20) }
        root.addView(label(if (renewal) "Renew message keys" else "Pair devices", 25f))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body) }; root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("Open pairing on both devices and connect a USB data cable. Keep both screens open. You will compare codes before anything is paired."))
        if (renewal) body.addView(label("Choose the same friend on both devices. Your saved history and verified device identities stay in place."))
        body.addView(label("Message keys: ${padSize / PadProtocol.MATERIAL_BYTES} MiB", 14f))
        status = label("Waiting for your friend's device…"); body.addView(status)
        body.addView(Button(this).apply { text = "About USB pairing"; setOnClickListener {
            val dialog = AlertDialog.Builder(this@CablePairActivity).setTitle("Physical pairing")
                .setMessage("One device needs the server address and access token saved. Approve Android's USB permission prompts. Compare every group of the confirmation code on BOTH physical screens.\n\nAfter both approve, the encrypted connection exchanges server setup, fresh message keys and public device identities. Private signing keys remain on their own device. USB host direction selects pad size. Both devices need version 0.11 or later.\n\nA data cable and compatible Android USB host/accessory modes are required. This experimental protocol uses conventional encryption and is not independently audited or post-quantum secure. If renewal is interrupted, reconnect both devices on this screen to recover.")
                .setPositiveButton("Close", null).create()
            dialog.window?.protectPrivateContent(); dialog.show()
        } })
        confirm = Button(this).apply { text = if (renewal) "Codes match · Renew" else "Codes match · Pair"; isEnabled = false; filterTouchesWhenObscured = true
            setOnClickListener { approved = true; isEnabled = false; confirmation?.countDown() } }; root.addView(confirm)
        finish = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }; root.addView(finish)
        body.addView(label("Approve Android's USB prompts. Cancel if the full codes differ.", 13f))
        setContentView(root)
        val events = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED) }
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, events, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        expireAt = android.os.SystemClock.elapsedRealtime() + 180000
    }
    override fun onResume() { super.onResume(); if (operation == null || cancelled.get() || operation?.cancelled == true) { finish(); return }; active = true; handler.post(poll) }
    private fun permission(): PendingIntent = PendingIntent.getBroadcast(this, 90, Intent(permissionAction).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    @Suppress("DEPRECATION")
    private fun inspect() {
        if (busy || awaitingPermission || cancelled.get() || operation?.cancelled == true) return
        if (android.os.SystemClock.elapsedRealtime() >= expireAt) { busy = true; note("No compatible USB data connection found. Check cable/USB host direction, then close and retry. QR setup remains available."); return }
        val accessory = manager.accessoryList?.firstOrNull { it.manufacturer == "Field" && it.model == "OTP Pager Pairing" }
        if (accessory != null) {
            if (!manager.hasPermission(accessory)) { awaitingPermission = true; manager.requestPermission(accessory, permission()); return }
            startTransfer(false) { UsbPairChannel.accessory(manager, accessory) }; return
        }
        val devices = manager.deviceList.values.toList()
        if (devices.size > 1) { note("Connect only the intended other station; disconnect other USB peripherals."); return }
        val device = devices.singleOrNull() ?: return
        if (!manager.hasPermission(device)) { awaitingPermission = true; manager.requestPermission(device, permission()); return }
        if (UsbPairChannel.isAccessory(device)) startTransfer(true) { UsbPairChannel.host(manager, device) }
        else {
            busy = true; note("Checking USB accessory support…")
            worker.execute {
                try { check(!cancelled.get()); operation?.check(); UsbPairChannel.switchToAccessory(manager, device); operation?.check(); runOnUiThread { busy = false; note("USB mode changed · waiting for reconnection/permission…") } }
                catch (_: Exception) { note("Could not establish Android accessory mode. Try reversing USB host direction or use QR setup.") }
            }
        }
    }
    private fun startTransfer(host: Boolean, open: () -> UsbPairChannel) {
        busy = true; note("Connected by USB · exchanging setup…")
        worker.execute {
            var engine: StationEngine? = null
            try {
                operation?.check()
                val station = ChatBook(this).engine(chatId); engine = station
                if (renewal) station.requireRenewalIdentity() else station.requireCableEligible()
                val link = open(); channel = link
                operation?.check()
                if (cancelled.get()) { link.close(); error("Cancelled") }
                link.use {
                    CableProtocol.run(host, it.input, it.output, station, review = { code, endpoint ->
                        val latch = CountDownLatch(1); confirmation = latch
                        if (cancelled.get() || operation?.cancelled == true) latch.countDown()
                        runOnUiThread { if (active && !cancelled.get()) {
                            status.text = "Compare every group on both screens:\n\n$code\n\nServer: $endpoint\n\nApprove only if every group matches."
                            confirm.isEnabled = true
                        } }
                        check(latch.await(90, TimeUnit.SECONDS) && approved && !cancelled.get() && operation?.cancelled != true) { "Confirmation cancelled or timed out" }
                    }, progress = ::note, renew = renewal, size = padSize)
                }
                runOnUiThread { if (active && !cancelled.get()) { status.text = if (renewal) "Keys renewed. Your chat is ready." else "Paired. You're ready to chat."; finish.text = "Open conversation"; confirm.isEnabled = false } }
            } catch (failure: Exception) {
                val saved = engine?.state?.let(StationEngine::hasPad) == true
                note(if (failure is CableProtocol.UpgradeRequired) "${failure.message}. Close this screen and update both devices before retrying."
                    else if (renewal) "Renewal paused. History and identities are retained. Reopen Renew pad for this friend on BOTH devices and reconnect USB to recover. No saved message is silently discarded."
                    else if (saved) "Connection interrupted after saving locally. Keep both devices offline and start a FRESH pairing on BOTH before use; do not reuse the transferred pad."
                    else "Pairing did not finish. No link was activated here. Close and retry; any exposed pad is retired automatically.")
                runOnUiThread { confirm.isEnabled = false }
            } finally { channel?.close(); channel = null; confirmation = null }
        }
    }
    override fun onStop() {
        active = false; cancelled.set(true); operation?.close(); operation = null; handler.removeCallbacksAndMessages(null); confirmation?.countDown(); channel?.close()
        worker.execute { StationRuntime.pairingOpen = false }
        if (!isFinishing) finish()
        super.onStop()
    }
    override fun onDestroy() { try { unregisterReceiver(receiver) } catch (_: Exception) {}; super.onDestroy() }
}
