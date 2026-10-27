package org.example.otppager

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

/** Everyday screens contain presentation and explicit actions, never keys or protocol logic. */
internal class PagerScreens(private val activity: Activity, private val actions: Actions) {
    interface Actions {
        fun back(destination: Int)
        fun appMenu()
        fun addFriend()
        fun openFriend(id: String, paired: Boolean)
        fun saveServer(address: String, token: String)
        fun cable()
        fun nearby()
        fun padSize()
        fun advanced()
        fun renew()
        fun receiving()
        fun rename()
        fun retryRetirement()
        fun freshPairing()
        fun status()
    }
    data class Friend(val id: String, val name: String, val detail: String, val paired: Boolean, val unread: Boolean)
    private val ink = Color.rgb(20, 20, 20)
    private val muted = Color.rgb(85, 85, 85)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, sp: Float = 15f, quiet: Boolean = false) = TextView(activity).apply {
        text = value; textSize = sp; setTextColor(if (quiet) muted else ink)
        typeface = Typeface.MONOSPACE; setLineSpacing(dp(2).toFloat(), 1f); isSaveEnabled = false
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = value; isAllCaps = false; textSize = 15f; typeface = Typeface.MONOSPACE
        minHeight = dp(48); minimumHeight = dp(48); stateListAnimator = null; elevation = 0f
        setPadding(dp(14), dp(8), dp(14), dp(8)); setTextColor(if (primary) Color.WHITE else ink)
        background = GradientDrawable().apply { setColor(if (primary) ink else Color.WHITE); cornerRadius = dp(2).toFloat(); if (!primary) setStroke(dp(1), Color.rgb(215, 215, 215)) }
        filterTouchesWhenObscured = true; setOnClickListener { action() }
        isSaveEnabled = false
    }
    private fun header(title: String, back: Int? = null, menu: Boolean = false) = row().apply {
        if (back != null) addView(button("‹") { actions.back(back) }.apply { contentDescription = "Back" }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(label(title, 24f).apply { setPadding(dp(if (back == null) 0 else 10), 0, 0, 0); typeface = Typeface.create("monospace", Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(60), 1f))
        if (menu) addView(button("⋮") { actions.appMenu() }.apply { contentDescription = "App options" }, LinearLayout.LayoutParams(dp(44), dp(44)))
    }
    val inbox = column()
    private val inboxRows = column()
    private val inboxStatus = label("Private conversations", 12f, true)
    private val inboxNotice = label("", 13f).apply { visibility = View.GONE }
    private val setupNotice = label("", 13f).apply { visibility = View.GONE }
    private val detailsNotice = label("", 13f).apply { visibility = View.GONE }
    private lateinit var addButton: Button
    val setup = column()
    private val setupBody = column()
    private val setupTitle = label("Pair with a friend", 23f)
    private val setupIntro = label("Only one device needs server details.", 14f, true)
    private val serverStep = column()
    private val pairStep = column()
    private val recoveryStep = column()
    private val setupAddress = field("Server address", false)
    private val setupToken = field("Access token", true)
    private val sizeButton = button("Pad size · 4 MiB") { actions.padSize() }
    private var skipServer = false
    private var shownFriends: List<Friend>? = null
    val details = column()
    private val friendName = label("Friend", 26f)
    private val friendState = label("Private conversation", 14f, true)
    private val keys = label("", 14f, true)
    val retired = column()
    private val retiredName = label("Retired pairing", 24f)
    private val retiredStatus = label("Local cleanup status unavailable", 18f)
    private val retiredNotice = label("", 13f).apply { visibility = View.GONE }
    private val retryRetirementButton = button("Retry local cleanup") { actions.retryRetirement() }.apply { isEnabled = false; alpha = 0.45f }
    private val freshPairingButton = button("Start fresh pairing", true) { actions.freshPairing() }.apply { isEnabled = false; alpha = 0.45f }
    init {
        retired.addView(header("Pairing recovery", back = 4))
        retired.addView(ScrollView(activity).apply { isFillViewport = true; scrollBarStyle = View.SCROLLBARS_INSIDE_INSET; setPadding(0, 0, dp(8), dp(12)); addView(column().apply {
            addView(retiredName.apply { setPadding(0, dp(20), 0, dp(16)) })
            addView(retiredStatus)
            addView(label("This pairing is permanently disabled on this device. No remote wipe has been requested; the other device may still hold its copy.", 15f).apply { setPadding(0, dp(16), 0, dp(16)) })
            addView(label("Local cleanup removes this pairing's vault, draft and identity keys and their files. The contact name and minimal reuse/retirement records remain. Flash, memory and exported copies cannot be guaranteed erased.", 13f, true))
            addView(retiredNotice)
            addView(retryRetirementButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
            addView(freshPairingButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(button("Back to chats") { actions.back(4) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }) }, LinearLayout.LayoutParams(-1, 0, 1f))
        inbox.addView(header("Chats", menu = true))
        inbox.addView(inboxStatus.apply { setPadding(0, dp(2), 0, dp(14)); setOnClickListener { actions.receiving() } })
        inbox.addView(inboxNotice)
        inbox.addView(ScrollView(activity).apply { addView(inboxRows); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        addButton = button("Add friend", true) { actions.addFriend() }
        inbox.addView(addButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        setup.addView(header("Add friend", back = 4))
        setup.addView(setupNotice)
        setup.addView(ScrollView(activity).apply { addView(setupBody) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setupBody.addView(setupTitle.apply { setPadding(0, dp(18), 0, dp(10)) })
        setupBody.addView(setupIntro.apply { setPadding(0, 0, 0, dp(20)) })
        serverStep.addView(setupAddress, LinearLayout.LayoutParams(-1, -2))
        serverStep.addView(setupToken, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        serverStep.addView(button("Save and continue", true) { actions.saveServer(setupAddress.text.toString(), setupToken.text.toString()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        serverStep.addView(button("Get setup from my friend") { skipServer = true; serverStep.visibility = View.GONE; pairStep.visibility = View.VISIBLE; setupIntro.text = "Open pairing on both devices. Choose the same method on each." }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        setupBody.addView(serverStep)
        pairStep.addView(button("Use a USB cable", true) { actions.cable() }, LinearLayout.LayoutParams(-1, -2))
        pairStep.addView(button("Tap + Wi-Fi") { actions.nearby() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        pairStep.addView(label("USB is the preferred physical exchange. Wireless pairing is experimental; keep the NFC tap private.", 13f, true).apply { setPadding(0, dp(12), 0, dp(18)) })
        pairStep.addView(sizeButton, LinearLayout.LayoutParams(-1, -2))
        pairStep.addView(button("Enter server details") { skipServer = false; serverStep.visibility = View.VISIBLE; pairStep.visibility = View.GONE; setupIntro.text = "Only one device needs server details." }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        setupBody.addView(pairStep)
        recoveryStep.addView(label("This conversation already has pairing material. Use the existing setup and recovery tools; do not copy a used pad or change the friend's device key.", 15f).apply { setPadding(0, 0, 0, dp(16)) })
        recoveryStep.addView(button("Continue existing setup", true) { actions.advanced() }, LinearLayout.LayoutParams(-1, -2))
        setupBody.addView(recoveryStep)
        setupBody.addView(button("Other setup options") { actions.advanced() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(16) })

        details.addView(header("Friend details", back = 0))
        details.addView(detailsNotice)
        val body = column()
        details.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(friendName.apply { setPadding(0, dp(20), 0, dp(8)) })
        body.addView(friendState.apply { setPadding(0, 0, 0, dp(24)) })
        body.addView(button("Connection details") { actions.status() }, LinearLayout.LayoutParams(-1, -2))
        body.addView(label("Message keys", 17f).apply { setPadding(0, dp(24), 0, dp(8)) })
        body.addView(keys)
        body.addView(button("Renew message keys") { actions.renew() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        body.addView(button("Rename friend") { actions.rename() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        body.addView(button("Receiving & battery") { actions.receiving() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        body.addView(label("Messages and drafts stay locally for up to 24 hours. Delivery means saved on the other device, not read by a person.", 13f, true).apply { setPadding(0, dp(24), 0, dp(18)) })
        body.addView(button("Advanced setup") { actions.advanced() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun field(hint: String, secret: Boolean) = EditText(activity).apply {
        this.hint = hint; textSize = 16f; typeface = Typeface.MONOSPACE; setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        if (secret) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        setPadding(dp(12), dp(12), dp(12), dp(12)); setTextColor(ink); setHintTextColor(muted)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSaveEnabled = false; filterTouchesWhenObscured = true
    }
    fun renderInbox(friends: List<Friend>, receiving: Boolean, busy: Boolean) {
        addButton.isEnabled = true; addButton.alpha = 1f
        inboxStatus.text = if (busy) "Checking for messages…" else if (receiving) "Background receiving is on" else "Tap here for receiving options"
        if (friends == shownFriends) return
        shownFriends = friends.toList()
        inboxRows.removeAllViews()
        if (friends.isEmpty()) {
            inboxRows.addView(label("A quieter way to talk.", 23f).apply { setPadding(0, dp(48), 0, dp(16)) })
            inboxRows.addView(label("Add a friend, pair your devices, then just write.", 16f, true))
        }
        for (friend in friends) {
            inboxRows.addView(button("${if (friend.unread) "● " else ""}${friend.name}\n${friend.detail}") { actions.openFriend(friend.id, friend.paired) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 16f; minHeight = dp(80)
                contentDescription = "${friend.name}. ${friend.detail}"
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }
    fun renderFriend(name: String, view: StationEngine.View, padSize: Int) {
        friendName.text = name
        friendState.text = when { view.relayRevoked -> "Access revoked · pair again to continue"; !view.pairingComplete -> "Pairing needs attention"; view.setupComplete -> "Paired with your friend's device"; else -> "Pairing is not finished" }
        keys.text = view.padRemaining?.let { "$it outgoing slots remaining. Messages and delivery receipts use slots." } ?: "Pair your devices to install message keys."
        setupTitle.text = if (view.cableEligible) "Pair with $name" else "Finish pairing"
        setupIntro.text = if (view.role == "—" && !skipServer) "Only one device needs server details." else "Keep both devices together and choose the same method on each."
        serverStep.visibility = if (view.cableEligible && view.role == "—" && !skipServer) View.VISIBLE else View.GONE
        pairStep.visibility = if (view.cableEligible && (view.role != "—" || skipServer)) View.VISIBLE else View.GONE
        recoveryStep.visibility = if (!view.cableEligible) View.VISIBLE else View.GONE
        sizeButton.text = "Pad size · ${padSize / PadProtocol.MATERIAL_BYTES} MiB · Change"
    }
    fun renderRetired(name: String, status: VaultRetirement.Status, busy: Boolean) {
        retiredName.text = name
        retiredStatus.text = when (status.state) {
            VaultRetirement.LocalState.COMPLETE -> "Local cleanup completed"
            VaultRetirement.LocalState.INCOMPLETE -> "Local cleanup incomplete"
            else -> "Local cleanup status unavailable"
        }
        retryRetirementButton.isEnabled = !busy
        freshPairingButton.isEnabled = !busy && status.state == VaultRetirement.LocalState.COMPLETE
        retryRetirementButton.alpha = if (retryRetirementButton.isEnabled) 1f else 0.45f
        freshPairingButton.alpha = if (freshPairingButton.isEnabled) 1f else 0.45f
    }
    fun resetSetup() { skipServer = false }
    fun showProblem() {
        inboxStatus.text = "Saved chats need attention"
        addButton.isEnabled = false; addButton.alpha = 0.5f
        shownFriends = null; inboxRows.removeAllViews()
        inboxRows.addView(label("This chat couldn't be opened.", 22f).apply { setPadding(0, dp(32), 0, dp(16)) })
        inboxRows.addView(label("Your saved data has not been reset. Open recovery tools to inspect the problem.", 15f, true).apply { setPadding(0, 0, 0, dp(20)) })
        inboxRows.addView(button("Open recovery tools") { actions.advanced() }, LinearLayout.LayoutParams(-1, -2))
    }
    fun opening(value: String) { inboxStatus.text = value }
    fun notice(value: String, page: Int) {
        val target = when (page) { 4 -> inboxNotice; 5 -> setupNotice; 6 -> detailsNotice; 7 -> retiredNotice; else -> return }
        target.text = value; target.visibility = View.VISIBLE; target.setPadding(0, dp(8), 0, dp(12))
    }
    fun clearNotices() { for (view in listOf(inboxNotice, setupNotice, detailsNotice, retiredNotice)) { view.text = ""; view.visibility = View.GONE } }
    fun clearPrivate() { retiredName.text = "Retired pairing"; retiredStatus.text = "Local cleanup status unavailable"; setupAddress.text.clear(); setupToken.text.clear(); inboxRows.removeAllViews(); shownFriends = null; friendName.text = "Friend"; keys.text = ""; friendState.text = "Private conversation"; setupTitle.text = "Pair with a friend"; clearNotices() }
}
