package org.example.otppager

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * UNREVIEWED best-effort peer wipe (docs/PEER_WIPE_PROTOCOL.md), off unless the owner turns on
 * the "unreviewed peer wipe" setting on BOTH phones.
 *
 * Initiator: [prepare] seals one WIPE frame and the hashes of the three acknowledgements the
 * friend's phone could send, BEFORE local destruction, into a small record outside the vault.
 * No control key, pad, message or identity key is kept, so a stolen phone cannot mint another
 * command. [pump] posts the frame to the control mailbox and matches acknowledgements.
 *
 * Recipient: the station engine verifies a WIPE for its own pairing generation, calls
 * [acknowledge] to precompute ACKs, then retires that chat even if ACK persistence fails.
 * [finish] queues WIPED or INCOMPLETE; transport runs only after retirement.
 *
 * Records are plain JSON in `peerwipe/`: routing metadata and sealed frames are the documented
 * retained-metadata exception; they authorize nothing beyond this one pairing's mailbox.
 */
internal object PeerWipe {
    private const val PREFS = "peer-wipe"
    private const val REPOST_MS = 12 * 3600_000L
    const val EXPIRY_MS = 90 * 86_400_000L
    private const val ACK_KEEP_MS = 7 * 86_400_000L
    private val b64e = Base64.getEncoder(); private val b64d = Base64.getDecoder()
    enum class Status { QUEUED, ACCEPTED, RECEIVED, WIPED, INCOMPLETE, EXPIRED, UNAVAILABLE }

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("unreviewed", false)
    fun setEnabled(context: Context, on: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("unreviewed", on).apply() }

    private fun directory(context: Context) = File(context.noBackupFilesDir, "peerwipe")
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    /** Relay capability for the control mailbox, domain-separated from the chat capability. */
    fun capability(credential: ByteArray): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest("OTP Pager / control capability v1".toByteArray(Charsets.US_ASCII) + credential))
    private fun own(role: String) = if (role == "A") ControlProtocol.Direction.A_TO_B else ControlProtocol.Direction.B_TO_A

    @Synchronized private fun write(file: File, record: JSONObject) {
        val dir = checkNotNull(file.parentFile); check(dir.isDirectory || dir.mkdirs()) { "Peer wipe storage unavailable" }
        val temporary = File(dir, file.name + ".new")
        java.io.FileOutputStream(temporary).use { it.write(record.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temporary.renameTo(file)) { "Peer wipe record rename failed" }
    }
    private fun read(file: File): JSONObject? = try { JSONObject(file.readText(Charsets.UTF_8)).takeIf { it.getInt("version") == 1 } } catch (_: Exception) { null }
    private fun records(context: Context, kind: String) = directory(context).listFiles()?.filter { it.name.startsWith("$kind-") && it.name.endsWith(".json") }.orEmpty()

    /** Mailbox routing shared by both roles. Uses the claimed capability when the relay supports claims. */
    private fun route(state: JSONObject): JSONObject {
        val c = state.getJSONObject("control")
        val credential = hex(c.getString("credential"))
        return try {
            JSONObject().put("endpoint", state.getString("endpoint")).put("room", ControlProtocol.mailbox(credential))
                .put("credential", if (c.optBoolean("scoped")) capability(credential) else state.getString("token"))
        } finally { credential.fill(0) }
    }

    /**
     * Initiator, before destroying [root]: returns false when this chat cannot ask its friend
     * (setting off, no control material, or the record could not be written). Never throws.
     */
    fun prepare(context: Context, root: String, state: JSONObject?): Boolean = try {
        val c = state?.optJSONObject("control")
        if (!enabled(context) || state == null || c == null) false
        else {
            val direction = own(state.getString("role"))
            val generation = hex(c.getString("generation")); val ctl = hex(c.getString("ctlOut")); val ack = hex(c.getString("ackIn"))
            val request = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            try {
                val frame = ControlProtocol.wipe(ctl, direction, generation, request, System.currentTimeMillis())
                val acks = JSONObject()
                for (outcome in ControlProtocol.Outcome.entries)
                    acks.put(PadProtocol.hex(ControlProtocol.sha256(ControlProtocol.ack(ack, direction.reverse, generation, outcome))), outcome.name)
                val record = route(state).put("version", 1).put("kind", "out").put("root", root)
                    .put("frame", b64e.encodeToString(frame)).put("acks", acks).put("created", System.currentTimeMillis())
                    .put("status", Status.QUEUED.name).put("lastPost", 0L).put("cursor", 0L).put("epoch", "")
                write(File(directory(context), "out-${PadProtocol.hex(request)}.json"), record)
                true
            } finally { ctl.fill(0); ack.fill(0) }
        }
    } catch (_: Exception) { false }

    /** Recipient: precompute final ACKs while the key exists. No network activity. */
    fun acknowledge(context: Context, root: String, state: JSONObject) {
        val c = state.getJSONObject("control")
        val direction = own(state.getString("role")); val generation = hex(c.getString("generation")); val key = hex(c.getString("ackOut"))
        try {
            fun frame(o: ControlProtocol.Outcome) = b64e.encodeToString(ControlProtocol.ack(key, direction, generation, o))
            val record = route(state).put("version", 1).put("kind", "ack").put("root", root)
                .put("wiped", frame(ControlProtocol.Outcome.WIPED)).put("incomplete", frame(ControlProtocol.Outcome.INCOMPLETE))
                .put("created", System.currentTimeMillis()).put("lastPost", 0L)
            write(File(directory(context), "ack-${c.getString("generation")}.json"), record)
            write(File(directory(context), "notice-$root.json"), JSONObject().put("version", 1).put("root", root).put("time", System.currentTimeMillis()))
        } finally { key.fill(0) }
    }
    /** Recipient, after local retirement: keep only the outcome that is true. */
    fun finish(context: Context, generation: String, completed: Boolean) {
        val file = File(directory(context), "ack-$generation.json")
        val record = read(file) ?: return
        record.put("final", record.getString(if (completed) "wiped" else "incomplete")); record.remove("wiped"); record.remove("incomplete")
        write(file, record)
    }
    /** Whether [root] was removed at its friend's request (shown once on the retired chat). */
    fun removedByFriend(context: Context, root: String) = File(directory(context), "notice-$root.json").isFile

    /** Newest initiator status for [root], for honest wording (§4 table). */
    fun status(context: Context, root: String): Status? = records(context, "out").mapNotNull(::read)
        .filter { it.optString("root") == root }.maxByOrNull { it.getLong("created") }?.let { Status.valueOf(it.getString("status")) }
    fun describe(status: Status?): String? = when (status) {
        null -> null
        Status.QUEUED -> "Request to your friend's phone is waiting to be sent"
        Status.ACCEPTED -> "Request delivered to the server, not yet seen by your friend's phone"
        Status.RECEIVED -> "Your friend's phone received the request"
        Status.WIPED -> "Your friend's phone reported the wipe complete"
        Status.INCOMPLETE -> "Your friend's phone reported the wipe did not finish"
        Status.EXPIRED -> "Request expired; outcome unknown"
        Status.UNAVAILABLE -> "Your friend's phone could not be asked to wipe"
    }
    fun forget(context: Context, root: String) { records(context, "out").filter { read(it)?.optString("root") == root }.forEach { it.delete() } }

    /**
     * Strict chats: before local destruction, queue deletion of this phone's relay share (always)
     * and the friend's share (only with peer wipe on). The record holds the share rooms and the
     * control capability, never a share. Without its share a strict vault can never be opened.
     */
    fun prepareShareDeletion(context: Context, root: String, stored: JSONObject): Boolean = try {
        val w = stored.optJSONObject("strict")
        if (w == null) false else {
            val rooms = org.json.JSONArray().put(w.getString("room"))
            if (enabled(context) && w.has("peerRoom")) rooms.put(w.getString("peerRoom"))
            write(File(directory(context), "del-$root.json"), JSONObject().put("version", 1).put("kind", "del").put("root", root)
                .put("endpoint", w.getString("endpoint")).put("capability", w.getString("capability")).put("rooms", rooms)
                .put("created", System.currentTimeMillis()))
            true
        }
    } catch (_: Exception) { false }
    /** Post pending frames and match acknowledgements. Best effort; every record is independent. */
    fun pump(context: Context, relay: RelayTransport = RelayClient, now: Long = System.currentTimeMillis()) {
        for (file in records(context, "del")) try { pumpDelete(file, relay, now) } catch (_: Exception) {}
        for (file in records(context, "out")) try { pumpOut(file, relay, now) } catch (_: Exception) {}
        for (file in records(context, "ack")) try { pumpAck(file, relay, now) } catch (_: Exception) {}
    }
    private fun pumpDelete(file: File, relay: RelayTransport, now: Long) {
        val record = read(file) ?: return
        if (now - record.getLong("created") > EXPIRY_MS) { file.delete(); return } // The relay deadline has long removed it.
        val rooms = record.getJSONArray("rooms")
        for (i in 0 until rooms.length()) relay.share(record.getString("endpoint"), rooms.getString(i), record.getString("capability"), "DELETE")
        file.delete()
    }
    private fun post(relay: RelayTransport, record: JSONObject, frame: String) {
        relay.request(record.getString("endpoint"), record.getString("room"), record.getString("credential"), "POST", JSONObject().put("packet", frame).toString())
    }
    private fun pumpOut(file: File, relay: RelayTransport, now: Long) {
        val record = read(file) ?: return
        var status = Status.valueOf(record.getString("status"))
        if (status in listOf(Status.WIPED, Status.INCOMPLETE, Status.EXPIRED)) return
        if (now - record.getLong("created") > EXPIRY_MS) { write(file, record.put("status", Status.EXPIRED.name)); return }
        if (status == Status.QUEUED || now - record.getLong("lastPost") > REPOST_MS) {
            post(relay, record, record.getString("frame"))
            record.put("lastPost", now); if (status == Status.QUEUED) status = Status.ACCEPTED
            write(file, record.put("status", status.name))
        }
        val acks = record.getJSONObject("acks")
        repeat(4) {
            val page = JSONObject(relay.request(record.getString("endpoint"), record.getString("room"), record.getString("credential"), "GET", cursor = record.getLong("cursor")))
            val epoch = page.optString("epoch")
            if (record.getString("epoch").isNotEmpty() && epoch != record.getString("epoch")) record.put("cursor", 0L)
            record.put("epoch", epoch)
            val messages = page.getJSONArray("messages")
            for (i in 0 until messages.length()) {
                val item = messages.getJSONObject(i)
                record.put("cursor", maxOf(record.getLong("cursor"), item.getLong("seq")))
                val bytes = try { b64d.decode(item.getString("packet")) } catch (_: Exception) { continue }
                val outcome = acks.optString(PadProtocol.hex(ControlProtocol.sha256(bytes))).takeIf { it.isNotEmpty() } ?: continue
                val matched = when (ControlProtocol.Outcome.valueOf(outcome)) {
                    ControlProtocol.Outcome.RECEIVED -> Status.RECEIVED
                    ControlProtocol.Outcome.WIPED -> Status.WIPED
                    ControlProtocol.Outcome.INCOMPLETE -> Status.INCOMPLETE
                }
                if (matched.ordinal > status.ordinal) status = matched
            }
            write(file, record.put("status", status.name))
            if (messages.length() < 50) return
        }
    }
    private fun pumpAck(file: File, relay: RelayTransport, now: Long) {
        val record = read(file) ?: return
        if (now - record.getLong("created") > ACK_KEEP_MS) { file.delete(); return }
        val final = record.optString("final").takeIf { it.isNotEmpty() } ?: return
        if (now - record.getLong("lastPost") > REPOST_MS) { post(relay, record, final); write(file, record.put("lastPost", now)) }
    }
}
