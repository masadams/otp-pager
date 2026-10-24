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
    private fun note(value: String) { runOnUiThread { if (active && !cancelled.get()) status.text = value } }
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
        StationRuntime.pairingOpen = true
        window.protectPrivateContent()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        manager = getSystemService(UsbManager::class.java)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 24); setBackgroundColor(Color.WHITE) }
        fun label(value: String, size: Float = 17f) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.BLACK); typeface = Typeface.MONOSPACE; setPadding(0, 12, 0, 20) }
        root.addView(label(if (renewal) "RENEW CHAT PAD" else "PAIR DEVICES", 25f))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body) }; root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("Open this screen on both devices, then connect a USB data cable. At least one station must have its relay address and access token saved. Approve Android's USB prompts. After both confirm, roles, fresh secret, pad and identities transfer automatically over an encrypted USB session. Both devices need version 0.11 or later. The USB host selects the pad size."))
        if (renewal) body.addView(label("Select Renew pad for the SAME friend on both devices. Outstanding messages are confirmed over USB before renewal. Your history and verified identities stay in place. If interrupted, reconnect both on this screen to recover. Pad choice: ${padSize / PadProtocol.MATERIAL_BYTES} MiB."))
        status = label("Waiting for the other device…"); body.addView(status)
        confirm = Button(this).apply { text = if (renewal) "Codes match · Renew pad" else "Codes match · Pair devices"; isEnabled = false; filterTouchesWhenObscured = true
            setOnClickListener { approved = true; isEnabled = false; confirmation?.countDown() } }; root.addView(confirm)
        finish = Button(this).apply { text = "Cancel"; setOnClickListener { finish() } }; root.addView(finish)
        body.addView(label("A data-capable cable and compatible USB host/accessory modes are required. No USB debugging. Cancel if the full confirmation codes differ.", 13f))
        setContentView(root)
        val events = IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED) }
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, events, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        expireAt = android.os.SystemClock.elapsedRealtime() + 180000
    }
    override fun onResume() { super.onResume(); active = true; handler.post(poll) }
    private fun permission(): PendingIntent = PendingIntent.getBroadcast(this, 90, Intent(permissionAction).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    @Suppress("DEPRECATION")
    private fun inspect() {
        if (busy || awaitingPermission || cancelled.get()) return
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
                try { UsbPairChannel.switchToAccessory(manager, device); runOnUiThread { busy = false; note("USB mode changed · waiting for reconnection/permission…") } }
                catch (_: Exception) { note("Could not establish Android accessory mode. Try reversing USB host direction or use QR setup.") }
            }
        }
    }
    private fun startTransfer(host: Boolean, open: () -> UsbPairChannel) {
        busy = true; note("Connected by USB · exchanging setup…")
        worker.execute {
            var engine: StationEngine? = null
            try {
                val station = ChatBook(this).engine(chatId); engine = station
                if (renewal) station.requireRenewalIdentity() else station.requireCableEligible()
                val link = open(); channel = link
                if (cancelled.get()) { link.close(); error("Cancelled") }
                link.use {
                    CableProtocol.run(host, it.input, it.output, station, review = { code, endpoint ->
                        val latch = CountDownLatch(1); confirmation = latch
                        runOnUiThread { if (active && !cancelled.get()) {
                            status.text = "COMPARE ON BOTH DEVICES\\n\\n$code\\n\\nRelay: $endpoint\\n\\nConfirm only if ALL groups match the other physical device. This verifies the pad and both device identities together.".replace("\\n", "\n")
                            confirm.isEnabled = true
                        } }
                        check(latch.await(90, TimeUnit.SECONDS) && approved && !cancelled.get()) { "Confirmation cancelled or timed out" }
                    }, progress = ::note, renew = renewal, size = padSize)
                }
                runOnUiThread { if (active && !cancelled.get()) { finish.text = "Done · open messages"; confirm.isEnabled = false } }
            } catch (failure: Exception) {
                val saved = engine?.state?.has("pad") == true
                note(if (failure is CableProtocol.UpgradeRequired) "${failure.message}. Close this screen and update both devices before retrying."
                    else if (renewal) "Renewal paused. History and identities are retained. Reopen Renew pad for this friend on BOTH devices and reconnect USB to recover. No saved message is silently discarded."
                    else if (saved) "Connection interrupted after saving locally. Keep both devices offline and start a FRESH pairing on BOTH before use; do not reuse the transferred pad."
                    else "Pairing did not finish. No link was activated here. Close and retry; any exposed pad is retired automatically.")
                runOnUiThread { confirm.isEnabled = false }
            } finally { channel?.close(); channel = null; confirmation = null }
        }
    }
    override fun onStop() {
        active = false; cancelled.set(true); handler.removeCallbacksAndMessages(null); confirmation?.countDown(); channel?.close()
        worker.execute { StationRuntime.pairingOpen = false }
        if (!isFinishing) finish()
        super.onStop()
    }
    override fun onDestroy() { try { unregisterReceiver(receiver) } catch (_: Exception) {}; super.onDestroy() }
}
