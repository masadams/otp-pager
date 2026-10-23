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
    private var generation = 0
    private var busy = false
    private var active = false
    private var unlocked = false
    private var backoff = 15000L
    private var tab = 0
    private var latest: StationEngine.View? = null
    private var rendered: List<StationEngine.Message>? = null
    private var errorLoading = false
    private var automatic = true
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
            if (active && unlocked && automatic && !busy && !errorLoading) runTask { engine?.let { it.sync() } ?: "OPEN LINK TO PAIR" }
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
        setSingleLine(true); setPadding(dp(12), dp(12), dp(12), dp(12))
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
        } else schedule()
    }
    private fun showUnlock() {
        busy = false
        val lock = column().apply { setBackgroundColor(paper); setPadding(dp(28), dp(80), dp(28), dp(24)) }
        lock.addView(text("FIELD / 02", 28f, ink, true))
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
        if (requestCode == 71 && resultCode == RESULT_OK) { unlocked = true; showTerminal() }
    }
    private fun showTerminal() {
        generation++
        latest = null; rendered = null; busy = false; errorLoading = false
        root = column().apply { setBackgroundColor(paper); setPadding(dp(20), dp(10), dp(20), dp(8)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(dp(20), insets.systemWindowInsetTop + dp(10), dp(20), insets.systemWindowInsetBottom + dp(8)); insets
        }
        val header = row()
        header.addView(text("FIELD", 28f, ink, true).apply { typeface = Typeface.create("monospace", Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        stationLabel = text("STATION —", 11f, green, true); header.addView(stationLabel); root.addView(header)
        root.addView(text("PRIVATE CORRESPONDENCE / EXPERIMENTAL", 10f, muted, true))
        val tabs = row().apply { setPadding(0, dp(12), 0, dp(4)) }
        trafficTab = button("Messages") { selectTab(0) }; linkTab = button("Link") { selectTab(1) }
        tabs.addView(trafficTab, LinearLayout.LayoutParams(0, -2, 1f)); tabs.addView(linkTab, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(tabs)
        status = text("Opening station…", 11f, muted, true).apply { setPadding(dp(2), dp(4), 0, dp(10)) }; root.addView(status)
        traffic = column()
        messageScroll = ScrollView(this).apply { isFillViewport = true }
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
        settings.addView(text("Your private link", 22f))
        settings.addView(text("Exchange the pairing secret in person. Give each device a different station letter.", 14f, muted))
        urlField = field("https://pager.example.com"); section(settings, "RELAY ADDRESS", urlField)
        tokenField = field("Relay access token", true); section(settings, "RELAY ACCESS", tokenField)
        secretField = field("43-character pairing secret", true); section(settings, "PAIRING SECRET", secretField)
        val secrets = row()
        secrets.addView(button("Show / hide") {
            secretField.inputType = InputType.TYPE_CLASS_TEXT or if ((secretField.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD)
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            secretField.typeface = Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(0, -2, 1f))
        secrets.addView(button("Generate new") {
            if (latest?.role != "—") report("Forget your existing pairing before generating a new one")
            else secretField.setText(Protocol.randomSecret())
        }, LinearLayout.LayoutParams(0, -2, 1f)); settings.addView(secrets)
        roleGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((index, label) in listOf("Station A", "Station B").withIndex()) roleGroup.addView(RadioButton(this).apply {
            id = 101 + index; text = label; setTextColor(ink); buttonTintList = ColorStateList.valueOf(green)
        }, RadioGroup.LayoutParams(0, dp(48), 1f))
        roleGroup.check(101); section(settings, "THIS DEVICE", roleGroup)
        saveButton = button("Save & connect", true) {
            val endpoint = urlField.text.toString(); val token = tokenField.text.toString().trim()
            val secret = secretField.text.toString().trim(); val role = if (roleGroup.checkedRadioButtonId == 101) "A" else "B"
            runTask { checkNotNull(engine).configure(endpoint, token, secret, role); "LINK SAVED // checking relay" }
        }; settings.addView(saveButton)
        pairingCode = text("NOT PAIRED", 12f, green, true).apply { setPadding(0, dp(20), 0, dp(12)) }; settings.addView(pairingCode)
        settings.addView(text("Compare this pair-check code on both devices in person. Matching codes confirm the same secret; they do not confirm the server is honest.", 12f, muted))
        settings.addView(Switch(this).apply {
            text = "Auto sync every 15 seconds while open"; setTextColor(ink); isChecked = automatic
            setPadding(0, dp(20), 0, dp(12)); setOnCheckedChangeListener { _, checked -> automatic = checked; schedule() }
        })
        settings.addView(text("Messages stay locally for up to 24 hours. Clear history to remove them sooner. Delivery means the other station saved the packet, not that a person read it.", 12f, muted))
        settings.addView(button("Clear message history") { confirm("Clear local messages?", "Pending messages stay queued. Replay protection stays active.") {
            runTask { checkNotNull(engine).clearHistory(); "LOCAL HISTORY CLEARED" }
        } })
        settings.addView(button("Forget pairing") { confirm("Forget this pairing?", "This deletes your saved link and queued messages. Re-pair BOTH devices with a NEW secret; reusing the old one can break replay protection.") {
            runTask { SecureStore(this).forget(); engine = StationEngine(SecureStore(this)); errorLoading = false; "PAIRING FORGOTTEN // use a fresh secret" }
            urlField.setText(""); tokenField.setText(""); secretField.setText(""); roleGroup.check(101)
        } })
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure)
            settings.addView(text("Set an Android screen lock to enable station unlock protection.", 12f, muted))
        linkScroll = ScrollView(this).apply { addView(settings) }
        root.addView(traffic, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(linkScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); root.requestApplyInsets(); selectTab(tab)
        runTask(load = true) { engine = StationEngine(SecureStore(this)); "STATION READY" }
    }
    private fun selectTab(value: Int) {
        tab = value; traffic.visibility = if (value == 0) View.VISIBLE else View.GONE; linkScroll.visibility = if (value == 1) View.VISIBLE else View.GONE
        trafficTab.setTextColor(if (value == 0) green else muted); linkTab.setTextColor(if (value == 1) green else muted)
    }
    private fun confirm(title: String, detail: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> if (!busy) action() }.show()
    }
    private fun report(message: String) { if (active && unlocked && ::status.isInitialized && status.text.toString() != message) status.text = message }
    private fun setBusy(value: Boolean) {
        busy = value
        for (view in listOf(sendButton, saveButton, syncButton, urlField, tokenField, secretField, roleGroup)) view.isEnabled = !value
        sendButton.isEnabled = !value && latest?.role != null && latest?.role != "—" && !errorLoading
    }
    private fun render(view: StationEngine.View) {
        latest = view; if (stationLabel.text.toString() != "STATION ${view.role}") stationLabel.text = "STATION ${view.role}"
        if (pairingCode.text.toString() != "PAIR CHECK\n${view.fingerprint}") pairingCode.text = "PAIR CHECK\n${view.fingerprint}"
        if (view.messages == rendered) return
        val wasAtBottom = messageScroll.getChildAt(0).height - (messageScroll.scrollY + messageScroll.height) < dp(100)
        rendered = view.messages; messages.removeAllViews()
        if (view.messages.isEmpty()) {
            messages.addView(text("A quiet channel.", 24f).apply { setPadding(dp(10), dp(40), 0, dp(10)) })
            messages.addView(text(if (view.role == "—") "Open Link to pair this station." else "Write your first message below.", 14f, muted).apply { setPadding(dp(10), 0, 0, 0) })
        }
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        for (m in view.messages) {
            val card = column().apply {
                setPadding(dp(14), dp(11), dp(14), dp(12))
                background = surface(Color.WHITE, 2, lineColor)
            }
            val label = if (m.incoming) "INCOMING" else m.status.uppercase()
            card.addView(text("$label  /  ${timeFormat.format(java.util.Date(m.time))}", 10f, muted, true))
            card.addView(text(m.text, 16f).apply { setPadding(0, dp(5), 0, 0); isSaveEnabled = false })
            messages.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10); if (m.incoming) rightMargin = dp(24) else leftMargin = dp(24)
            })
        }
        if (wasAtBottom) messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun runTask(load: Boolean = false, clearComposer: Boolean = false, action: () -> String) {
        if (busy || !active || !unlocked || isFinishing) return
        val taskGeneration = generation
        setBusy(true); handler.removeCallbacks(poll)
        worker.execute {
            var note: String; var view: StationEngine.View? = null
            var config: org.json.JSONObject? = null; var success = false
            try {
                note = action(); success = true; backoff = 15000L
                view = engine?.view(); if (load) config = engine?.state?.let { org.json.JSONObject(it.toString()) }
            } catch (e: Exception) {
                note = friendly(e); backoff = (backoff * 2).coerceAtMost(60000L)
                if (load) errorLoading = true
                try { view = engine?.view() } catch (_: Exception) { errorLoading = true }
            }
            runOnUiThread {
                if (!active || !unlocked || isFinishing || taskGeneration != generation) return@runOnUiThread
                if (config != null) {
                    urlField.setText(config!!.getString("endpoint")); tokenField.setText(config!!.getString("token"))
                    secretField.setText(config!!.getString("secret")); roleGroup.check(if (config!!.getString("role") == "A") 101 else 102)
                }
                view?.let(::render)
                if (clearComposer && success) composer.text.clear()
                if (errorLoading) note = "VAULT UNAVAILABLE // data preserved; see Link → Forget pairing to reset"
                report(note); setBusy(false); schedule(if (clearComposer && success) 100 else backoff)
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
        is java.io.IOException -> "OFFLINE // queued traffic is retained; retrying"
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "INVALID SETTINGS"
        else -> "STATION ERROR // saved data retained"
    }
    private fun schedule(delay: Long = backoff) {
        handler.removeCallbacks(poll)
        if (active && unlocked && !isFinishing && !errorLoading) handler.postDelayed(poll, delay)
    }
    override fun onStop() {
        generation++; active = false; unlocked = false; handler.removeCallbacks(poll)
        if (::composer.isInitialized) composer.text.clear()
        if (::messages.isInitialized) messages.removeAllViews()
        if (::tokenField.isInitialized) { tokenField.text.clear(); secretField.text.clear() }
        latest = null; rendered = null
        worker.execute { engine = null }
        super.onStop()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
