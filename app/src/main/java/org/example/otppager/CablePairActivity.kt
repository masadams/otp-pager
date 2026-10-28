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
    private lateinit var discovery: UsbPairDiscovery
    private lateinit var retryPermission: Button
    private var active = false
    @Volatile private var channel: UsbPairChannel? = null
    @Volatile private var confirmation: CountDownLatch? = null
    @Volatile private var approved = false
    private lateinit var chatId: String
    private var renewal = false
    private var padSize = PadProtocol.MATERIAL_BYTES
    private val permissionAction get() = "$packageName.USB_PAIR_PERMISSION"
    private fun note(value: String) { log(value); runOnUiThread { if (active && !cancelled.get() && operation?.cancelled != true) status.text = value } }
    /** Step log for physical-device diagnosis. Never records codes, server details, keys or serial numbers. */
    private val steps = java.util.ArrayDeque<String>()
    private val started = android.os.SystemClock.elapsedRealtime()
    private var details: TextView? = null
    private var lastLogged = ""
    private fun log(value: String) {
        if (value == lastLogged) return
        lastLogged = value
        val line = "%6.1fs %s".format((android.os.SystemClock.elapsedRealtime() - started) / 1000.0, value)
        synchronized(steps) { steps.addLast(line); while (steps.size > 80) steps.removeFirst() }
        runOnUiThread { details?.text = synchronized(steps) { steps.joinToString("\n") } }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action in listOf(UsbManager.ACTION_USB_DEVICE_DETACHED, UsbManager.ACTION_USB_ACCESSORY_DETACHED) && channel != null) {
                channel?.close(); confirmation?.countDown()
                note("USB disconnected · pairing stopped")
                return
            }
            if (intent.action != permissionAction) log("Event: ${intent.action?.substringAfterLast('.')}")
            if (intent.action == permissionAction) {
                log("Permission result: ${if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) "granted" else "denied"}")
                if (discovery.permissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))) inspect()
                retryPermission.isEnabled = discovery.state == UsbPairDiscovery.State.DENIED
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
        discovery = UsbPairDiscovery(android.os.SystemClock::elapsedRealtime,
            { active && !cancelled.get() && operation?.cancelled == false },
            { task -> worker.execute { task() } }, { task -> runOnUiThread { task() } }, ::note)
        retryPermission = Button(this).apply {
            text = "Retry USB permission"; isEnabled = false
            setOnClickListener { isEnabled = false; if (discovery.retryPermission()) inspect() }
        }
        body.addView(retryPermission)
        details = label("", 11f).apply { visibility = android.view.View.GONE }
        body.addView(Button(this).apply { text = "USB details"; setOnClickListener {
            details?.let { it.visibility = if (it.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        } })
        body.addView(Button(this).apply { text = "Copy USB details"; setOnClickListener {
            val text = synchronized(steps) { steps.joinToString("\n") }
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("OTP Pager USB details", text))
            log("Details copied")
        } })
        body.addView(details)
        log("Start · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · app ${packageManager.getPackageInfo(packageName, 0).versionName} · protocol v${CableProtocol.VERSION} · ${if (renewal) "renewal" else "pairing"} · ${padSize / PadProtocol.MATERIAL_BYTES} MiB")
        log("USB host feature: ${packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST)} · accessory feature: ${packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_ACCESSORY)}")
        body.addView(Button(this).apply { text = "About USB pairing"; setOnClickListener {
            val dialog = AlertDialog.Builder(this@CablePairActivity).setTitle("Physical pairing")
                .setMessage("One device needs the server address and access token saved. Approve Android's USB permission prompts. Compare every group of the confirmation code on BOTH physical screens.\n\nAfter both approve, the encrypted connection exchanges server setup, fresh message keys and public device identities. Private signing keys remain on their own device. USB host direction selects pad size. Both devices need the same app version.\n\nA data cable and compatible Android USB host/accessory modes are required. This experimental protocol uses conventional encryption and is not independently audited or post-quantum secure. If renewal is interrupted, reconnect both devices on this screen to recover.")
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
    }
    override fun onResume() { super.onResume(); if (operation == null || cancelled.get() || operation?.cancelled == true) { finish(); return }; active = true; handler.post(poll) }
    private fun permission(): PendingIntent = PendingIntent.getBroadcast(this, 90, Intent(permissionAction).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    @Suppress("DEPRECATION")
    private fun inspect() {
        if (!discovery.inspectable()) return
        val accessories = manager.accessoryList.orEmpty()
        val accessory = accessories.firstOrNull { it.manufacturer == "Field" && it.model == "OTP Pager Pairing" }
        val devices = manager.deviceList.values.toList()
        log("Scan · accessory: ${if (accessories.isEmpty()) "none" else accessories.joinToString { "${it.manufacturer}/${it.model}" }} · devices: ${if (devices.isEmpty()) "none" else devices.joinToString(transform = UsbPairChannel::describe)}")
        if (accessory != null) {
            if (!manager.hasPermission(accessory)) { log("Requesting accessory permission"); discovery.requestPermission { manager.requestPermission(accessory, permission()) }; retryPermission.isEnabled = discovery.state == UsbPairDiscovery.State.DENIED; return }
            startTransfer(false) { UsbPairChannel.accessory(manager, accessory) }; return
        }
        if (devices.size > 1) { note("Connect only the intended other station; disconnect other USB peripherals."); return }
        val device = devices.singleOrNull() ?: return
        if (!manager.hasPermission(device)) { log("Requesting device permission"); discovery.requestPermission { manager.requestPermission(device, permission()) }; retryPermission.isEnabled = discovery.state == UsbPairDiscovery.State.DENIED; return }
        if (UsbPairChannel.isAccessory(device)) startTransfer(true) { UsbPairChannel.host(manager, device) }
        else {
            val lease = operation ?: return
            discovery.negotiate {
                check(!cancelled.get()); lease.check()
                log("Asking the other phone to switch to accessory mode")
                try { log("Other phone accepted accessory mode (AOA v${UsbPairChannel.switchToAccessory(manager, device)}); it should reconnect now") }
                catch (failure: Exception) { log("Accessory mode failed: ${failure.message}"); throw failure }
                lease.check()
            }
        }
    }
    private fun startTransfer(host: Boolean, open: () -> UsbPairChannel) {
        if (!discovery.startTransfer()) return
        log("Opening data channel as ${if (host) "USB host" else "accessory"}")
        note("Connected by USB · exchanging setup…")
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
                    }, progress = ::note, renew = renewal, size = padSize, strict = if (renewal) 0L else intent.getLongExtra("strict", 0L))
                }
                runOnUiThread { if (active && !cancelled.get()) { status.text = if (renewal) "Keys renewed. Your chat is ready." else "Paired. You're ready to chat."; finish.text = "Open conversation"; confirm.isEnabled = false } }
            } catch (failure: Exception) {
                log("Transfer stopped: ${failure.javaClass.simpleName}: ${failure.message?.take(160)}")
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
        if (::discovery.isInitialized) discovery.stop()
        active = false; cancelled.set(true); operation?.close(); operation = null; handler.removeCallbacksAndMessages(null); confirmation?.countDown(); channel?.close()
        worker.execute { StationRuntime.pairingOpen = false }
        if (!isFinishing) finish()
        super.onStop()
    }
    override fun onDestroy() { try { unregisterReceiver(receiver) } catch (_: Exception) {}; super.onDestroy() }
}
