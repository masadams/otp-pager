package org.example.otppager

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*

/**
 * Group chats (docs/GROUP_CHATS.md). A group is a label on ordinary pairwise chats; every
 * message is sent as one separately pad-encrypted copy per member. All storage and network
 * work runs on the station worker; this screen only renders snapshots.
 */
class GroupActivity : Activity() {
    private val worker = StationRuntime.worker
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var open: String? = null
    private var active = false
    private var generation = 0
    /** Text not yet queued for everyone, kept for the composer and Retry. Memory only, cleared on leave. */
    private class Pending(val group: String, val text: String, val message: String?, val missed: List<ChatBook.Chat>)
    private var pending: Pending? = null

    private fun label(value: String, size: Float = 16f, muted: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (muted) Color.DKGRAY else Color.BLACK); typeface = Typeface.MONOSPACE; setPadding(0, 10, 0, 10)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; isAllCaps = false; typeface = Typeface.MONOSPACE; setOnClickListener { action() } }
    private fun note(value: String) = runOnUiThread { if (active) status.text = value }
    /** Worker task whose result is dropped if the screen moved on meanwhile. */
    private fun task(network: Boolean = false, work: (ChatBook) -> String?, then: (() -> Unit)? = null) {
        val token = ++generation
        if (network) note("Checking…")
        worker.execute {
            val result = try { work(ChatBook(this)) } catch (e: Exception) { e.message ?: "Something went wrong" }
            runOnUiThread { if (active && token == generation) { result?.let { status.text = it }; then?.invoke() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.protectPrivateContent()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 24); setBackgroundColor(Color.WHITE) }
        status = label("", 13f, true)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        setContentView(root)
        open = intent.getStringExtra("group")
    }
    override fun onResume() { super.onResume(); if (!AppLock.gate(this)) return; active = true; render() }
    override fun onStop() { active = false; generation++; body.removeAllViews(); super.onStop() }
    override fun onDestroy() { pending = null; super.onDestroy() }
    @Deprecated("Back returns from a thread to the group list")
    override fun onBackPressed() { if (open != null) { open = null; render() } else super.onBackPressed() }

    private fun render() { if (open == null) renderList() else renderThread(open!!) }

    private fun renderList() {
        var groups: List<ChatBook.GroupThread> = emptyList()
        task(work = { groups = it.groups(); null }) {
            body.removeAllViews()
            body.addView(label("Groups", 25f))
            body.addView(label("Each message goes separately to every member, encrypted with that friend's own message keys. " +
                "Everyone in a group should be paired with each other.", 13f, true))
            if (groups.isEmpty()) body.addView(label("No groups yet.", 15f, true))
            for (g in groups) body.addView(button("${g.name}\n${g.chats.joinToString { it.name }}") { open = g.id; render() })
            body.addView(button("New group") { newGroup() })
            body.addView(button("Close") { finish() })
        }
    }

    private fun newGroup() {
        var choices: List<ChatBook.Chat> = emptyList()
        task(work = { book -> choices = book.chats().filter { book.summary(it.id).paired }; null }) {
            if (choices.size < 2) { status.text = "Pair with at least two friends first"; return@task }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 10, 40, 0) }
            val name = EditText(this).apply { hint = "Group name"; inputType = InputType.TYPE_CLASS_TEXT; filters = arrayOf(android.text.InputFilter.LengthFilter(32)) }
            box.addView(name)
            val picked = BooleanArray(choices.size)
            val list = choices.mapIndexed { i, chat -> CheckBox(this).apply { text = chat.name; setOnCheckedChangeListener { _, on -> picked[i] = on } } }
            list.forEach(box::addView)
            box.addView(label("Creating the group uses one message key with each friend you choose.", 12f, true))
            AlertDialog.Builder(this).setTitle("New group").setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Create") { _, _ ->
                    val ids = choices.filterIndexed { i, _ -> picked[i] }.map { it.id }
                    val title = name.text.toString().trim()
                    if (title.isEmpty() || ids.size < 2) { status.text = "Name the group and choose at least two friends"; return@setPositiveButton }
                    var created: String? = null
                    task(work = { created = it.createGroup(title, ids).id; "Group created · sync to send the invitations" }) { open = created; render() }
                }.create().also { it.window?.protectPrivateContent(); it.show() }
        }
    }

    private fun renderThread(id: String) {
        var group: ChatBook.GroupThread? = null; var lines: List<ChatBook.GroupLine> = emptyList(); var others: List<ChatBook.Chat> = emptyList()
        task(work = { book -> group = book.groups().firstOrNull { it.id == id }; lines = book.groupLines(id)
            others = book.chats().filter { c -> book.summary(c.id).paired && group?.chats?.none { it.id == c.id } == true }; null }) {
            val g = group ?: run { open = null; renderList(); return@task }
            body.removeAllViews()
            body.addView(label(g.name, 25f))
            body.addView(label("Members: ${g.members.joinToString()}", 13f, true))
            body.addView(label("Linked on this phone: ${g.chats.joinToString { it.name }}", 13f, true))
            if (lines.isEmpty()) body.addView(label("No messages yet.", 15f, true))
            for (line in lines.takeLast(100)) body.addView(label((if (line.unread) "● " else "") + "${line.from}: ${line.text}" +
                if (!line.incoming) "\n· ${line.status}" else "", 15f).apply { if (!line.incoming) setPadding(60, 10, 0, 10) })
            val composer = EditText(this).apply { hint = "Message to ${g.name}"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
            val waiting = pending?.takeIf { it.group == id }
            if (waiting != null && waiting.missed.isEmpty()) composer.setText(waiting.text) // Nothing queued yet: keep the text.
            body.addView(composer)
            /** Queue (or retry for [missed] only), then check each chat; report what actually happened. */
            fun send(text: String, message: String?, missed: List<ChatBook.Chat>?) {
                pending = Pending(id, text, message, missed.orEmpty())
                var result: ChatBook.GroupSend? = null
                task(network = true, work = { book ->
                    val sent = book.queueGroup(id, text, message, missed?.map { it.id }?.toSet()); result = sent
                    val unreachable = g.chats.filter { c -> sent.missed.none { it.id == c.id } }
                        .mapNotNull { chat -> try { book.engine(chat.id).sync(); null } catch (_: Exception) { chat.name } }
                    when {
                        sent.missed.isNotEmpty() -> "Not queued for ${sent.missed.joinToString { it.name }} (pair again or renew keys), then tap Retry. Everyone else has it queued."
                        unreachable.isNotEmpty() -> "Queued for everyone. Couldn't reach the server for ${unreachable.joinToString()} yet; it retries automatically."
                        else -> "Sent to the server for everyone · delivery shows below"
                    }
                }) {
                    // Only a fully queued message leaves the composer; otherwise keep what's needed to retry.
                    val r = result
                    pending = when { r == null -> pending; r.missed.isEmpty() -> null; else -> Pending(id, text, r.message, r.missed) }
                    render()
                }
            }
            body.addView(button("Send to everyone") {
                val text = composer.text.toString().trim(); if (text.isEmpty()) return@button
                send(text, null, null)
            })
            if (waiting != null && waiting.missed.isNotEmpty()) body.addView(button("Retry for ${waiting.missed.joinToString { it.name }}") {
                send(waiting.text, waiting.message, waiting.missed)
            })
            body.addView(button("Check now") {
                task(network = true, work = { book -> g.chats.forEach { chat -> try { book.engine(chat.id).sync() } catch (_: Exception) {} }; "Checked" }) { render() }
            })
            if (others.isNotEmpty()) body.addView(button("Link a member to a chat") {
                AlertDialog.Builder(this).setTitle("Which chat is a member of ${g.name}?")
                    .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                        task(work = { it.linkToGroup(id, others[which].id); "${others[which].name} linked" }) { render() }
                    }.setNegativeButton("Cancel", null).create().also { it.window?.protectPrivateContent(); it.show() }
            })
            body.addView(button("Leave group on this phone") {
                AlertDialog.Builder(this).setTitle("Leave ${g.name}?").setMessage("This phone stops showing and sending this group. Each friend's pairwise chat and its history stay. Friends are not told.")
                    .setPositiveButton("Leave") { _, _ -> task(work = { book -> g.chats.forEach { book.engine(it.id).leaveGroup(id) }; "Left the group" }) { open = null; render() } }
                    .setNegativeButton("Cancel", null).create().also { it.window?.protectPrivateContent(); it.show() }
            })
            body.addView(button("All groups") { open = null; render() })
        }
    }
}
