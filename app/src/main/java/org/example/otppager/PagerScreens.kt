package org.example.otppager

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

/** Everyday screens contain presentation and explicit actions, never keys or protocol logic. */
private const val NO_REMOTE_WIPE = "This pairing is permanently disabled on this device. No remote wipe has been requested; the other device may still hold its copy."

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
        fun dismissRetired()
        fun status()
        fun wipe()
        fun testConnection() {}
        fun testServer(address: String) {}
    }
    data class Friend(val id: String, val name: String, val detail: String, val paired: Boolean, val unread: Boolean)
    private val ink = UiStyle.ink
    private val muted = UiStyle.muted
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, sp: Float = 15f, quiet: Boolean = false) = TextView(activity).apply {
        text = value; textSize = sp; setTextColor(if (quiet) muted else ink)
        typeface = UiStyle.body; setLineSpacing(dp(2).toFloat(), 1f); isSaveEnabled = false
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = value; isAllCaps = false; textSize = 15f; typeface = UiStyle.medium; letterSpacing = 0.01f
        minHeight = dp(50); minimumHeight = dp(50); stateListAnimator = null; elevation = 0f
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) Color.WHITE else ink)))
        val radius = dp(UiStyle.BUTTON_RADIUS).toFloat()
        background = UiStyle.pressable(android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), UiStyle.shape(Color.WHITE, radius, UiStyle.line, dp(1)))
            addState(intArrayOf(), if (primary) UiStyle.shape(ink, radius) else UiStyle.shape(Color.WHITE, radius, UiStyle.line, dp(1)))
        }, radius)
        filterTouchesWhenObscured = true; setOnClickListener { action() }
        isSaveEnabled = false
    }
    private fun header(title: String, back: Int? = null, menu: Boolean = false) = row().apply {
        if (back != null) addView(button("‹") { actions.back(back) }.apply {
            contentDescription = "Back"; textSize = 24f; background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(label(title, 24f).apply {
            setPadding(dp(if (back == null) 0 else 4), 0, 0, 0); typeface = UiStyle.bold
            gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, dp(60), 1f))
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
    private val retiredRemote = label(NO_REMOTE_WIPE, 15f).apply { setPadding(0, dp(16), 0, dp(16)) }
    private val retryRetirementButton = button("Retry local cleanup") { actions.retryRetirement() }.apply { isEnabled = false; alpha = 0.45f }
    private val dismissRetiredButton = button("Done · remove from Chats", true) { actions.dismissRetired() }.apply { visibility = View.GONE }
    private val freshPairingButton = button("Start fresh pairing", true) { actions.freshPairing() }.apply { isEnabled = false; alpha = 0.45f }
    init {
        retired.addView(header("Pairing recovery", back = 4))
        retired.addView(ScrollView(activity).apply { isFillViewport = true; scrollBarStyle = View.SCROLLBARS_INSIDE_INSET; setPadding(0, 0, dp(8), dp(12)); addView(column().apply {
            addView(retiredName.apply { setPadding(0, dp(20), 0, dp(16)) })
            addView(retiredStatus)
            addView(retiredRemote)
            addView(label("Local cleanup removes this pairing's vault, draft and identity keys and their files. After cleanup completes, Done or returning to Chats removes this entry. Minimal reuse/retirement records and queued peer-wipe requests remain. Flash, memory and exported copies cannot be guaranteed erased.", 13f, true))
            addView(retiredNotice)
            addView(dismissRetiredButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
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
        serverStep.addView(button("Test server") { actions.testServer(setupAddress.text.toString()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        serverStep.addView(button("Get setup from my friend") { skipServer = true; serverStep.visibility = View.GONE; pairStep.visibility = View.VISIBLE; setupIntro.text = "Open pairing on both devices. Choose the same method on each." }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        setupBody.addView(serverStep)
        pairStep.addView(button("Use a USB cable", true) { actions.cable() }, LinearLayout.LayoutParams(-1, -2))
        pairStep.addView(button(if (NEARBY_PAIRING_AVAILABLE) "Tap + Wi-Fi" else "Tap + Wi-Fi · Coming soon") { actions.nearby() }.apply { isEnabled = NEARBY_PAIRING_AVAILABLE; if (!isEnabled) alpha = 0.6f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        pairStep.addView(label(if (NEARBY_PAIRING_AVAILABLE) "USB is the preferred physical exchange. Wireless pairing is experimental; keep the NFC tap private." else "Connect both phones with a USB data cable to pair. Wireless pairing is coming soon.", 13f, true).apply { setPadding(0, dp(12), 0, dp(18)) })
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
        details.addView(ScrollView(activity).apply {
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET; setPadding(0, 0, dp(8), 0); addView(body) // Keep text off the screen edge.
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(friendName.apply { setPadding(0, dp(20), 0, dp(8)) })
        body.addView(friendState.apply { setPadding(0, 0, 0, dp(24)) })
        body.addView(button("Connection details") { actions.status() }, LinearLayout.LayoutParams(-1, -2))
        body.addView(button("Test server connection") { actions.testConnection() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        body.addView(label("Message keys", 17f).apply { setPadding(0, dp(24), 0, dp(8)) })
        body.addView(keys)
        body.addView(button("Renew message keys") { actions.renew() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        body.addView(button("Rename friend") { actions.rename() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        body.addView(button("Receiving & battery") { actions.receiving() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        body.addView(label("Messages and drafts stay locally for up to 24 hours. Delivery means saved on the other device, not read by a person.", 13f, true).apply { setPadding(0, dp(24), 0, dp(18)) })
        body.addView(button("Advanced setup") { actions.advanced() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        // Local-only destruction stays last and visually separate from everyday options.
        body.addView(button("Wipe this chat from this phone") { actions.wipe() }.apply {
            contentDescription = "Wipe this chat from this phone. Your friend's phone is not changed."
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(16) })
    }
    private fun field(hint: String, secret: Boolean) = EditText(activity).apply {
        this.hint = hint; textSize = 16f; typeface = UiStyle.body; setSingleLine(true)
        background = UiStyle.shape(Color.WHITE, dp(UiStyle.BUTTON_RADIUS).toFloat(), UiStyle.line, dp(1))
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        if (secret) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        setPadding(dp(14), dp(13), dp(14), dp(13)); setTextColor(ink); setHintTextColor(muted)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        isSaveEnabled = false; filterTouchesWhenObscured = true
    }
    fun renderInbox(friends: List<Friend>, receiving: Boolean, busy: Boolean) {
        addButton.isEnabled = true; addButton.alpha = 1f
        inboxStatus.text = if (busy) "Checking for messages…" else if (receiving) "Background receiving is on · Change" else "Messages arrive while the app is open · Change"
        if (friends == shownFriends) return
        shownFriends = friends.toList()
        inboxRows.removeAllViews()
        if (friends.isEmpty()) {
            inboxRows.addView(label("A quieter way to talk.", 23f).apply { setPadding(0, dp(48), 0, dp(16)) })
            inboxRows.addView(label("Add a friend, pair your devices, then just write.", 16f, true))
        }
        for (friend in friends) {
            val title = "${if (friend.unread) "● " else ""}${friend.name}"
            val rowText = android.text.SpannableString("$title\n${friend.detail}").apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, title.length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.82f), title.length, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(muted), title.length, length, 0)
            }
            inboxRows.addView(button("") { actions.openFriend(friend.id, friend.paired) }.apply {
                text = rowText; typeface = UiStyle.body; setLineSpacing(dp(4).toFloat(), 1f)
                val radius = dp(UiStyle.CARD_RADIUS).toFloat()
                background = UiStyle.pressable(UiStyle.shape(Color.WHITE, radius, if (friend.unread) ink else UiStyle.line, dp(if (friend.unread) 2 else 1)), radius)
                setPadding(dp(18), dp(14), dp(18), dp(14))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 16f; minHeight = dp(76)
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
    /** [remote]: honest peer-wipe status (unreviewed feature), or null when none was involved. */
    fun renderRetired(name: String, status: VaultRetirement.Status, busy: Boolean, remote: String? = null) {
        retiredName.text = name
        retiredRemote.text = remote ?: NO_REMOTE_WIPE
        retiredStatus.text = when (status.state) {
            VaultRetirement.LocalState.COMPLETE -> "Local cleanup completed"
            VaultRetirement.LocalState.INCOMPLETE -> "Local cleanup incomplete"
            else -> "Local cleanup status unavailable"
        }
        dismissRetiredButton.visibility = if (status.state == VaultRetirement.LocalState.COMPLETE) View.VISIBLE else View.GONE
        dismissRetiredButton.isEnabled = !busy && status.state == VaultRetirement.LocalState.COMPLETE
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
        inboxRows.addView(label("Saved data could not be opened. Open recovery tools to inspect the problem.", 15f, true).apply { setPadding(0, 0, 0, dp(20)) })
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
