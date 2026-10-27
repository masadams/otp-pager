package org.example.otppager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Authenticated terminal evidence only. No unfreeze, enrollment, or command authority. */
internal object RetirementEvidence {
    const val FILE = "retirement-evidence-v1.bin"
    enum class Presence { NONE, PRESENT, UNAVAILABLE }
    private val absentChecked = mutableSetOf<String>()
    private val captureClosed = mutableSetOf<String>()
    private val tickets = mutableMapOf<String, Ticket>()
    class Ticket internal constructor(internal val context: Context, val generation: String) {
        internal val directory = context.noBackupFilesDir.absoluteFile
    }
    fun marker(directory: File) = File(directory, FILE)
    internal fun alias(directory: File): String = "otp-pager-terminal-" + PadProtocol.hex(
        MessageDigest.getInstance("SHA-256").digest(directory.absolutePath.toByteArray(Charsets.UTF_8))).take(32)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    /** Fresh reads at startup/recovery; key-only interruption is terminal, not a new installation. */
    fun probe(directory: File): Presence = synchronized(VaultLifecycle.lock) {
        try {
            val present = VaultLifecycle.variants(marker(directory)).any(::exists) || keys().containsAlias(alias(directory))
            if (present) absentChecked.remove(directory.absolutePath) else absentChecked.add(directory.absolutePath)
            if (present) Presence.PRESENT else Presence.NONE
        } catch (_: Exception) { Presence.UNAVAILABLE }
    }
    /** Avoid a Keystore round trip on every normal store operation. Creation invalidates this cache first. */
    fun presence(directory: File): Presence = synchronized(VaultLifecycle.lock) {
        when {
            VaultLifecycle.variants(marker(directory)).any(::exists) -> Presence.PRESENT
            directory.absolutePath in absentChecked -> Presence.NONE
            else -> probe(directory)
        }
    }
    fun beginFresh(context: Context): Ticket = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir.absoluteFile
        check(GlobalRetirement.isFrozen(directory)) { "Freeze must precede evidence creation" }
        check(directory.path !in captureClosed) { "Cleanup has already started; capture window is closed" }
        check(VaultLifecycle.variants(GlobalRetirement.marker(directory)).none(::exists)) { "Legacy intent cannot gain retrospective provenance" }
        check(probe(directory) == Presence.NONE) { "Existing evidence/key cannot be replaced" }
        absentChecked.remove(directory.path)
        val generation = PadProtocol.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val ticket = Ticket(context.applicationContext, generation)
        // This is the only key-creation path. A failed write leaves the key as a terminal latch.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias(directory), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build())
        generator.generateKey()
        write(directory, RetirementEvidenceCodec.record(generation, RetirementEvidenceCodec.Phase.INTENT, emptySet(), emptyList()))
        tickets[directory.path] = ticket
        ticket
    }
    /** Cleanup wins any race with a capture attempt; a future retry cannot mint a late ticket. */
    fun retireCaptureWindow(directory: File) = synchronized(VaultLifecycle.lock) {
        captureClosed.add(directory.absolutePath); tickets.remove(directory.absolutePath); Unit
    }
    fun close(ticket: Ticket) = synchronized(VaultLifecycle.lock) {
        if (tickets[ticket.directory.path] === ticket) tickets.remove(ticket.directory.path)
        Unit
    }
    /** A single live ticket minted before cleanup; startup has no API to reissue one. */
    fun seal(ticket: Ticket, snapshot: RetainedGuardInventory.Snapshot) = synchronized(VaultLifecycle.lock) {
        check(tickets[ticket.directory.path] === ticket) { "No initial capture authority" }
        tickets.remove(ticket.directory.path) // Consume before I/O; failed attempts cannot be retried after cleanup.
        val current = read(ticket.directory)
        check(current.phase == RetirementEvidenceCodec.Phase.INTENT && current.generation == ticket.generation)
        RetainedGuardInventory.verify(ticket.context, snapshot)
        write(ticket.directory, RetirementEvidenceCodec.record(current.generation, RetirementEvidenceCodec.Phase.SEALED,
            snapshot.roots, snapshot.guards))
    }
    fun read(directory: File): RetirementEvidenceCodec.Record = synchronized(VaultLifecycle.lock) {
        check(VaultLifecycle.variants(marker(directory)).drop(1).none(::exists)) { "Ambiguous terminal record" }
        val base = marker(directory)
        check(Files.isRegularFile(base.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Terminal evidence unavailable" }
        val bytes = readBounded(base)
        check(bytes.size >= 61)
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        val signature = bytes.copyOfRange(bytes.size - 32, bytes.size)
        check(MessageDigest.isEqual(mac(directory, payload), signature)) { "Terminal evidence authentication failed" }
        RetirementEvidenceCodec.decode(payload)
    }
    /** Revalidate current guards and scope, including a previously CLEANED record; never trust phase alone. */
    fun verifyFrozen(context: Context): RetirementEvidenceCodec.Record = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir.absoluteFile
        check(GlobalRetirement.isFrozen(directory))
        val record = read(directory)
        check(record.phase != RetirementEvidenceCodec.Phase.INTENT) { "Pre-cleanup provenance was not sealed" }
        RetainedGuardInventory.verify(context, RetainedGuardInventory.Snapshot(record.roots, record.guards))
        record
    }
    /** Fresh evidence check for a later enrollment transaction; this still grants no activation authority. */
    fun verifyCompleted(context: Context): RetirementEvidenceCodec.Record = synchronized(VaultLifecycle.lock) {
        val record = verifyFrozen(context)
        check(record.phase == RetirementEvidenceCodec.Phase.CLEANED)
        check(GlobalRetirement.inspect(context).state == GlobalRetirement.LocalState.COMPLETE)
        record
    }
    fun recordCleanup(context: Context, result: GlobalRetirementMachine.Result) = synchronized(VaultLifecycle.lock) {
        if (probe(context.noBackupFilesDir) != Presence.PRESENT) return@synchronized
        val current = read(context.noBackupFilesDir)
        if (current.phase == RetirementEvidenceCodec.Phase.INTENT) return@synchronized // Never invent capture on recovery.
        check(result.completed && result.roots == current.roots) { "Cleanup scope did not match sealed provenance" }
        verifyFrozen(context)
        check(GlobalRetirement.inspect(context).state == GlobalRetirement.LocalState.COMPLETE)
        if (current.phase != RetirementEvidenceCodec.Phase.CLEANED) write(context.noBackupFilesDir,
            RetirementEvidenceCodec.record(current.generation, RetirementEvidenceCodec.Phase.CLEANED, current.roots, current.guards))
    }
    /** Hash of the exact authenticated terminal envelope, for confirmation/staging binding. */
    fun commitment(directory: File): String = synchronized(VaultLifecycle.lock) {
        read(directory)
        PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(readBounded(marker(directory))))
    }
    private fun mac(directory: File, payload: ByteArray): ByteArray {
        val key = keys().getKey(alias(directory), null) as? SecretKey ?: error("Terminal authentication key missing")
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(payload) }
    }
    private fun write(directory: File, record: RetirementEvidenceCodec.Record) {
        val payload = RetirementEvidenceCodec.encode(record)
        val bytes = payload + mac(directory, payload)
        check(bytes.size <= RetirementEvidenceCodec.MAX_BYTES)
        val atomic = AtomicFile(marker(directory)); val stream = atomic.startWrite(); var finished = false
        try {
            stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream); finished = true
            check(VaultLifecycle.variants(marker(directory)).drop(1).none(::exists))
            check(readBounded(marker(directory)).contentEquals(bytes))
            check(read(directory) == record)
            VaultLifecycle.syncDirectory(directory)
        } catch (failure: Exception) {
            if (!finished) atomic.failWrite(stream) // Preserve finalized terminal evidence after a sync/readback error.
            throw failure
        }
    }
    private fun readBounded(file: File): ByteArray {
        Files.newByteChannel(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            check(channel.size() in 61..RetirementEvidenceCodec.MAX_BYTES.toLong())
            val out = java.io.ByteArrayOutputStream(); val buffer = java.nio.ByteBuffer.allocate(8192)
            while (true) {
                val count = channel.read(buffer); if (count == -1) break
                check(count > 0 && out.size() <= RetirementEvidenceCodec.MAX_BYTES - count)
                out.write(buffer.array(), 0, count); buffer.clear()
            }
            return out.toByteArray()
        }
    }
}
