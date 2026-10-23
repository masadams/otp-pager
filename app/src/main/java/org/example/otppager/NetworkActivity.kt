package org.example.otppager

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import java.util.concurrent.Executors

/** Single profile, foreground-only delivery. No background service or analytics. */
class NetworkActivity : Activity() {
    companion object { private val stationWorker = Executors.newSingleThreadExecutor() }
    private val worker = stationWorker
    private val handler = Handler(Looper.getMainLooper())
    private var engine: StationEngine? = null // worker-thread owned
    @Volatile private var generation = 0
    private var busy = false
    @Volatile private var active = false
    @Volatile private var unlocked = false
    private var needsTerminal = true
    private var backoff = 15000L
    private var tab = 0
    private var latest: StationEngine.View? = null
    private var rendered: List<StationEngine.Message>? = null
    private var errorLoading = false
    private var automatic = true
    private var lastContact = 0L
    private var connectionFailed = false
    private var importedDeviceCard: String? = null
    private lateinit var exportDeviceButton: Button
    private lateinit var importDeviceButton: Button
    private var identityScan = false
    private var pendingDeviceScan: String? = null
    private lateinit var showIdentityQrButton: Button
    private lateinit var scanIdentityQrButton: Button
    private var pendingScan: String? = null
    private var pairingDialog: AlertDialog? = null
    private lateinit var showQrButton: Button
    private lateinit var scanQrButton: Button
    private lateinit var imageQrButton: Button
    private var pendingDocument: Pair<Int, android.net.Uri>? = null
    private lateinit var indicators: TextView
    private lateinit var padStatus: TextView
    private lateinit var padScroll: ScrollView
    private lateinit var deviceTab: Button
    private lateinit var deviceScroll: ScrollView
    private lateinit var identityStatus: TextView
    private lateinit var identityCard: TextView
    private lateinit var peerCardField: EditText
    private lateinit var prepareDeviceButton: Button
    private lateinit var pinDeviceButton: Button
    private lateinit var padTab: Button
    private lateinit var verifyButton: Button
    private lateinit var createPadButton: Button
    private lateinit var importPadButton: Button
    private lateinit var newMessagesButton: Button
    private lateinit var setupHint: TextView
    private lateinit var root: LinearLayout
    private lateinit var traffic: LinearLayout
    private lateinit var linkScroll: ScrollView
    private lateinit var messages: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var stationLabel: TextView
    private lateinit var pairingCode: TextView
    private lateinit var composer: EditText
    private lateinit var count: TextView
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var secretField: EditText
    private lateinit var roleGroup: RadioGroup
    private lateinit var sendButton: Button
    private lateinit var saveButton: Button
    private lateinit var saveRelayButton: Button
    private lateinit var syncButton: Button
    private lateinit var trafficTab: Button
    private lateinit var linkTab: Button
    private val paper = Color.WHITE
    private val ink = Color.rgb(20, 20, 20)
    private val muted = Color.rgb(70, 70, 70)
    private val green = Color.rgb(30, 30, 30)
    private val lineColor = Color.rgb(215, 217, 205)
    private val poll = object : Runnable {
        override fun run() {
            if (active && unlocked) updateIndicators()
            if (active && unlocked && automatic && !busy && !errorLoading && latest != null && latest?.role != "—") runTask { engine?.let { it.sync() } ?: "OPEN LINK TO PAIR" }
            else if (active && unlocked) handler.postDelayed(this, 5000)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 14f, color: Int = ink, mono: Boolean = false) = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun surface(color: Int, radius: Int = 8, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(2).toFloat(); border?.let { setStroke(dp(1), it) }
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; typeface = Typeface.MONOSPACE
        minHeight = dp(44); minimumHeight = dp(44); setTextColor(if (primary) Color.WHITE else ink)
        backgroundTintList = ColorStateList.valueOf(if (primary) green else Color.TRANSPARENT)
        filterTouchesWhenObscured = true
        setOnClickListener { if (!busy) action() }
    }
    private fun field(hint: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint; textSize = 15f; setTextColor(ink); setHintTextColor(muted)
        typeface = Typeface.MONOSPACE
        inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
        // setSingleLine installs a transformation; restore password masking afterward.
        if (password) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = surface(Color.WHITE, 6, lineColor)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        isSaveEnabled = false; filterTouchesWhenObscured = true
    }
    private fun section(parent: LinearLayout, label: String, field: View) {
        parent.addView(text(label, 11f, muted, true).apply { setPadding(0, dp(18), 0, dp(7)) })
        parent.addView(field, LinearLayout.LayoutParams(-1, -2))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = paper; window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    override fun onResume() {
        super.onResume(); active = true
        if (!unlocked) {
            val guard = getSystemService(KeyguardManager::class.java)
            if (guard.isDeviceSecure) showUnlock() else { unlocked = true; showTerminal() }
        } else if (needsTerminal) showTerminal() else schedule()
    }
    private fun showUnlock() {
        busy = false
        val lock = column().apply { setBackgroundColor(paper); setPadding(dp(28), dp(80), dp(28), dp(24)) }
        lock.addView(text("FIELD / 06", 28f, ink, true))
        lock.addView(text("Your station is locked.", 18f).apply { setPadding(0, dp(20), 0, dp(20)) })
        lock.addView(button("Unlock station", true) {
            val guard = getSystemService(KeyguardManager::class.java)
            val intent = guard.createConfirmDeviceCredentialIntent("Unlock Field", "Use your device screen lock")
            if (intent != null) startActivityForResult(intent, 71)
        })
        setContentView(lock)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == com.google.zxing.integration.android.IntentIntegrator.REQUEST_CODE) {
            val result = com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            val contents = result?.contents?.takeIf { it.length <= 1024 }
            if (identityScan) pendingDeviceScan = contents else pendingScan = contents
            unlocked = true; needsTerminal = true; tab = if (identityScan) 3 else 1
            identityScan = false
        }
        if (requestCode in listOf(81, 82, 83, 84, 85) && resultCode == RESULT_OK && data?.data != null) {
            pendingDocument = requestCode to data.data!!; unlocked = true; needsTerminal = true
            if (requestCode in listOf(83, 84)) tab = 3
            if (requestCode == 85) tab = 1
        }
        if (requestCode == 71 && resultCode == RESULT_OK) { unlocked = true; needsTerminal = true }
    }
    private fun showTerminal() {
        needsTerminal = false
        generation++
        latest = null; rendered = null; busy = false; errorLoading = false
        root = column().apply { isFocusableInTouchMode = true; setBackgroundColor(paper); setPadding(dp(20), dp(10), dp(20), dp(8)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(20), insets.systemWindowInsetTop + dp(10), dp(20), insets.systemWindowInsetBottom + dp(8)); insets
        }
        val header = row()
        header.addView(text("FIELD", 24f, ink, true).apply { typeface = Typeface.create("monospace", Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        stationLabel = text("STATION —", 11f, green, true); header.addView(stationLabel); root.addView(header)
        root.addView(text("PRIVATE CHANNEL / EXPERIMENTAL", 10f, muted, true))
        indicators = text("○ NOT CHECKED", 11f, ink, true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = surface(paper, 2, ink)
        }
        root.addView(indicators, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val tabs = row().apply { setPadding(0, dp(12), 0, dp(4)) }
        trafficTab = button("Messages") { selectTab(0) }; linkTab = button("Link") { selectTab(1) }
        padTab = button("Pad") { selectTab(2) }; deviceTab = button("Device") { selectTab(3) }
        tabs.addView(trafficTab, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(linkTab, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(padTab, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(deviceTab, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(tabs)
        status = text("Opening station…", 11f, muted, true).apply { setPadding(dp(2), dp(4), 0, dp(10)) }; root.addView(status)
        traffic = column()
        setupHint = text("", 12f, ink, true).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = surface(paper, 2, ink); setOnClickListener { selectTab(if (latest?.ownDevice != null && latest?.peerDevice == null) 3 else if (latest?.padCheck == null) 1 else 2) } }; traffic.addView(setupHint)
        newMessagesButton = button("New messages · Jump to latest") {
            messageScroll.fullScroll(View.FOCUS_DOWN)
            ackVisibleMessages()
        }; traffic.addView(newMessagesButton)
        messageScroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        messageScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (active && tab == 0 && !busy && (latest?.unread ?: 0) > 0 && atBottom())
                ackVisibleMessages()
        }
        messages = column().apply { setPadding(0, dp(8), 0, dp(12)) }; messageScroll.addView(messages)
        traffic.addView(messageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        composer = field("Write a message…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false); maxLines = 4; minLines = 2; gravity = Gravity.TOP
        }
        traffic.addView(composer)
        val composeActions = row()
        count = text("0 / 2048 bytes", 10f, muted, true); composeActions.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        syncButton = button("Sync") { runTask { engine?.sync() ?: "Save link settings first" } }; composeActions.addView(syncButton)
        sendButton = button("Send", true) {
            val value = composer.text.toString()
            runTask(clearComposer = true) { checkNotNull(engine).send(value); "QUEUED // saved safely; syncing" }
        }; composeActions.addView(sendButton); traffic.addView(composeActions)
        composer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, n: Int) {
                val bytes = s.toString().toByteArray(Charsets.UTF_8).size
                count.text = "$bytes / 2048 bytes"; count.setTextColor(if (bytes > 2048) Color.rgb(145, 57, 39) else muted)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val settings = column().apply { setPadding(0, 0, 0, dp(24)) }
        settings.addView(text("Station settings", 22f))
        settings.addView(text("Save server details on one device, then pair by cable. QR is available as a fallback.", 14f, muted))
        settings.addView(button("Pair devices · USB cable", true) {
            if (latest?.cableEligible == false) confirm("Replace this pairing?", "This removes the current conversation and queued messages on THIS station. The server address and token stay saved. A fresh secret and pad will be created; the old pad cannot be reused. Start the new pairing on both intended devices.") { beginCablePairing(true) }
            else beginCablePairing(false)
        })
        settings.addView(text("Open Pair devices on both and connect a USB data cable. Setup, pad transfer and identity verification happen together.", 12f, muted))
        val qrActions = row()
        showQrButton = button("Show setup QR") {
            confirm("Share this station's setup?", "This QR contains your relay token and pairing secret. Show it only to your other physical station. Anyone photographing it gets those secrets. It does not include the pad or private device key. Never use it to clone an existing paired device; replacement devices need a fresh secret and pad.") {
                var invite: PairingInvite? = null
                runTask(onSuccess = { invite?.let(::displaySetupQr) }) { invite = checkNotNull(engine).pairingInvite(); "SETUP QR · expires after 60 seconds" }
            }
        }
        scanQrButton = button("Scan setup QR") {
            launchQrScanner(false)
        }
        qrActions.addView(showQrButton, LinearLayout.LayoutParams(0, -2, 1f)); qrActions.addView(scanQrButton, LinearLayout.LayoutParams(0, -2, 1f)); settings.addView(qrActions)
        imageQrButton = button("Import QR image · no camera") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 85)
        }; settings.addView(imageQrButton)
        settings.addView(text("QR fallback: show on one station, scan on the other. The QR contains secrets. Pad and identity setup remain separate in this fallback.", 12f, muted))
        urlField = field("https://pager.example.com"); section(settings, "RELAY ADDRESS", urlField)
        tokenField = field("Relay access token", true); section(settings, "RELAY ACCESS", tokenField)
        saveRelayButton = button("Save server settings", true) {
            val endpoint = urlField.text.toString(); val token = tokenField.text.toString().trim()
            runTask(load = true) {
                val station = checkNotNull(engine); val current = station.state
                station.configure(endpoint, token, current?.getString("secret") ?: Protocol.randomSecret(), current?.getString("role") ?: "A")
                "SERVER SAVED · open Pair devices on both stations"
            }
        }; settings.addView(saveRelayButton)
        val manual = column().apply { visibility = View.GONE }
        settings.addView(button("Advanced · manual pairing") { manual.visibility = if (manual.visibility == View.GONE) View.VISIBLE else View.GONE })
        settings.addView(manual)
        secretField = field("43-character pairing secret", true); section(manual, "PAIRING SECRET", secretField)
        val secrets = row()
        secrets.addView(button("Show / hide") {
            secretField.inputType = InputType.TYPE_CLASS_TEXT or if ((secretField.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD)
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            secretField.typeface = Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(0, -2, 1f))
        secrets.addView(button("Generate new") {
            if (latest?.role != "—") report("Forget your existing pairing before generating a new one")
            else secretField.setText(Protocol.randomSecret())
        }, LinearLayout.LayoutParams(0, -2, 1f)); manual.addView(secrets)
        roleGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((index, label) in listOf("Station A", "Station B").withIndex()) roleGroup.addView(RadioButton(this).apply {
            id = if (index == 0) R.id.station_a else R.id.station_b; text = label; setTextColor(ink); buttonTintList = ColorStateList.valueOf(green)
        }, RadioGroup.LayoutParams(0, dp(48), 1f))
        roleGroup.check(R.id.station_a); section(manual, "THIS DEVICE", roleGroup)
        saveButton = button("Save & connect", true) {
            val endpoint = urlField.text.toString(); val token = tokenField.text.toString().trim()
            val secret = secretField.text.toString().trim(); val role = if (roleGroup.checkedRadioButtonId == R.id.station_a) "A" else "B"
            runTask { checkNotNull(engine).configure(endpoint, token, secret, role); checkNotNull(engine).sync() }
        }; manual.addView(saveButton)
        pairingCode = text("NOT PAIRED", 12f, green, true).apply { setPadding(0, dp(20), 0, dp(12)) }; settings.addView(pairingCode)
        settings.addView(text("Compare this pair-check code on both devices in person. Matching codes confirm the same secret; they do not confirm the server is honest.", 12f, muted))
        val padSettings = column().apply { setPadding(0, 0, 0, dp(24)) }
        padSettings.addView(text("Physical pad setup", 22f))
        padSettings.addView(text("01  Save a fresh link on both stations.\n02  A exports; B imports via physical storage.\n03  Compare the FULL code in person.\n04  Verify on both devices, then sync both.", 14f, ink, true))
        padStatus = text("AES-GCM · no pad installed", 12f, ink, true).apply { setPadding(0, dp(12), 0, dp(8)) }; padSettings.addView(padStatus)
        createPadButton = button("A · Create & export pad file") {
            confirm("Export secret pad material?", "Choose offline USB storage. This file contains BOTH directions of the pad in plaintext. Do not use cloud storage, email, Git, or an online emulator. Generated with Android randomness; experimental, not certified true-random OTP. If export fails, forget pairing and use a fresh secret and pad.") {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"; putExtra(Intent.EXTRA_TITLE, "field-secret-pad.fieldpad")
                }, 81)
            }
        }; padSettings.addView(createPadButton)
        importPadButton = button("B · Import pad file") {
            confirm("Import a physical pad?", "Only import on Station B from the paired Station A. Never install the same pad on additional devices or after reinstalling. A previous installation on this app is blocked.") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 82)
            }
        }; padSettings.addView(importPadButton)
        verifyButton = button("Verify codes & announce", true) {
            if (latest?.padVerified == true) {
                val suffix = latest?.padCheck?.takeLast(8) ?: ""
                runTask { checkNotNull(engine).confirmPad(suffix); "READINESS QUEUED · sync both stations" }
            } else showPadVerification()
        }; padSettings.addView(verifyButton)
        padSettings.addView(text("Verification uses one slot per station. Pad ready means an authenticated readiness message was received; it does not mean the peer is online now. If setup stalls after relay expiry, use Send readiness again, then sync both devices.", 12f, muted))
        padSettings.addView(text("127 outgoing slots per station, including one for setup. Each message and delivery receipt consumes one slot. No automatic fallback to AES when pads run out. Forgetting keeps a pad retirement record. App uninstall or restored backups cannot be detected reliably: always create fresh pads afterward.", 12f, muted))
        padScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(padSettings) }
        val deviceSettings = column().apply { setPadding(0, 0, 0, dp(24)) }
        deviceSettings.addView(text("Device identity", 22f))
        deviceSettings.addView(text("After pad setup, create an identity on BOTH stations. Transfer each PUBLIC card to the other device using physical storage or a trusted local connection. Compare each FULL fingerprint in person before pinning. No server approval or continuous handshake is needed.", 14f, muted))
        identityStatus = text("DEVICE NOT BOUND", 12f, ink, true); deviceSettings.addView(identityStatus)
        prepareDeviceButton = button("Create this device identity", true) {
            confirm("Start device binding?", "First sync queued messages and finish pad setup. Starting pauses new sends until you pin the other device. Incoming unsigned packets will be rejected. Both stations must use version 0.5 or later. Device signatures create verifiable attribution; this mode does not provide deniability. Existing history and pad counters stay intact.") {
                runTask { checkNotNull(engine).prepareIdentity(); "IDENTITY CREATED · exchange public cards in person" }
            }
        }; deviceSettings.addView(prepareDeviceButton)
        identityCard = text("", 12f, ink, true).apply { setTextIsSelectable(true); setPadding(0, dp(12), 0, dp(12)) }; deviceSettings.addView(identityCard)
        showIdentityQrButton = button("Show identity QR") {
            latest?.ownDevice?.let { displayQr("FIELDDEVICE1:$it", "PUBLIC DEVICE IDENTITY", "Scan on the other device, then compare its full fingerprint before pinning.") }
        }; deviceSettings.addView(showIdentityQrButton)
        scanIdentityQrButton = button("Scan other device identity") { launchQrScanner(true) }; deviceSettings.addView(scanIdentityQrButton)
        exportDeviceButton = button("Export public identity card") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "field-public-device.txt")
            }, 83)
        }; deviceSettings.addView(exportDeviceButton)
        importDeviceButton = button("Import other public card") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain" }, 84)
        }; deviceSettings.addView(importDeviceButton)
        peerCardField = field("Other station's PUBLIC identity card").apply { setSingleLine(false); maxLines = 5 }
        section(deviceSettings, "OTHER DEVICE CARD", peerCardField)
        pinDeviceButton = button("Compare & pin other device", true) { showDeviceVerification() }; deviceSettings.addView(pinDeviceButton)
        deviceSettings.addView(text("The private signing key stays in Android Keystore. The protection label reports hardware or software storage on this device; it is not remote attestation. A missing or changed identity requires a fresh physical pairing. Copied pads still expose message contents, and a compromised original device can still use its key. Verification does not prove the peer is online now.", 12f, muted))
        deviceScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(deviceSettings) }
        settings.addView(Switch(this).apply {
            text = "Auto sync every 15 seconds while open"; setTextColor(ink); isChecked = automatic
            setPadding(0, dp(20), 0, dp(12)); setOnCheckedChangeListener { _, checked -> automatic = checked; updateIndicators(); schedule() }
        })
        settings.addView(text("Messages stay locally for up to 24 hours. Clear history to remove them sooner. Delivery means the other station saved the packet, not that a person read it.", 12f, muted))
        settings.addView(button("Clear message history") { confirm("Clear local messages?", "Pending messages stay queued. Replay protection stays active.") {
            runTask { checkNotNull(engine).clearHistory(); "LOCAL HISTORY CLEARED" }
        } })
        settings.addView(button("Forget pairing") { confirm("Forget this pairing?", "This deletes your saved link and queued messages. Re-pair BOTH devices with a NEW secret; reusing the old one can break replay protection.") {
            runTask { SecureStore(this).forget(); engine = StationEngine(SecureStore(this)); errorLoading = false; "PAIRING FORGOTTEN // use a fresh secret" }
            urlField.setText(""); tokenField.setText(""); secretField.setText(""); roleGroup.check(R.id.station_a)
        } })
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure)
            settings.addView(text("Set an Android screen lock to enable station unlock protection.", 12f, muted))
        linkScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(settings) }
        root.addView(traffic, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(linkScroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(padScroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(deviceScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); root.requestApplyInsets(); selectTab(tab)
        var loadedInvite: PairingInvite? = null
        val scanResult = pendingScan; pendingScan = null
        val deviceScanResult = pendingDeviceScan; pendingDeviceScan = null
        runTask(load = true, onSuccess = { loadedInvite?.let(::previewSetupQr) }, publicTransfer = pendingDocument?.first in listOf(83, 84), qrTransfer = pendingDocument?.first == 85) {
            engine = StationEngine(SecureStore(this))
            val scanned = scanResult
            val document = pendingDocument; pendingDocument = null
            if (deviceScanResult != null) {
                require(deviceScanResult.startsWith("FIELDDEVICE1:")) { "This is not a Field device identity QR" }
                val card = deviceScanResult.removePrefix("FIELDDEVICE1:")
                IdentityProtocol.publicKey(card); importedDeviceCard = card
                "IDENTITY SCANNED · compare the full fingerprint before pinning"
            } else if (scanned != null) {
                check(engine?.state == null) { "Existing pairing preserved. Forget it only when arranging a fresh secret and pad." }
                loadedInvite = PairingInvite.decode(scanned)
                "QR READ · review settings before saving"
            } else if (document == null) "STATION READY" else {
                val (operation, uri) = document
                if (operation == 85) {
                    check(engine?.state == null) { "Existing pairing preserved; QR import requires a new station" }
                    val scannedImage = contentResolver.openInputStream(uri)!!.use { PairingQr.readImage(it) }
                    loadedInvite = PairingInvite.decode(scannedImage)
                    "QR READ · review settings before saving"
                } else if (operation == 83) {
                    val card = checkNotNull(engine).view().ownDevice ?: error("Create this device identity first")
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(card.toByteArray(Charsets.US_ASCII)); it.flush() }
                    "PUBLIC CARD EXPORTED · transfer to other station"
                } else if (operation == 84) {
                    val card = contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        while (true) { val byte = input.read(); if (byte < 0) break; require(out.size() < 256) { "Public card file too large" }; out.write(byte) }
                        out.toString("US-ASCII").trim()
                    }
                    IdentityProtocol.publicKey(card)
                    importedDeviceCard = card
                    "PUBLIC CARD IMPORTED · compare the full fingerprint before pinning"
                } else if (operation == 81) {
                    val bundle = checkNotNull(engine).generatePad()
                    try { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bundle); it.flush() } }
                    finally { bundle.fill(0) }
                    "PAD INSTALLED · transfer file to Station B before sending"
                } else {
                    require(engine?.state?.getString("role") == "B") { "Import only on Station B" }
                    val bundle = contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= PadProtocol.BUNDLE_BYTES) { "Pad file too large" }; out.write(buffer, 0, n) }
                        out.toByteArray()
                    }
                    try { checkNotNull(engine).installPad(bundle) } finally { bundle.fill(0) }
                    "PAD INSTALLED · compare pad check with Station A"
                }
            }
        }
    }
    private fun selectTab(value: Int) {
        root.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        tab = value; traffic.visibility = if (value == 0) View.VISIBLE else View.GONE; linkScroll.visibility = if (value == 1) View.VISIBLE else View.GONE
        padScroll.visibility = if (value == 2) View.VISIBLE else View.GONE
        deviceScroll.visibility = if (value == 3) View.VISIBLE else View.GONE
        for ((index, button) in listOf(trafficTab, linkTab, padTab, deviceTab).withIndex()) {
            button.setTextColor(if (value == index) Color.WHITE else ink)
            button.backgroundTintList = ColorStateList.valueOf(if (value == index) ink else Color.TRANSPARENT)
        }
        if (value == 0 && atBottom() && (latest?.unread ?: 0) > 0 && !busy) ackVisibleMessages()
    }
    private fun ackVisibleMessages() {
        if (errorLoading) return
        val visibleGeneration = generation
        runTask {
            if (active && unlocked && visibleGeneration == generation) engine?.markRead()
            "LATEST MESSAGES"
        }
    }
    private fun atBottom() = !::messageScroll.isInitialized || messageScroll.getChildAt(0) == null ||
        messageScroll.getChildAt(0).height - (messageScroll.scrollY + messageScroll.height) < dp(24)
    private fun beginCablePairing(replace: Boolean) {
        runTask(onSuccess = { startActivity(Intent(this, CablePairActivity::class.java)) }) {
            val station = checkNotNull(engine)
            if (replace) station.resetForCable()
            station.requireCableEligible(); "OPENING PHYSICAL PAIRING"
        }
    }
    private fun launchQrScanner(deviceIdentity: Boolean) {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) { report("No camera available · use file import or enter settings"); return }
        identityScan = deviceIdentity
        com.google.zxing.integration.android.IntentIntegrator(this)
            .setCaptureActivity(PairingScannerActivity::class.java)
            .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
            .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false)
            .setPrompt(if (deviceIdentity) "Scan the other station's public identity QR" else "Scan the setup QR on your other physical station").initiateScan()
    }
    private fun displaySetupQr(invite: PairingInvite) = displayQr(invite.encode(), "SECRET SETUP · Station ${invite.role}",
        "Scan from your other physical device. Contains token + pairing secret. Closes in 60 seconds.")
    private fun displayQr(payload: String, title: String, detail: String) {
        val edge = (resources.displayMetrics.widthPixels - dp(96)).coerceIn(200, 1200)
        val bitmap = try { PairingQr.bitmap(payload, edge) } catch (_: Exception) { report("Setup is too large for a QR code"); return }
        val image = ImageView(this).apply {
            setImageBitmap(bitmap); contentDescription = "Pairing QR for the other station"
            (drawable as? android.graphics.drawable.BitmapDrawable)?.paint?.isFilterBitmap = false
        }
        val box = column().apply { gravity = Gravity.CENTER; addView(image, LinearLayout.LayoutParams(edge, edge)) }
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setMessage(detail)
            .setView(box).setPositiveButton("Close", null).create()
        val expire = Runnable { dialog.dismiss() }
        pairingDialog = dialog; handler.removeCallbacks(poll)
        dialog.setOnDismissListener { handler.removeCallbacks(expire); image.setImageDrawable(null); bitmap.recycle(); pairingDialog = null; schedule() }
        dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        handler.postDelayed(expire, 60000)
    }
    private fun previewSetupQr(invite: PairingInvite) {
        val master = Protocol.master(invite.secret)
        val fingerprint = try { Protocol.fingerprint(master) } finally { master.fill(0) }
        val dialog = AlertDialog.Builder(this).setTitle("Review scanned setup")
            .setMessage("Relay: ${invite.endpoint}\nThis device: Station ${invite.role}\nRelay token: included (hidden)\nPairing secret: included (hidden)\nPair check: $fingerprint\n\nOnly continue if this came from your other physical station. The pad and device identity are not included. Use this setup to save the link without typing. Pad and identity setup remain separate in this QR fallback.")
            .setNegativeButton("Cancel", null).setPositiveButton("Use this setup") { _, _ ->
                if (latest?.role == "—" && !busy) {
                    urlField.setText(invite.endpoint); tokenField.setText(invite.token); secretField.setText(invite.secret)
                    roleGroup.check(if (invite.role == "A") R.id.station_a else R.id.station_b)
                    runTask {
                        check(checkNotNull(engine).state == null) { "Existing pairing preserved" }
                        checkNotNull(engine).configure(invite.endpoint, invite.token, invite.secret, invite.role)
                        "LINK SAVED · continue pad setup or start fresh cable pairing"
                    }
                } else report("Existing pairing preserved")
            }.create()
        pairingDialog = dialog; handler.removeCallbacks(poll)
        dialog.setOnDismissListener { pairingDialog = null; schedule() }
        dialog.show(); dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun showDeviceVerification() {
        val card = peerCardField.text.toString().trim()
        val fingerprint = try { IdentityProtocol.fingerprint(card) } catch (_: Exception) { report("Invalid public identity card"); return }
        val input = field("Last 8 characters from OTHER device").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val dialog = AlertDialog.Builder(this).setTitle("Compare the OTHER device fingerprint")
            .setMessage("Compare ALL 64 characters below with the fingerprint displayed on the OTHER physical device. Do not compare a screenshot or code supplied by the relay.\n\n${fingerprint.chunked(4).joinToString(" ")}\n\nEnter the last 8 characters read from that device. Pinning cannot be undone within this pairing.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Pin device", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val suffix = input.text.toString().trim().lowercase()
                if (suffix != fingerprint.takeLast(8)) input.error = "Fingerprint does not match"
                else { dialog.dismiss(); runTask { checkNotNull(engine).pinDevice(card, suffix); "DEVICE PINNED · repeat exchange on other station" } }
            }
        }
        handler.removeCallbacks(poll); dialog.setOnDismissListener { schedule() }; dialog.show()
    }
    private fun showPadVerification() {
        val input = field("Last 8 characters from OTHER station").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val dialog = AlertDialog.Builder(this).setTitle("Compare the full pad check")
            .setMessage("Compare ALL 32 characters on both devices in person. If they match, enter the last 8 characters shown on the OTHER device. This announces readiness using one pad slot.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Verify", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val suffix = input.text.toString().trim().lowercase()
                if (suffix != latest?.padCheck?.takeLast(8)) input.error = "Code does not match"
                else { dialog.dismiss(); runTask { checkNotNull(engine).confirmPad(suffix); "VERIFIED LOCALLY · sync both stations" } }
            }
        }
        handler.removeCallbacks(poll)
        dialog.setOnDismissListener { schedule() }
        dialog.show()
    }
    private fun confirm(title: String, detail: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> if (!busy) action() }.show()
    }
    private fun report(message: String) { if (active && unlocked && ::status.isInitialized && status.text.toString() != message) status.text = message }
    private fun setBusy(value: Boolean) {
        busy = value
        for (view in listOf(sendButton, saveButton, saveRelayButton, syncButton, urlField, tokenField, secretField, roleGroup)) view.isEnabled = !value
        sendButton.isEnabled = !value && latest?.canSend == true && !errorLoading
        showQrButton.isEnabled = !value && latest?.role != null && latest?.role != "—" && !errorLoading
        scanQrButton.isEnabled = !value && latest?.role == "—" && !errorLoading
        imageQrButton.isEnabled = scanQrButton.isEnabled
        showIdentityQrButton.isEnabled = !value && latest?.ownDevice != null
        scanIdentityQrButton.isEnabled = !value && latest?.ownDevice != null && latest?.peerDevice == null
        exportDeviceButton.isEnabled = !value && latest?.ownDevice != null
        importDeviceButton.isEnabled = !value && latest?.ownDevice != null && latest?.peerDevice == null
        prepareDeviceButton.isEnabled = !value && latest?.ownDevice == null && latest?.padVerified == true && latest?.peerReady == true && latest?.pending == 0
        pinDeviceButton.isEnabled = !value && latest?.ownDevice != null && latest?.peerDevice == null
        peerCardField.isEnabled = !value && latest?.ownDevice != null && latest?.peerDevice == null
        createPadButton.isEnabled = !value && latest?.role == "A" && latest?.padCheck == null
        importPadButton.isEnabled = !value && latest?.role == "B" && latest?.padCheck == null
        verifyButton.isEnabled = !value && latest?.ownDevice == null && latest?.padCheck != null && (latest?.padVerified == true || latest?.padRemaining != 0)
    }
    private fun updateIndicators() {
        if (!::indicators.isInitialized) return
        val v = latest
        val connection = when {
            v == null || v.role == "—" -> "○ NOT PAIRED"
            connectionFailed -> "! RELAY UNAVAILABLE"
            lastContact == 0L -> "○ NOT CHECKED"
            System.currentTimeMillis() - lastContact > 60000 -> "○ LAST CHECK >1 MIN"
            else -> "● RELAY CONNECTED"
        }
        val value = "$connection  ·  ${v?.pending ?: 0} QUEUED\n${v?.unread ?: 0} NEW  ·  " + (v?.padRemaining?.let { "${if (v?.padVerified != true) "PAD CHECK NEEDED" else if (v?.peerReady != true) "WAITING FOR PEER" else "PAD READY"} · $it SLOTS" } ?: "AES-GCM · TEST MODE") + "\n" + (if (v?.peerDevice != null) "DEVICE KEY PINNED" else if (v?.ownDevice != null) "DEVICE EXCHANGE INCOMPLETE" else "DEVICE NOT BOUND") + if (!automatic) " · AUTO OFF" else ""
        if (indicators.text.toString() != value) indicators.text = value
    }
    private fun render(view: StationEngine.View) {
        latest = view
        val tabLabel = if (view.unread > 0) "Messages (${view.unread})" else "Messages"
        if (trafficTab.text.toString() != tabLabel) trafficTab.text = tabLabel
        updateIndicators()
        newMessagesButton.visibility = if (view.unread > 0) View.VISIBLE else View.GONE
        newMessagesButton.text = "${view.unread} new · Jump to latest"
        val hint = when {
            view.role == "—" -> "SET UP YOUR STATION → Link"
            !view.pairingComplete -> "PAIRING INCOMPLETE · arrange a fresh physical pairing on both devices"
            view.padCheck == null -> "AES TEST MODE · Set up a physical pad → Pad"
            view.padRemaining == 0 -> "SEND PAD EXHAUSTED · Receiving and queued retries remain available"
            !view.padVerified -> "COMPARE CODES IN PERSON → Pad"
            !view.peerReady -> "WAITING FOR PEER VERIFICATION · Sync both stations"
            view.ownDevice != null && view.peerDevice == null -> "FINISH DEVICE EXCHANGE → Device"
            (view.padRemaining ?: Int.MAX_VALUE) <= 10 -> "LOW PAD RESERVE · Arrange a fresh physical pairing"
            else -> ""
        }
        setupHint.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
        if (setupHint.text.toString() != hint) setupHint.text = hint
        val verifyLabel = if (view.padVerified) "Send readiness again" else "Verify codes & announce"
        if (verifyButton.text.toString() != verifyLabel) verifyButton.text = verifyLabel
        val padLabel = view.padCheck?.let { "PAD CHECK\n${it.chunked(4).joinToString(" ")}\n${view.padRemaining} outgoing slots remaining" } ?: "AES-GCM · no pad installed"
        if (padStatus.text.toString() != padLabel) padStatus.text = padLabel
        if (stationLabel.text.toString() != "STATION ${view.role}") stationLabel.text = "STATION ${view.role}"
        if (pairingCode.text.toString() != "PAIR CHECK\n${view.fingerprint}") pairingCode.text = "PAIR CHECK\n${view.fingerprint}"
        importedDeviceCard?.let { peerCardField.setText(it); importedDeviceCard = null }
        val deviceLabel = if (view.peerDevice != null) "DEVICE KEY PINNED · offline verification enabled" else if (view.ownDevice != null) "WAITING FOR PHYSICAL KEY EXCHANGE" else "DEVICE NOT BOUND · pad possession only"
        val ownLabel = view.ownDevice?.let { "THIS DEVICE · ${view.keyProtection}\nFINGERPRINT\n${IdentityProtocol.fingerprint(it).chunked(4).joinToString(" ")}\n\nPUBLIC CARD (select and copy)\n$it" } ?: ""
        if (identityCard.text.toString() != ownLabel) identityCard.text = ownLabel
        val fullDeviceLabel = if (view.peerDevice != null) "$deviceLabel\nPEER FINGERPRINT\n${IdentityProtocol.fingerprint(view.peerDevice).chunked(4).joinToString(" ")}" else deviceLabel
        if (identityStatus.text.toString() != fullDeviceLabel) identityStatus.text = fullDeviceLabel
        if (view.peerDevice != null) {
            peerCardField.text.clear()
        }
        if (view.messages == rendered) return
        val wasAtBottom = atBottom()
        rendered = view.messages; messages.removeAllViews()
        if (view.messages.isEmpty()) {
            messages.addView(text("A quiet channel.", 24f).apply { setPadding(dp(10), dp(40), 0, dp(10)) })
            messages.addView(text(if (view.role == "—") "Open Link to pair this station." else "Write your first message below.", 14f, muted).apply { setPadding(dp(10), 0, 0, 0) })
        }
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        for (m in view.messages) {
            val card = column().apply {
                setPadding(dp(14), dp(11), dp(14), dp(12))
                background = surface(Color.WHITE, 2, if (m.incoming) ink else lineColor)
            }
            val label = if (m.incoming) (if (m.unread) "● NEW FROM PEER" else "FROM PEER") else when (m.status) { "queued" -> "○ QUEUED"; "relayed" -> "↑ AT RELAY"; "delivered" -> "✓ PEER SAVED"; else -> m.status.uppercase() }
            card.addView(text("$label  /  ${timeFormat.format(java.util.Date(m.time))}\n${if (m.deviceVerified) "DEVICE SIGNATURE VERIFIED" else "PAD / SHARED SECRET ONLY"}", 10f, muted, true))
            card.addView(text(m.text, 16f, ink, true).apply { setPadding(0, dp(5), 0, 0); isSaveEnabled = false })
            messages.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10); if (m.incoming) rightMargin = dp(24) else leftMargin = dp(24)
            })
        }
        if (wasAtBottom) messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun runTask(load: Boolean = false, clearComposer: Boolean = false, publicTransfer: Boolean = false, qrTransfer: Boolean = false, onSuccess: (() -> Unit)? = null, action: () -> String) {
        if (busy || !active || !unlocked || isFinishing) return
        val taskGeneration = generation
        setBusy(true); handler.removeCallbacks(poll)
        worker.execute {
            var note: String; var view: StationEngine.View? = null
            var config: org.json.JSONObject? = null; var success = false; var relayFailure = false
            try {
                note = action(); success = true; backoff = 15000L
                view = engine?.view(); if (load) config = engine?.state?.let { org.json.JSONObject(it.toString()) }
            } catch (e: Exception) {
                relayFailure = !load && (e is java.io.IOException || e is RelayHttpException)
                note = if (load && engine != null && e is java.io.IOException) {
                    if (qrTransfer) "QR IMAGE COULD NOT BE READ // no settings changed"
                    else if (publicTransfer) "PUBLIC CARD TRANSFER FAILED // identity retained; retry the file transfer"
                    else "PAD FILE TRANSFER FAILED // after failed export use a fresh pairing and pad"
                } else friendly(e); backoff = (backoff * 2).coerceAtMost(60000L)
                if (load && engine == null) errorLoading = true
                if (load) config = engine?.state?.let { org.json.JSONObject(it.toString()) }
                try { view = engine?.view() } catch (_: Exception) { errorLoading = true }
            }
            runOnUiThread {
                if (!active || !unlocked || isFinishing || taskGeneration != generation) return@runOnUiThread
                if (config != null) {
                    urlField.setText(config!!.getString("endpoint")); tokenField.setText(config!!.getString("token"))
                    secretField.setText(config!!.getString("secret")); roleGroup.check(if (config!!.getString("role") == "A") R.id.station_a else R.id.station_b)
                }
                if (note.startsWith("Connected")) { lastContact = System.currentTimeMillis(); connectionFailed = false }
                else if (relayFailure) connectionFailed = true
                view?.let(::render)
                if (clearComposer && success) composer.text.clear()
                if (errorLoading) note = "VAULT UNAVAILABLE // data preserved; see Link → Forget pairing to reset"
                report(note); setBusy(false); schedule(if (clearComposer && success) 100 else backoff)
                if (success) onSuccess?.invoke()
                // Check current visibility AFTER rendering/network completion, not before a slow sync.
                messageScroll.post {
                    if (success && !errorLoading && active && unlocked && taskGeneration == generation && tab == 0 && !busy && (latest?.unread ?: 0) > 0 && atBottom()) ackVisibleMessages()
                }
            }
        }
    }
    private fun friendly(e: Exception): String = when (e) {
        is RelayHttpException -> when (e.status) {
            401, 403 -> "ACCESS DENIED // check the relay token in Link"
            429 -> "RELAY BUSY OR FULL // queued traffic will retry"
            413 -> "RELAY REJECTED MESSAGE SIZE"
            in 300..399 -> "REDIRECT BLOCKED // use the final HTTPS hostname"
            else -> "RELAY ERROR ${e.status} // queued traffic is retained"
        }
        is javax.net.ssl.SSLException -> "TLS ERROR // check the server certificate and device clock"
        is com.google.zxing.ReaderException -> "No readable pairing QR found; try a clear, upright image"
        is java.io.IOException -> "OFFLINE // queued traffic is retained; retrying"
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "INVALID SETTINGS"
        else -> "STATION ERROR // saved data retained"
    }
    private fun schedule(delay: Long = backoff) {
        handler.removeCallbacks(poll)
        if (active && unlocked && !isFinishing && !errorLoading) handler.postDelayed(poll, delay)
    }
    override fun onStop() {
        pairingDialog?.dismiss(); pairingDialog = null
        generation++; active = false; unlocked = false; needsTerminal = true; handler.removeCallbacks(poll)
        if (::composer.isInitialized) composer.text.clear()
        if (::messages.isInitialized) messages.removeAllViews()
        if (::tokenField.isInitialized) { tokenField.text.clear(); secretField.text.clear() }
        latest = null; rendered = null; importedDeviceCard = null
        if (::peerCardField.isInitialized) peerCardField.text.clear()
        worker.execute { engine = null }
        super.onStop()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
