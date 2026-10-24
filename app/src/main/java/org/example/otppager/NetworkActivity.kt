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

/** Isolated two-device chats with explicit receiving and physical pairing controls. */
class NetworkActivity : Activity() {
    private val worker = StationRuntime.worker
    private val handler = Handler(Looper.getMainLooper())
    private var engine: StationEngine? = null // worker-thread owned
    private var book: ChatBook? = null
    private lateinit var chatButton: Button
    private lateinit var receiveButton: Button
    private lateinit var renewButton: Button
    private lateinit var advancedSetup: LinearLayout
    private lateinit var serverForm: LinearLayout
    private lateinit var pairedOptions: LinearLayout
    private lateinit var settingsIntro: TextView
    private lateinit var pairingHelp: TextView
    private lateinit var pairButton: Button
    private var advancedShown = false
    private var selectedPadSize = 4 * PadProtocol.MATERIAL_BYTES
    @Volatile private var generation = 0
    private var busy = false
    @Volatile private var active = false
    @Volatile private var unlocked = false
    private var needsTerminal = true
    private var backoff = 15000L
    private var tab = 0
    private var latest: StationEngine.View? = null
    private var rendered: List<StationEngine.Message>? = null
    private var renderedSetupComplete: Boolean? = null
    private var errorLoading = false
    private var automatic = true
    private var lastContact = 0L
    private var connectionFailed = false
    private var connectionProblem = "RELAY UNAVAILABLE"
    private var networkBusy = false
    private val compact: Boolean get() = resources.configuration.screenHeightDp < 600 || resources.configuration.screenWidthDp < 360
    private var importedDeviceCard: String? = null
    private lateinit var exportDeviceButton: Button
    private lateinit var importDeviceButton: Button
    private var identityScan = false
    private var pendingDeviceScan: String? = null
    private lateinit var showIdentityQrButton: Button
    private lateinit var scanIdentityQrButton: Button
    private var pendingScan: String? = null
    private var pairingDialog: AlertDialog? = null
    private val openDialogs = mutableListOf<AlertDialog>()
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
    private lateinit var navigation: LinearLayout
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
            if (active && unlocked && automatic && !busy && !errorLoading && latest != null && latest?.role != "—" && latest?.pairingComplete == true && latest?.relayRevoked != true) runTask(network = true) { syncChats() }
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
        stateListAnimator = null; elevation = 0f; setPadding(dp(8), 0, dp(8), 0)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), surface(paper, 2, lineColor))
            addState(intArrayOf(), surface(if (primary) ink else paper, 2, if (primary) ink else lineColor))
        }
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) Color.WHITE else ink)))
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
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSaveEnabled = false; filterTouchesWhenObscured = true
    }
    private fun section(parent: LinearLayout, label: String, field: View) {
        parent.addView(text(label, 11f, muted, true).apply { setPadding(0, dp(18), 0, dp(7)) })
        parent.addView(field, LinearLayout.LayoutParams(-1, -2))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPadSize = getSharedPreferences("receiving", MODE_PRIVATE).getInt("padSize", 4 * PadProtocol.MATERIAL_BYTES).takeIf { PadProtocol.validSize(it) } ?: 4 * PadProtocol.MATERIAL_BYTES
        window.protectPrivateContent()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = paper; window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    override fun onResume() {
        super.onResume(); active = true; StationRuntime.uiVisible = true
        if (!unlocked) {
            val guard = getSystemService(KeyguardManager::class.java)
            if (guard.isDeviceSecure) showUnlock() else { unlocked = true; showTerminal() }
        } else if (needsTerminal) showTerminal() else schedule()
    }
    private fun showUnlock() {
        busy = false
        val lock = column().apply { setBackgroundColor(paper); setPadding(dp(28), dp(80), dp(28), dp(24)) }
        lock.addView(text("FIELD / 07", 28f, ink, true))
        lock.addView(text("Your station is locked.", 18f).apply { setPadding(0, dp(20), 0, dp(20)) })
        lock.addView(button("Unlock station", true) {
            val guard = getSystemService(KeyguardManager::class.java)
            val intent = guard.createConfirmDeviceCredentialIntent("Unlock Field", "Use your device screen lock")
            if (intent != null) startActivityForResult(intent, 71)
        })
        setContentView(lock)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (::composer.isInitialized && active && composer.text.isNotEmpty()) {
            intent.removeExtra("openUnread"); report("New messages are available in Chats")
        } else needsTerminal = true
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
        root = column().apply { isFocusableInTouchMode = true; setBackgroundColor(paper); setPadding(dp(if (compact) 12 else 20), dp(8), dp(if (compact) 12 else 20), dp(6)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(if (compact) 12 else 20), insets.systemWindowInsetTop + dp(8), dp(if (compact) 12 else 20), insets.systemWindowInsetBottom + dp(6)); insets
        }
        val chrome = column(); root.addView(chrome)
        val header = row()
        chatButton = button("Chats ▾") { showChats() }.apply {
            textSize = if (compact) 15f else 18f; typeface = Typeface.create("monospace", Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(6), 0); background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        header.addView(chatButton, LinearLayout.LayoutParams(0, -2, 1f))
        stationLabel = text("STATION —", 11f, green, true); header.addView(stationLabel)
        receiveButton = button("Receive") { showReceiving() }.apply { textSize = 11f; minWidth = 0; minimumWidth = 0 }
        header.addView(receiveButton); chrome.addView(header)
        if (!compact) chrome.addView(text("PRIVATE MESSAGES", 10f, muted, true))
        indicators = text("○ Not checked yet", 11f, ink, true).apply {
            setPadding(dp(10), dp(if (compact) 5 else 8), dp(10), dp(if (compact) 5 else 8)); background = surface(paper, 2, ink)
            contentDescription = "Station status. Tap for connection, delivery and security details."
            setOnClickListener { showStationStatus() }
        }
        chrome.addView(indicators, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (compact) 6 else 10) })
        val tabs = row().apply { setPadding(0, dp(if (compact) 4 else 8), 0, dp(2)) }
        navigation = tabs
        trafficTab = button("Messages") { selectTab(0) }; linkTab = button("Link") { selectTab(1) }
        padTab = button("Pad") { selectTab(2) }; deviceTab = button("Device") { selectTab(3) }
        if (compact) for (item in listOf(trafficTab, linkTab, padTab, deviceTab)) {
            item.textSize = 11f; item.minWidth = 0; item.minimumWidth = 0; item.setPadding(dp(3), 0, dp(3), 0)
        }
        tabs.addView(trafficTab, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(linkTab, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(padTab, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(deviceTab, LinearLayout.LayoutParams(0, -2, 1f)); chrome.addView(tabs)
        status = text("Opening station…", 11f, muted, true).apply { setPadding(dp(2), dp(2), 0, dp(4)); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setOnClickListener { showStationStatus() } }; chrome.addView(status)
        traffic = column()
        setupHint = text("", 12f, ink, true).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = surface(paper, 2, ink); setOnClickListener { selectTab(if (latest?.relayRevoked == true || latest?.pairingComplete == false) 1 else if (latest?.ownDevice != null && latest?.peerDevice == null) 3 else if (latest?.padCheck == null) 1 else 2) } }; traffic.addView(setupHint)
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
            setSingleLine(false); maxLines = if (compact) 2 else 4; minLines = if (compact) 1 else 2; gravity = Gravity.TOP
        }
        traffic.addView(composer)
        val composeActions = row()
        count = text("0 / 2048 bytes", 10f, muted, true); composeActions.addView(count, LinearLayout.LayoutParams(0, -2, 1f))
        syncButton = button("Sync") { runTask(network = true) { syncChats() } }; composeActions.addView(syncButton)
        sendButton = button("Send", true) {
            val value = composer.text.toString()
            runTask(clearComposer = true) { check(checkNotNull(engine).view().padCheck != null) { "Pair a physical pad before sending" }; checkNotNull(engine).send(value); "QUEUED // saved safely; syncing" }
        }; composeActions.addView(sendButton); traffic.addView(composeActions)
        composer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, n: Int) {
                val bytes = s.toString().toByteArray(Charsets.UTF_8).size
                count.text = "$bytes / 2048 bytes"; count.setTextColor(if (bytes > 2048) Color.rgb(145, 57, 39) else muted)
                updateSendButton()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val settings = column().apply { setPadding(0, 0, 0, dp(24)) }
        settings.addView(text("Chat settings", 22f))
        pairedOptions = column(); settings.addView(pairedOptions)
        pairedOptions.addView(button("Receiving & battery") { showReceiving() })
        renewButton = button("Renew pad · USB") { choosePadSize(renew = true) }
        pairedOptions.addView(renewButton)
        pairedOptions.addView(button("Renew nearby · NFC + Wi-Fi") { beginRadioPairing(true) })
        pairedOptions.addView(button("Pad size for USB pairing") { choosePadSize(renew = false, launch = false) })
        settings.addView(button("Chat relay access") { showRelayAccess() })
        settingsIntro = text("Save server details on one device, then pair by cable. QR is available as a fallback.", 14f, muted); settings.addView(settingsIntro)
        pairButton = button("Pair devices · USB cable", true) {
            if (latest?.cableEligible == false) confirm("Replace this pairing?", "This removes the current conversation and queued messages on THIS station. The server address and token stay saved. A fresh secret and pad will be created; the old pad cannot be reused. Start the new pairing on both intended devices.") { beginCablePairing(true) }
            else choosePadSize(renew = false)
        }; settings.addView(pairButton)
        settings.addView(button("Pair nearby · NFC + Wi-Fi") { beginRadioPairing(false) })
        pairingHelp = text("Open Pair devices on both and connect a USB data cable. Setup, pad transfer and identity verification happen together.", 12f, muted); settings.addView(pairingHelp)
        settings.addView(button("Advanced connection setup") {
            advancedShown = !advancedShown
            advancedSetup.visibility = if (advancedShown) View.VISIBLE else View.GONE
            val paired = latest?.peerDevice != null
            serverForm.visibility = if (!paired || advancedShown) View.VISIBLE else View.GONE
            pairButton.visibility = if (!paired || advancedShown) View.VISIBLE else View.GONE
        })
        advancedSetup = column(); settings.addView(advancedSetup)
        advancedSetup.addView(button("Tap setup · NFC") {
            runTask(onSuccess = { startActivity(Intent(this, NfcSetupActivity::class.java).putExtra("chat", checkNotNull(book).active().id)) }) {
                checkNotNull(engine).requireCableEligible(); "Open NFC setup on the other device"
            }
        })
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
        qrActions.addView(showQrButton, LinearLayout.LayoutParams(0, -2, 1f)); qrActions.addView(scanQrButton, LinearLayout.LayoutParams(0, -2, 1f)); advancedSetup.addView(qrActions)
        imageQrButton = button("Import QR image · no camera") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }, 85)
        }; advancedSetup.addView(imageQrButton)
        advancedSetup.addView(text("QR fallback: show on one station, scan on the other. The QR contains secrets. Pad and identity setup remain separate in this fallback.", 12f, muted))
        serverForm = column(); settings.addView(serverForm, 1)
        urlField = field("https://pager.example.com"); section(serverForm, "RELAY ADDRESS", urlField)
        tokenField = field("Relay access token", true); section(serverForm, "RELAY SETUP TOKEN", tokenField)
        saveRelayButton = button("Save server settings", true) {
            val endpoint = urlField.text.toString(); val token = tokenField.text.toString().trim()
            runTask(load = true) {
                val station = checkNotNull(engine); val current = station.state
                station.configure(endpoint, token, current?.getString("secret") ?: Protocol.randomSecret(), current?.getString("role") ?: "A")
                "SERVER SAVED · open Pair devices on both stations"
            }
        }; serverForm.addView(saveRelayButton)
        val manual = column().apply { visibility = View.GONE }
        advancedSetup.addView(button("Advanced · manual pairing") { manual.visibility = if (manual.visibility == View.GONE) View.VISIBLE else View.GONE })
        advancedSetup.addView(manual)
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
            runTask(onSuccess = { runTask(network = true) { syncChats() } }) {
                checkNotNull(engine).configure(endpoint, token, secret, role); "LINK SAVED · checking relay"
            }
        }; manual.addView(saveButton)
        pairingCode = text("NOT PAIRED", 12f, green, true).apply { setPadding(0, dp(20), 0, dp(12)) }; advancedSetup.addView(pairingCode)
        advancedSetup.addView(text("Compare this pair-check code on both devices in person. Matching codes confirm the same secret; they do not confirm the server is honest.", 12f, muted))
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
        settings.addView(button("Retry undelivered messages") {
            runTask(onSuccess = { runTask(network = true) { syncChats() } }) {
                val n = checkNotNull(engine).retryDelivery()
                "$n SAVED MESSAGES REQUEUED · checking existing receipts"
            }
        })
        settings.addView(text("Messages stay locally for up to 24 hours. Clear history to remove them sooner. Delivery means the other station saved the packet, not that a person read it.", 12f, muted))
        settings.addView(button("Clear message history") { confirm("Clear local messages?", "Pending messages stay queued. Saved receipt recovery is removed with history. Replay protection stays active.") {
            runTask { checkNotNull(engine).clearHistory(); "LOCAL HISTORY CLEARED" }
        } })
        settings.addView(button("Forget pairing") { confirm("Forget this pairing?", "This deletes your saved link and queued messages. Re-pair BOTH devices with a NEW secret; reusing the old one can break replay protection.") {
            runTask { checkNotNull(book).forgetActive(); engine = checkNotNull(book).engine(); errorLoading = false; "PAIRING FORGOTTEN // use a fresh secret" }
            urlField.setText(""); tokenField.setText(""); secretField.setText(""); roleGroup.check(R.id.station_a)
        } })
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure)
            settings.addView(text("Set an Android screen lock to enable station unlock protection.", 12f, muted))
        linkScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(settings) }
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = android.graphics.Rect(); root.getWindowVisibleDisplayFrame(visible)
            val keyboardVisible = root.rootView.height - visible.bottom > dp(150)
            val next = if (compact && tab == 0 && keyboardVisible) View.GONE else View.VISIBLE
            if (chrome.visibility != next) chrome.visibility = next
        }
        root.addView(traffic, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(linkScroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(padScroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(deviceScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); root.requestApplyInsets(); selectTab(tab)
        var loadedInvite: PairingInvite? = null
        val scanResult = pendingScan; pendingScan = null
        val deviceScanResult = pendingDeviceScan; pendingDeviceScan = null
        runTask(load = true, onSuccess = { loadedInvite?.let(::previewSetupQr) }, publicTransfer = pendingDocument?.first in listOf(83, 84), qrTransfer = pendingDocument?.first == 85) {
            book = null; engine = null
            book = ChatBook(this)
            if (intent.getBooleanExtra("openUnread", false)) {
                book!!.chats().firstOrNull { book!!.summary(it.id).unread > 0 }?.let { book!!.select(it.id) }
                intent.removeExtra("openUnread")
            }
            engine = checkNotNull(book).engine()
            val scanned = scanResult
            val document = pendingDocument; pendingDocument = null
            if (deviceScanResult != null) {
                require(deviceScanResult.startsWith("FIELDDEVICE1:")) { "This is not a Field device identity QR" }
                val card = deviceScanResult.removePrefix("FIELDDEVICE1:")
                IdentityProtocol.publicKey(card); importedDeviceCard = card
                "IDENTITY SCANNED · compare the full fingerprint before pinning"
            } else if (scanned != null) {
                checkNotNull(engine).requireCableEligible()
                loadedInvite = PairingInvite.decode(scanned)
                "QR READ · review settings before saving"
            } else if (document == null) "STATION READY" else {
                val (operation, uri) = document
                if (operation == 85) {
                    checkNotNull(engine).requireCableEligible()
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
                    val bundle = checkNotNull(engine).generatePad(selectedPadSize)
                    try { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bundle); it.flush() } }
                    finally { bundle.fill(0) }
                    "PAD INSTALLED · transfer file to Station B before sending"
                } else {
                    require(engine?.state?.getString("role") == "B") { "Import only on Station B" }
                    val bundle = contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= PadProtocol.MAX_BUNDLE_BYTES) { "Pad file too large" }; out.write(buffer, 0, n) }
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
        tab = value; updateNavigation(); traffic.visibility = if (value == 0) View.VISIBLE else View.GONE; linkScroll.visibility = if (value == 1) View.VISIBLE else View.GONE
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
    private fun beginCablePairing(replace: Boolean, renew: Boolean = false) {
        runTask(onSuccess = { startActivity(Intent(this, CablePairActivity::class.java).putExtra("chat", checkNotNull(book).active().id).putExtra("renew", renew).putExtra("padSize", selectedPadSize)) }) {
            val station = checkNotNull(engine)
            if (replace) station.resetForCable()
            if (renew) station.requireRenewalIdentity() else station.requireCableEligible(); "Opening USB connection"
        }
    }
    private fun beginRadioPairing(renew: Boolean) {
        confirm(if (renew) "Renew over local Wi-Fi?" else "Pair over local Wi-Fi?",
            "Both devices need version 0.12, NFC and the same private Wi-Fi. Tap to share a temporary secret, then compare codes before the encrypted pad transfer. No pad goes to the relay. Keep the tap private; use USB if you prefer a cable. Existing pairings are preserved. Pad size on this device: ${selectedPadSize / PadProtocol.MATERIAL_BYTES} MiB.") {
            runTask(onSuccess = { startActivity(Intent(this, RadioPairActivity::class.java)
                .putExtra("chat", checkNotNull(book).active().id).putExtra("renew", renew).putExtra("padSize", selectedPadSize)) }) {
                if (renew) checkNotNull(engine).requireRenewalIdentity() else checkNotNull(engine).requireCableEligible()
                "Opening nearby transfer"
            }
        }
    }
    private fun choosePadSize(renew: Boolean, launch: Boolean = true) {
        val choices = arrayOf("1 MiB · 127 slots each way", "2 MiB · 254 slots each way", "4 MiB · 508 slots each way")
        val sizes = intArrayOf(PadProtocol.MATERIAL_BYTES, 2 * PadProtocol.MATERIAL_BYTES, 4 * PadProtocol.MATERIAL_BYTES)
        var selected = sizes.indexOf(selectedPadSize).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(if (renew) "Renew this friend's pad" else "Choose a USB pad size")
            .setSingleChoiceItems(choices, selected) { _, which -> selected = which }
            .setPositiveButton(if (launch) "Continue by USB" else "Save") { _, _ ->
                selectedPadSize = sizes[selected]
                getSharedPreferences("receiving", MODE_PRIVATE).edit().putInt("padSize", selectedPadSize).apply()
                if (launch) beginCablePairing(false, renew) else report("USB pad size: ${selectedPadSize / PadProtocol.MATERIAL_BYTES} MiB")
            }.setNegativeButton("Cancel", null).create().also(::secureShow)
    }
    private fun showReceiving() {
        val enabled = ReceiveService.enabled(this)
        val notifications = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        val power = getSystemService(android.os.PowerManager::class.java)
        val battery = if (power.isIgnoringBatteryOptimizations(packageName)) "Battery restrictions are lifted." else "Android may delay checks in deep sleep. For timely delivery, allow unrestricted battery use in Android settings."
        val state = if (ReceiveService.running) "Receiving is on." else if (enabled) "Receiving is paused. Tap Resume." else "Receiving is off. Messages are checked while the app is open."
        AlertDialog.Builder(this).setTitle("Receive with the screen off")
            .setMessage("$state${if (!notifications) " Notifications are currently blocked." else ""}\n\nBackground checks cycle through your friends about every 30 seconds, with backoff when connections fail. Live receiving keeps the CPU awake and uses more battery. A persistent notification lets you stop it. Message text and friend names stay out of notifications.\n\n$battery\n\nThis uses additional battery. After a restart or Force stop, open the app and resume if needed.")
            .setPositiveButton(if (ReceiveService.running) "Turn off" else if (enabled) "Resume" else "Turn on") { _, _ ->
                if (ReceiveService.running) ReceiveService.stop(this) else {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 92)
                    else enableReceiving()
                }
                updateIndicators()
            }.setNeutralButton("Battery settings") { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (_: Exception) { report("Open Android settings → Apps → OTP Pager → Battery") }
            }.setNegativeButton("Close", null).create().also(::secureShow)
    }
    private fun enableReceiving() {
        try { ReceiveService.start(this); report("Background receiving enabled"); handler.postDelayed({ if (active) updateIndicators() }, 600) }
        catch (_: Exception) { report("Receiving could not start. Keep the app open and try again.") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 92 && active) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) enableReceiving()
            else report("Notifications were not allowed. Receiving remains off.")
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
        val edge = minOf(dp(280), resources.displayMetrics.widthPixels - dp(96)).coerceIn(200, 1200)
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
        secureShow(dialog)
        handler.postDelayed(expire, 60000)
    }
    private fun previewSetupQr(invite: PairingInvite) {
        val master = Protocol.master(invite.secret)
        val fingerprint = try { Protocol.fingerprint(master) } finally { master.fill(0) }
        val dialog = AlertDialog.Builder(this).setTitle("Review scanned setup")
            .setMessage("Relay: ${invite.endpoint}\nThis device: Station ${invite.role}\nRelay token: included (hidden)\nPairing secret: included (hidden)\nPair check: $fingerprint\n\nOnly continue if this came from your other physical station. This replaces unused link settings in this chat only. The pad and device identity are not included. Use this setup to save the link without typing. Pad and identity setup remain separate in this QR fallback.")
            .setNegativeButton("Cancel", null).setPositiveButton("Use this setup") { _, _ ->
                if (latest?.cableEligible == true && !busy) {
                    urlField.setText(invite.endpoint); tokenField.setText(invite.token); secretField.setText(invite.secret)
                    roleGroup.check(if (invite.role == "A") R.id.station_a else R.id.station_b)
                    runTask {
                        checkNotNull(engine).acceptInvite(invite)
                        "LINK SAVED · continue pad setup or start fresh cable pairing"
                    }
                } else report("Existing pairing preserved")
            }.create()
        pairingDialog = dialog; handler.removeCallbacks(poll)
        dialog.setOnDismissListener { pairingDialog = null; schedule() }
        secureShow(dialog)
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
        handler.removeCallbacks(poll); dialog.setOnDismissListener { schedule() }; secureShow(dialog)
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
        secureShow(dialog)
    }
    private fun secureShow(dialog: AlertDialog) {
        openDialogs.removeAll { !it.isShowing }
        dialog.window?.protectPrivateContent()
        openDialogs.add(dialog); dialog.show()
        for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply { setTextColor(ink); isAllCaps = false; filterTouchesWhenObscured = true }
        }
    }
    private fun confirm(title: String, detail: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> if (!busy) action() }.create().also(::secureShow)
    }
    private fun report(message: String) {
        if (!active || !unlocked || !::status.isInitialized) return
        if (status.text.toString() != message) status.text = message
        status.visibility = if (latest?.setupComplete == true && message in listOf("STATION READY", "LATEST MESSAGES", "Connected")) View.GONE else View.VISIBLE
    }
    private fun updateNavigation() {
        if (::navigation.isInitialized) navigation.visibility = if (latest?.setupComplete == true && tab == 0) View.GONE else View.VISIBLE
    }
    private fun setBusy(value: Boolean) {
        busy = value
        if (!value) networkBusy = false
        chatButton.isEnabled = !value && book != null
        for (view in listOf(sendButton, saveButton, saveRelayButton, syncButton, urlField, tokenField, secretField, roleGroup)) view.isEnabled = !value
        syncButton.isEnabled = !value && latest?.role != null && latest?.role != "—" && latest?.pairingComplete == true && latest?.relayRevoked != true && !errorLoading
        updateSendButton()
        showQrButton.isEnabled = !value && latest?.role != null && latest?.role != "—" && !errorLoading
        scanQrButton.isEnabled = !value && latest?.cableEligible == true && !errorLoading
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
        renewButton.isEnabled = !value && latest?.peerDevice != null && latest?.relayRevoked != true
        verifyButton.isEnabled = !value && latest?.ownDevice == null && latest?.padCheck != null && (latest?.padVerified == true || latest?.padRemaining != 0)
        updateIndicators()
    }
    private fun updateSendButton() {
        if (!::sendButton.isInitialized) return
        val bytes = composer.text.toString().toByteArray(Charsets.UTF_8).size
        sendButton.isEnabled = !busy && latest?.canSend == true && latest?.padCheck != null && !errorLoading && bytes in 1..2048
    }
    private fun updateIndicators() {
        if (!::indicators.isInitialized) return
        val v = latest
        if (::receiveButton.isInitialized) {
            receiveButton.text = if (ReceiveService.running) "Receiving ●" else if (ReceiveService.enabled(this)) "Resume" else "Receive"
            receiveButton.contentDescription = "Background receiving. " + if (ReceiveService.running) "On" else "Off or paused"
        }
        val connection = when {
            v == null || v.role == "—" -> "○ Set up this chat"
            v.relayRevoked -> "! Chat access revoked"
            networkBusy -> "○ Checking relay…"
            connectionFailed -> "! $connectionProblem"
            lastContact == 0L -> "○ Not checked yet"
            android.os.SystemClock.elapsedRealtime() - lastContact > 60000 -> "○ Check needed"
            else -> "● Relay checked"
        }
        val security = when {
            v?.ownDevice != null && v.peerDevice == null -> "DEVICE EXCHANGE INCOMPLETE"
            v?.peerDevice != null -> "DEVICE VERIFIED"
            v?.padCheck == null -> "PAD SETUP NEEDED"
            v.padVerified && v.peerReady -> "PAD READY"
            else -> "PAD CHECK NEEDED"
        }
        val accessBadge = if (v?.padCheck != null && !v.relayRevoked) if (v.relayScoped) " · Private chat" else " · Shared access" else ""
        val details = listOfNotNull(
            v?.unread?.takeIf { it > 0 }?.let { "$it new" },
            v?.pending?.takeIf { it > 0 }?.let { "$it waiting to send" },
            v?.awaitingReceipt?.takeIf { it > 0 }?.let { "$it awaiting delivery" }
        ).joinToString(" · ").ifEmpty { "No unread messages" }
        val value = "$connection$accessBadge${if (!automatic) " · Manual" else ""}\n$details"
        val access = if (v?.relayRevoked == true) "Chat access revoked" else if (v?.relayScoped == true) "Chat access protected" else "Shared relay access; update relay to protect this chat"
        indicators.contentDescription = "$value. $security. $access. Tap for details."
        if (indicators.text.toString() != value) indicators.text = value
    }
    private fun syncChats(): String {
        try { return checkNotNull(engine).sync() }
        finally { book?.refresh(); book?.syncAnother() }
    }
    private fun showChats() {
        val chats = book?.chats() ?: return
        val rows = chats.map { chat ->
            val summary = checkNotNull(book).summary(chat.id)
            "${if (chat.id == book?.active()?.id) "● " else ""}${chat.name}" +
                if (summary.problem) "\nNeeds attention" else "\n${if (summary.unread > 0) "${summary.unread} new messages" else "No new messages"}${if (summary.queued > 0) " · ${summary.queued} waiting to send" else ""}"
        }.toMutableList().apply { add("Chat settings"); add("Rename chat") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Chats · two devices each")
            .setItems(rows) { _, which ->
                when (which) { chats.size -> selectTab(1); chats.size + 1 -> editChatName(create = false); else -> switchChat(chats[which].id) }
            }
            .setPositiveButton("New chat") { _, _ -> editChatName(create = true) }
            .setNegativeButton("Close", null).create().also(::secureShow)
    }
    private fun editChatName(create: Boolean) {
        val input = field("Friend's name").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(android.text.InputFilter.LengthFilter(32))
            if (!create) setText(book?.active()?.name ?: "")
        }
        val dialog = AlertDialog.Builder(this).setTitle(if (create) "New private chat" else "Rename chat")
            .setMessage(if (create) "Each chat pairs with one friend and gets fresh keys. Only the relay address and access token are copied from this chat." else "This name stays on this device.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString()
            if (value.isBlank()) { input.error = "Enter a name"; return@setOnClickListener }
            dialog.dismiss()
            discardDraftThen {
                runTask(onSuccess = { reopenChat() }) {
                    val contacts = checkNotNull(book)
                    if (create) contacts.select(contacts.add(value).id) else contacts.rename(value)
                    "CHAT SAVED"
                }
            }
        } }
        secureShow(dialog)
    }
    private fun discardDraftThen(action: () -> Unit) {
        if (composer.text.isNotEmpty()) confirm("Discard unsent draft?", "Queued messages stay in their original chat. This only clears what you are typing.", action)
        else action()
    }
    private fun switchChat(id: String) {
        if (id == book?.active()?.id) return
        discardDraftThen {
            runTask(onSuccess = { reopenChat() }) { checkNotNull(book).select(id); "CHAT SELECTED" }
        }
    }
    private fun reopenChat() {
        composer.text.clear(); lastContact = 0L; connectionFailed = false; backoff = 15000L; tab = 0
        showTerminal()
    }
    private fun showRelayAccess() {
        val v = latest ?: return
        val explanation = when {
            v.relayRevoked -> "This chat can no longer use the relay. Local history remains on this device. Pair a fresh chat on both devices to continue."
            v.relayScoped -> "This chat uses its own relay credential. The shared setup token cannot read or write this protected mailbox. Other chats use different credentials. Revoking stops relay access for BOTH devices and removes this chat's queued server packets. Local history is kept."
            else -> "This chat currently uses shared relay access. Update the relay, then tap Sync on both devices to enable chat protection automatically. Update both apps before doing so: older apps cannot access a protected mailbox."
        }
        val builder = AlertDialog.Builder(this).setTitle("Chat relay access").setMessage(explanation).setNegativeButton("Close", null)
        if (v.relayScoped && !v.relayRevoked) builder.setPositiveButton("Revoke this chat") { _, _ ->
            confirm("Revoke access for both devices?", "This chat will stop sending and receiving through the relay. Local messages stay saved. You will need fresh physical pairing on both devices to continue.") {
                runTask(network = true) { checkNotNull(engine).revokeRelayAccess(); "CHAT ACCESS REVOKED · local history kept" }
            }
        }
        if (v.relayRevoked) builder.setPositiveButton("Pair again") { _, _ -> selectTab(1) }
        builder.create().also(::secureShow)
    }
    private fun showStationStatus() {
        val v = latest ?: return
        val contact = if (lastContact == 0L) "No successful relay check this session" else {
            val seconds = ((android.os.SystemClock.elapsedRealtime() - lastContact) / 1000).coerceAtLeast(0)
            "Last successful relay check: ${if (seconds < 60) "$seconds seconds" else "${seconds / 60} minutes"} ago"
        }
        AlertDialog.Builder(this).setTitle("Station ${v.role} · status")
            .setMessage("$contact\n${if (connectionFailed) connectionProblem else "Checks run while this app is open; this is not peer online status"}\n${if (v.relayRevoked) "CHAT ACCESS REVOKED" else if (v.relayScoped) "CHAT ACCESS PROTECTED" else "SHARED RELAY ACCESS · update relay"}\n\n${v.pending} packets queued locally\n${v.awaitingReceipt} messages awaiting a saved receipt\n${v.unread} new messages\n\n${v.padRemaining?.let { "$it outgoing pad slots remaining" } ?: "No pad installed · sending disabled"}\n${if (v.peerDevice != null) "Peer device key pinned" else if (v.ownDevice != null) "DEVICE EXCHANGE INCOMPLETE" else "Device identity not bound"}\n\nPeer saved means the other device stored the message; it does not mean a person read it. Retrying uses the same encrypted packets and no new pad slots.\n\n${status.text}")
            .setPositiveButton("Chat access") { _, _ -> showRelayAccess() }
            .setNegativeButton("Close", null)
            .setNeutralButton(if (v.relayRevoked) "Pair again" else "Retry") { _, _ ->
                if (v.relayRevoked) selectTab(1) else runTask(onSuccess = { runTask(network = true) { syncChats() } }) {
                    val n = checkNotNull(engine).retryDelivery()
                    "$n SAVED MESSAGES REQUEUED · checking existing receipts"
                }
            }.create().also(::secureShow)
    }
    private fun render(view: StationEngine.View) {
        latest = view; updateNavigation()
        advancedSetup.visibility = if (advancedShown) View.VISIBLE else View.GONE
        val paired = view.peerDevice != null
        serverForm.visibility = if (!paired || advancedShown) View.VISIBLE else View.GONE
        pairButton.visibility = if (!paired || advancedShown) View.VISIBLE else View.GONE
        pairingHelp.visibility = if (paired) View.GONE else View.VISIBLE
        settingsIntro.text = if (paired) "Your friend's device is verified. Renew its pad here while keeping your history and identities." else "Save server details on one device, then pair by USB. Advanced setup includes QR and NFC options."
        pairedOptions.visibility = if (view.padCheck != null) View.VISIBLE else View.GONE
        stationLabel.visibility = if (view.setupComplete && tab == 0) View.GONE else View.VISIBLE
        renewButton.isEnabled = !busy && view.peerDevice != null && !view.relayRevoked

        val current = book?.active()
        val elsewhere = book?.unreadElsewhere() ?: 0
        val chatLabel = "${current?.name ?: "Chats"} ▾${if (elsewhere > 0) " · $elsewhere new" else ""}"
        if (chatButton.text.toString() != chatLabel) chatButton.text = chatLabel
        val tabLabel = if (compact) "Messages" else if (view.unread > 0) "Messages (${view.unread})" else "Messages"
        if (trafficTab.text.toString() != tabLabel) trafficTab.text = tabLabel
        updateIndicators()
        newMessagesButton.visibility = if (view.unread > 0) View.VISIBLE else View.GONE
        newMessagesButton.text = "${view.unread} new · Jump to latest"
        val hint = when {
            view.role == "—" -> "SET UP YOUR STATION → Link"
            view.relayRevoked -> "CHAT ACCESS REVOKED · open chat settings to pair afresh"
            !view.pairingComplete -> "USB exchange paused · reconnect both devices to recover"
            view.padCheck == null -> "PAIR WITH YOUR FRIEND → Link · USB pairs keys and pad together"
            view.padRemaining == 0 -> "Pad empty · renew by USB. You can still receive and retry saved messages."
            !view.padVerified -> "COMPARE CODES IN PERSON → Pad"
            !view.peerReady -> "WAITING FOR PEER VERIFICATION · Sync both stations"
            view.ownDevice != null && view.peerDevice == null -> "FINISH DEVICE EXCHANGE → Device"
            (view.padRemaining ?: Int.MAX_VALUE) <= 10 -> "Pad running low · open Chat settings → Renew pad"
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
        if (view.messages == rendered && renderedSetupComplete == view.setupComplete) return
        renderedSetupComplete = view.setupComplete
        val wasAtBottom = atBottom()
        rendered = view.messages; messages.removeAllViews()
        if (view.messages.isEmpty()) {
            messages.addView(text(if (view.setupComplete) "Ready for your friend." else "Make a private connection.", if (compact) 20f else 24f).apply { setPadding(dp(10), dp(40), 0, dp(10)) })
            messages.addView(text(if (view.setupComplete) "Send a message below. Delivery updates appear beside each message." else "Save relay details on one device, then connect both with USB and compare the on-screen codes.", 14f, muted).apply { setPadding(dp(10), 0, 0, 0) })
            if (!view.setupComplete) messages.addView(button("Set up this chat") { selectTab(1) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        for (m in view.messages) {
            val card = column().apply {
                setPadding(dp(14), dp(11), dp(14), dp(12))
                background = surface(Color.WHITE, 2, if (m.incoming) ink else lineColor)
            }
            val label = if (m.incoming) (if (m.unread) "● New · ${current?.name ?: "Friend"}" else current?.name ?: "Friend") else when (m.status) { "queued" -> "Waiting to send"; "relayed" -> "Sent · awaiting delivery"; "delivered" -> "✓ Delivered to device"; else -> "Expired" }
            card.addView(text("$label  /  ${timeFormat.format(java.util.Date(m.time))}${if (m.deviceVerified) "" else " · Unverified device"}", 10f, muted, true))
            card.addView(text(m.text, 16f, ink, true).apply { setPadding(0, dp(5), 0, 0); isSaveEnabled = false })
            messages.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10); if (m.incoming) rightMargin = dp(24) else leftMargin = dp(24)
            })
        }
        if (wasAtBottom) messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun runTask(network: Boolean = false, load: Boolean = false, clearComposer: Boolean = false, publicTransfer: Boolean = false, qrTransfer: Boolean = false, onSuccess: (() -> Unit)? = null, action: () -> String) {
        if (busy || !active || !unlocked || isFinishing) return
        val taskGeneration = generation
        networkBusy = network; setBusy(true); handler.removeCallbacks(poll)
        worker.execute {
            var note: String; var view: StationEngine.View? = null
            var config: org.json.JSONObject? = null; var success = false; var relayFailure = false; var failureLabel = "RELAY UNAVAILABLE"
            try {
                note = action(); success = true; if (network) backoff = 15000L
                book?.refresh(); view = engine?.view(); if (load) config = engine?.state?.let { org.json.JSONObject(it.toString()) }
            } catch (e: Exception) {
                relayFailure = network
                failureLabel = when (e) {
                    is RelayHttpException -> when (e.status) { 401, 403 -> "ACCESS DENIED"; 410 -> "CHAT ACCESS REVOKED"; 429 -> "RELAY BUSY"; else -> "RELAY ERROR ${e.status}" }
                    is javax.net.ssl.SSLException -> "TLS CHECK FAILED"
                    is java.io.IOException -> "RELAY UNAVAILABLE"
                    else -> "CHECK RELAY SETTINGS"
                }
                note = if (load && engine != null && e is java.io.IOException) {
                    if (qrTransfer) "QR IMAGE COULD NOT BE READ // no settings changed"
                    else if (publicTransfer) "PUBLIC CARD TRANSFER FAILED // identity retained; retry the file transfer"
                    else "PAD FILE TRANSFER FAILED // after failed export use a fresh pairing and pad"
                } else friendly(e); if (network) backoff = (backoff * 2).coerceAtMost(60000L)
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
                if (network && success) { lastContact = android.os.SystemClock.elapsedRealtime(); connectionFailed = false }
                else if (relayFailure) {
                    connectionFailed = true
                    connectionProblem = failureLabel
                }
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
            401, 403 -> "ACCESS DENIED // check chat access or the setup token in Settings"
            410 -> "CHAT ACCESS REVOKED // pair a fresh chat on both devices"
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
        openDialogs.toList().forEach { it.dismiss() }; openDialogs.clear()
        pairingDialog?.dismiss(); pairingDialog = null
        generation++; active = false; StationRuntime.uiVisible = false; unlocked = false; needsTerminal = true; handler.removeCallbacks(poll)
        if (::composer.isInitialized) composer.text.clear()
        if (::messages.isInitialized) messages.removeAllViews()
        if (::chatButton.isInitialized) chatButton.text = "Chats"
        if (::tokenField.isInitialized) { tokenField.text.clear(); secretField.text.clear() }
        latest = null; rendered = null; importedDeviceCard = null
        if (::peerCardField.isInitialized) peerCardField.text.clear()
        worker.execute { engine = null; book = null }
        super.onStop()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
