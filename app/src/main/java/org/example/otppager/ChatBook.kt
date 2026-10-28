package org.example.otppager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Routes the default contact index to the current lineage: legacy `chat-index`, or an ACTIVE epoch's own index. */
internal object EpochRouting {
    fun index(context: Context): String = EpochState.activeIfStaged(context.noBackupFilesDir)?.index ?: "chat-index"
    fun initialRoot(context: Context): String = EpochState.activeIfStaged(context.noBackupFilesDir)?.initialRoot ?: "station-v2"
}

/** Encrypted contact index. Existing station-v2 data stays in place; keys are never copied. */
class ChatBook(context: Context, indexNamespace: String = EpochRouting.index(context), legacyNamespace: String = EpochRouting.initialRoot(context)) {
    companion object { const val MAX_CHATS = 12 }
    private val context = context.applicationContext
    private val index = SecureStore(context, indexNamespace)
    private val claims = PairingClaims(SecureStore(context, "$indexNamespace-claims"))
    // Reload durable state for every operation: stale UI/receiver objects cannot overwrite a replacement.
    private val data: JSONObject get() = checkNotNull(index.load()) { "Contact index unavailable" }.also(::validate)
    private var roundRobin = 0
    data class Chat(val id: String, val name: String, val entryId: String) {
        constructor(id: String, name: String) : this(id, name, id)
    }
    data class Summary(val unread: Int = 0, val queued: Int = 0, val problem: Boolean = false, val draft: Boolean = false, val paired: Boolean = false, val eligible: Boolean = true, val retired: Boolean = false)
    private val summaries = java.util.concurrent.ConcurrentHashMap<String, Summary>()
    init {
        synchronized(VaultLifecycle.lock) {
            val saved = index.load()
            if (saved == null) {
                // An ACTIVE epoch's index is created by activation only; a missing one is never recreated.
                check(EpochState.activeIfStaged(context.noBackupFilesDir) == null) { "Contact index unavailable" }
                // A lost index must not silently reopen a terminal legacy generation.
                VaultLifecycle.requireActive(context.noBackupFilesDir, legacyNamespace)
                claims.migrate(SecureStore(context, legacyNamespace))
                save(JSONObject().put("version", 3).put("active", legacyNamespace).put("chats", JSONArray()
                    .put(JSONObject().put("id", legacyNamespace).put("entryId", legacyNamespace).put("name", "Chat 1"))))
            } else {
                validate(saved)
                if (saved.getInt("version") == 2) {
                    val next = JSONObject(saved.toString()).put("version", 3)
                    val entries = next.getJSONArray("chats")
                    for (i in 0 until entries.length()) entries.getJSONObject(i).put("entryId", entries.getJSONObject(i).getString("id"))
                    save(next) // Only index metadata changes; vaults, identities and pad owners stay in place.
                }
            }
        }
        refreshSummaries()
    }
    private fun validate(value: JSONObject) {
        check(value.getInt("version") in 2..3) { "Unsupported contact index version" }
        val chats = value.getJSONArray("chats")
        check(chats.length() in 1..MAX_CHATS)
        val ids = mutableSetOf<String>(); val names = mutableSetOf<String>(); val entries = mutableSetOf<String>()
        for (i in 0 until chats.length()) {
            val chat = chats.getJSONObject(i)
            check(Regex("[a-z0-9-]{1,30}").matches(chat.getString("id")) && ids.add(chat.getString("id")))
            if (value.getInt("version") == 3) check(Regex("[a-z0-9-]{1,30}").matches(chat.getString("entryId")) && entries.add(chat.getString("entryId")))
            check(name(chat.getString("name")) == chat.getString("name") && names.add(chat.getString("name").lowercase(java.util.Locale.ROOT)))
        }
        check(value.getString("active") in ids)
    }
    private fun save(next: JSONObject) { validate(next); index.save(next) }
    private fun name(value: String) = value.trim().also {
        require(it.length in 1..32 && it.none { c -> c.isISOControl() || Character.getType(c) == Character.FORMAT.toInt() }) { "Use a chat name of 1–32 characters without control characters" }
    }
    fun chats(): List<Chat> = synchronized(VaultLifecycle.lock) { data.getJSONArray("chats").let { list -> (0 until list.length()).map { Chat(list.getJSONObject(it).getString("id"), list.getJSONObject(it).getString("name"), list.getJSONObject(it).optString("entryId", list.getJSONObject(it).getString("id"))) } } }
    fun active(): Chat = synchronized(VaultLifecycle.lock) { chats().first { it.id == data.getString("active") } }
    fun select(id: String) = synchronized(VaultLifecycle.lock) { require(chats().any { it.id == id }); save(JSONObject(data.toString()).put("active", id)) }
    fun rename(value: String) = synchronized(VaultLifecycle.lock) {
        val clean = name(value)
        require(chats().none { it.id != active().id && it.name.equals(clean, ignoreCase = true) }) { "Another chat already uses that name" }
        val next = JSONObject(data.toString()); val entries = next.getJSONArray("chats")
        for (i in 0 until entries.length()) if (entries.getJSONObject(i).getString("id") == active().id) entries.getJSONObject(i).put("name", clean)
        save(next)
    }
    fun add(value: String, copyServerFrom: String? = active().id): Chat = synchronized(VaultLifecycle.lock) {
        check(chats().size < MAX_CHATS) { "This version supports up to $MAX_CHATS separate chats" }
        val clean = name(value)
        require(chats().none { it.name.equals(clean, ignoreCase = true) }) { "Another chat already uses that name" }
        val source = copyServerFrom?.let { id -> require(chats().any { it.id == id }); SecureStore(context, id).load() }
        val chat = Chat(freshNamespace(), clean)
        val next = JSONObject(data.toString()); next.getJSONArray("chats").put(JSONObject().put("id", chat.id).put("entryId", chat.entryId).put("name", chat.name))
        save(next) // A failure afterward leaves a recoverable empty chat, never a second owner of old key material.
        if (source != null) engine(chat.id).configure(source.getString("endpoint"), source.getString("token"), Protocol.randomSecret(), "A")
        refresh(chat.id); chat
    }
    fun engine(id: String = active().id, relay: RelayTransport = RelayClient): StationEngine = synchronized(VaultLifecycle.lock) {
        require(chats().any { it.id == id }) { "Unknown chat" }
        StationEngine(SecureStore(context, id), relay, claims)
    }
    private fun freshNamespace(): String {
        val directory = context.noBackupFilesDir
        val files = checkNotNull(directory.listFiles()) { "Contact storage unavailable" }.map { it.name }
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = keys.aliases().toList()
        val root = generateSequence { "chat-" + UUID.randomUUID().toString().replace("-", "").take(20) }.first { candidate ->
            chats().none { it.id == candidate || it.entryId == candidate } &&
                files.none { it.startsWith("$candidate.") || it.startsWith("$candidate-") } &&
                aliases.none { it == "otp-pager-$candidate" || it.startsWith("otp-pager-$candidate-") || it.startsWith("otp-pager-$candidate~") }
        }
        // In an ACTIVE epoch the root is listed durably before its first key or file exists.
        EpochState.activeIfStaged(directory)?.let { EpochManifests.appendRoot(directory, it.epoch, root) }
        return root
    }
    /** A recovery action can only retry a known terminal generation, never retire an active contact. */
    internal fun recoverRetired(id: String): VaultRetirement.Result {
        synchronized(VaultLifecycle.lock) {
            require(chats().any { it.id == id }) { "Unknown chat" }
            check(VaultLifecycle.isContact(id) && VaultLifecycle.isRetired(context.noBackupFilesDir, id)) { "Pairing is not retired" }
        }
        return VaultRetirement.retireContact(context, id)
    }
    /** Explicit local-only replacement. Never copies credentials, pads, history or peer identity. */
    internal fun replaceRetired(id: String): Chat {
        // Recovery may dispatch cancellation; never do this while holding an outer lifecycle lock.
        val result = recoverRetired(id)
        check(result.terminalDurable && result.completed) { "Local destruction incomplete; retry recovery before fresh pairing" }
        return synchronized(VaultLifecycle.lock) {
            val next = JSONObject(data.toString()); val entries = next.getJSONArray("chats")
            val position = (0 until entries.length()).firstOrNull { entries.getJSONObject(it).getString("id") == id }
                ?: error("Contact generation changed; reload chats")
            val entry = entries.getJSONObject(position)
            val replacement = Chat(freshNamespace(), entry.getString("name"), entry.getString("entryId"))
            entry.put("id", replacement.id)
            if (next.getString("active") == id) next.put("active", replacement.id)
            save(next) // Atomic index commit is the only publication boundary; an interrupted retry remains safe.
            summaries.remove(id); refresh(replacement.id)
            replacement
        }
    }
    /**
     * User-initiated local-only wipe of one listed conversation generation. Other contacts,
     * pad ledgers, reuse claims and the contact entry/name stay in place; the peer is not
     * contacted. Repeating it on an already retired generation retries cleanup idempotently.
     */
    internal fun wipeLocal(id: String): VaultRetirement.Result {
        synchronized(VaultLifecycle.lock) {
            require(VaultLifecycle.isContact(id)) { "Not a conversation" }
            require(chats().any { it.id == id }) { "Unknown chat" }
        }
        // Retirement dispatches cancellation; never call it while holding an outer lifecycle lock.
        val result = VaultRetirement.retireContact(context, id)
        refresh(id)
        return result
    }
    internal fun ensureClaimsRegistry() = claims.ensureRegistry()
    fun forgetActive() = synchronized(VaultLifecycle.lock) { ChatDraft(SecureStore(context, active().id).sibling("draft")).clear(); SecureStore(context, active().id).forget(); refresh() }
    fun summary(id: String): Summary {
        if (VaultLifecycle.isRetired(context.noBackupFilesDir, id)) return Summary(eligible = false, retired = true)
        return summaries[id] ?: Summary()
    }
    fun unreadElsewhere() = chats().filter { it.id != active().id }.sumOf { summary(it.id).unread }
    fun refresh(id: String = active().id) {
        try {
            if (VaultLifecycle.isRetired(context.noBackupFilesDir, id)) {
                summaries[id] = Summary(eligible = false, retired = true)
                return
            }
            val s = SecureStore(context, id).load()
            val now = System.currentTimeMillis()
            fun live(entry: JSONObject) = now - entry.getLong("time") < 86400000
            val inbox = s?.getJSONArray("inbox"); val out = s?.getJSONArray("outbox")
            val draft = try {
                if (s == null) false else {
                    val master = Protocol.master(s.getString("secret"))
                    val room = try { Protocol.room(master) } finally { master.fill(0) }
                    ChatDraft(SecureStore(context, id).sibling("draft")).read(room, s.optString("lastSentDraft")) != null
                }
            } catch (_: Exception) { false } // A damaged draft never blocks receiving this chat.
            summaries[id] = Summary(
                (if (inbox == null) 0 else (0 until inbox.length()).count { live(inbox.getJSONObject(it)) && !inbox.getJSONObject(it).optBoolean("read") }) + (s?.optJSONArray("history")?.let { history -> (0 until history.length()).count { live(history.getJSONObject(it)) && history.getJSONObject(it).optBoolean("unread") } } ?: 0),
                if (out == null) 0 else (0 until out.length()).count { live(out.getJSONObject(it)) && out.getJSONObject(it).getString("status") == "queued" }, draft = draft,
                paired = s != null && StationEngine.hasPad(s) && s.has("peerDevice") && s.optBoolean("padVerified") && s.optBoolean("peerReady") && s.optBoolean("cableComplete", true) && !s.optBoolean("renewalPaused") && !s.optBoolean("relayRevoked"),
                eligible = s == null || (!StationEngine.hasPad(s) && s.getLong("counter") == 0L && s.getLong("high") == 0L && s.getJSONArray("inbox").length() == 0 && s.getJSONArray("outbox").length() == 0))
        } catch (_: Exception) { summaries[id] = Summary(problem = true) }
    }
    private fun refreshSummaries() { chats().forEach { refresh(it.id) } }
    /** One inactive chat per foreground poll bounds battery/network cost. An offline chat does not block others. */
    fun syncAnother(relay: RelayTransport = RelayClient) {
        val others = chats().filter { it.id != active().id && !VaultLifecycle.isRetired(context.noBackupFilesDir, it.id) }
        if (others.isEmpty()) return
        val chat = others[roundRobin.mod(others.size)]; roundRobin = (roundRobin + 1).mod(others.size)
        try {
            val peer = engine(chat.id, relay)
            if (peer.state != null && peer.view().pairingComplete) peer.sync()
            refresh(chat.id)
        } catch (_: Exception) { summaries[chat.id] = summary(chat.id).copy(problem = true) }
    }
}

/** A pad or mailbox secret can never be assigned to two local chats. Retained on Forget pairing. */
class PairingClaims(private val store: SecureStore) {
    companion object { private val lock = VaultLifecycle.lock }
    private var failed = false
    private fun claim(kind: String, key: String, owner: String, existing: Boolean) = synchronized(lock) {
        check(!failed) { "Pairing registry write failed; restart the app" }
        val data = store.load() ?: JSONObject().put("version", 2).put("pads", JSONObject()).put("rooms", JSONObject())
        val entries = data.getJSONObject(kind)
        if (entries.has(key)) {
            require(existing && entries.getString(key) == owner) { "This pairing material was already used. Create a fresh pairing for each friend." }
        } else {
            // In an ACTIVE epoch every earlier wipe's sealed cohort is checked before the delta grows.
            store.activeEpoch?.let { epoch ->
                val retained = RetainedGuardReader.activeExclusions(store.directory, epoch)
                require(key !in (if (kind == "pads") retained.pads else retained.rooms)) {
                    "This pairing material was already used. Create a fresh pairing for each friend."
                }
            }
            entries.put(key, owner)
            try { store.save(data) } catch (e: Exception) { failed = true; throw e }
        }
    }
    fun room(secret: String, owner: String, existing: Boolean) {
        val master = Protocol.master(secret)
        try { claim("rooms", Protocol.room(master), owner, existing) } finally { master.fill(0) }
    }
    /** Global wipe evidence needs an authenticated registry; create the empty one if nothing was ever claimed. */
    internal fun ensureRegistry() = synchronized(lock) {
        check(!failed) { "Pairing registry write failed; restart the app" }
        if (store.load() == null) store.save(JSONObject().put("version", 2).put("pads", JSONObject()).put("rooms", JSONObject()))
    }
    fun pad(id: String, owner: String) { require(Regex("[a-f0-9]{32}").matches(id)); claim("pads", id, owner, existing = true) }
    fun migrate(profile: SecureStore) {
        profile.load()?.let { room(it.getString("secret"), profile.namespace, existing = true) }
        profile.sibling("pads").load()?.getJSONObject("pads")?.keys()?.forEach { pad(it, profile.namespace) }
    }
}
