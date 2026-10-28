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

/**
 * Authenticated `OTPTERM2` terminal records for wiping an ACTIVE epoch (slice B3d).
 *
 * One file and one permanent HMAC key per wipe generation, so repeat wipes never overwrite
 * earlier evidence. A key without its file (interrupted INTENT) is a terminal latch: the
 * lineage reads it as unreadable and freezes. Like `OTPTERM1`, this grants no unfreeze or
 * peer authority; only [FreshEnrollment] can move past a CLEANED record.
 */
internal object EpochEvidence {
    private const val PREFIX = "epoch-terminal-"
    private val filePattern = Regex("epoch-terminal-([a-f0-9]{32})\\.bin")
    private val epochPattern = Regex("[a-f0-9]{32}")
    private val tickets = mutableMapOf<String, Ticket>()
    class Ticket internal constructor(internal val directory: File, val generation: String, val epoch: String)

    fun file(directory: File, generation: String) = File(directory, "$PREFIX$generation.bin")
    private fun keyPrefix(directory: File) = "otp-pager-epochterm-" + PadProtocol.hex(
        MessageDigest.getInstance("SHA-256").digest(directory.absolutePath.toByteArray(Charsets.UTF_8))).take(32) + "-"
    private fun alias(directory: File, generation: String) = keyPrefix(directory) + generation
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** Every name and key must pair up exactly; a key-only or file-only generation throws. */
    fun readAll(directory: File): List<EpochTerminalCodec.Record> = synchronized(VaultLifecycle.lock) {
        val names = checkNotNull(directory.list()) { "Lifecycle directory unavailable" }.filter { it.startsWith(PREFIX) }
        val files = names.map { checkNotNull(filePattern.matchEntire(it)) { "Unknown or variant terminal file" }.groupValues[1] }.toSet()
        val prefix = keyPrefix(directory)
        val aliases = keys().aliases().toList().filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).also { g -> check(epochPattern.matches(g)) { "Unknown terminal key" } } }.toSet()
        check(files == aliases && files.size <= EnrollmentStageCodec.MAX_ATTEMPTS) { "Terminal file/key mismatch" }
        files.sorted().map { read(directory, it) }
    }

    fun read(directory: File, generation: String): EpochTerminalCodec.Record = synchronized(VaultLifecycle.lock) {
        val bytes = bounded(file(directory, generation))
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        check(MessageDigest.isEqual(mac(directory, generation, payload), bytes.copyOfRange(payload.size, bytes.size))) {
            "Terminal evidence authentication failed"
        }
        EpochTerminalCodec.decode(payload).also { check(it.generation == generation) }
    }

    /** SHA-256 of the exact authenticated envelope, binding the next stage and manifest. */
    fun commitment(directory: File, generation: String): String = synchronized(VaultLifecycle.lock) {
        read(directory, generation)
        PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(bounded(file(directory, generation))))
    }

    /** Requires the in-process freeze latch and no earlier record for this epoch. */
    fun beginFresh(directory: File, epoch: String): Ticket = synchronized(VaultLifecycle.lock) {
        check(GlobalRetirement.isFrozen(directory)) { "Freeze must precede evidence creation" }
        check(readAll(directory).none { it.epoch == epoch }) { "This epoch already has terminal evidence" }
        val generation = PadProtocol.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        check(generation != epoch)
        EpochState.invalidate(directory)
        // Key creation is the durable latch; a failed file write leaves the epoch frozen.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias(directory, generation), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build())
        generator.generateKey()
        write(directory, EpochTerminalCodec.record(generation, epoch, RetirementEvidenceCodec.Phase.INTENT, emptySet(), emptyList()))
        Ticket(directory, generation, epoch).also { tickets[directory.path] = it }
    }

    /** Close the capture window before cleanup starts; a late ticket can never seal. */
    fun closeCapture(directory: File) = synchronized(VaultLifecycle.lock) { tickets.remove(directory.absolutePath); Unit }

    /**
     * Seal the epoch's claims delta and contact ledgers as an immutable cohort while every
     * writer is frozen. Only the live ticket from [beginFresh] may do this, once.
     */
    fun seal(context: Context, ticket: Ticket, manifest: ActiveManifestCodec.Record, priorRoots: Set<String>) = synchronized(VaultLifecycle.lock) {
        check(tickets[ticket.directory.path] === ticket && manifest.epoch == ticket.epoch) { "No capture authority" }
        tickets.remove(ticket.directory.path)
        val current = read(ticket.directory, ticket.generation)
        check(current.phase == RetirementEvidenceCodec.Phase.INTENT)
        val roots = (manifest.roots + EpochGuardInventory.discoverRoots(context, priorRoots)).toSortedSet()
        val guards = EpochGuardInventory.capture(context, manifest, roots)
        write(ticket.directory, EpochTerminalCodec.record(ticket.generation, ticket.epoch, RetirementEvidenceCodec.Phase.SEALED, roots, guards))
    }

    /** Upgrade SEALED to CLEANED only after an exact-scope, independently verified cleanup. */
    fun recordCleanup(context: Context, generation: String, result: GlobalRetirementMachine.Result) = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir.absoluteFile
        val current = read(directory, generation)
        if (current.phase == RetirementEvidenceCodec.Phase.INTENT) return@synchronized // Never invent a capture.
        check(result.completed && result.roots == current.roots) { "Cleanup scope did not match sealed provenance" }
        EpochGuardInventory.verify(context, current)
        check(GlobalRetirement.inspect(context).state == GlobalRetirement.LocalState.COMPLETE)
        if (current.phase != RetirementEvidenceCodec.Phase.CLEANED) write(directory, EpochTerminalCodec.record(
            current.generation, current.epoch, RetirementEvidenceCodec.Phase.CLEANED, current.roots, current.guards))
    }

    /** Fresh check before enrollment past this record. Grants no activation authority by itself. */
    fun verifyCompleted(context: Context, generation: String): EpochTerminalCodec.Record = synchronized(VaultLifecycle.lock) {
        val record = read(context.noBackupFilesDir.absoluteFile, generation)
        check(record.phase == RetirementEvidenceCodec.Phase.CLEANED)
        EpochGuardInventory.verify(context, record)
        check(GlobalRetirement.inspect(context).state == GlobalRetirement.LocalState.COMPLETE)
        record
    }

    private fun mac(directory: File, generation: String, payload: ByteArray): ByteArray {
        val key = keys().getKey(alias(directory, generation), null) as? SecretKey ?: error("Terminal authentication key missing")
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(payload) }
    }

    private fun bounded(file: File): ByteArray {
        check(VaultLifecycle.variants(file).drop(1).none(::exists)) { "Ambiguous terminal variants" }
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Terminal evidence unavailable" }
        Files.newByteChannel(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            check(channel.size() in 77..EpochTerminalCodec.MAX_BYTES.toLong())
            val out = java.io.ByteArrayOutputStream(); val buffer = java.nio.ByteBuffer.allocate(8192)
            while (true) {
                val count = channel.read(buffer); if (count == -1) break
                check(count > 0 && out.size() <= EpochTerminalCodec.MAX_BYTES - count); out.write(buffer.array(), 0, count); buffer.clear()
            }
            return out.toByteArray()
        }
    }

    private fun write(directory: File, record: EpochTerminalCodec.Record) {
        val payload = EpochTerminalCodec.encode(record)
        val bytes = payload + mac(directory, record.generation, payload)
        val target = file(directory, record.generation)
        val atomic = AtomicFile(target); val stream = atomic.startWrite(); var finished = false
        try {
            stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream); finished = true
            check(VaultLifecycle.variants(target).drop(1).none(::exists))
            check(bounded(target).contentEquals(bytes)); check(read(directory, record.generation) == record)
            VaultLifecycle.syncDirectory(directory)
        } catch (failure: Exception) {
            if (!finished) atomic.failWrite(stream) // Finalized terminal evidence is never deleted.
            throw failure
        } finally { EpochState.invalidate(directory) }
    }
}

/** Epoch-scoped retained guard discovery and verification (claims delta + contact ledgers). */
internal object EpochGuardInventory {
    private val ledgerFile = Regex("^(chat-[a-f0-9]{20}-pads)\\.bin(?:\\.(?:bak|new))?$")
    private val ledgerKey = Regex("^otp-pager-(chat-[a-f0-9]{20}-pads)$")

    /** Contact roots with artifacts on disk that no earlier wipe destroyed. */
    fun discoverRoots(context: Context, priorRoots: Set<String>): Set<String> =
        ContactInventory.scan(context).filter { it != "station-v2" && it !in priorRoots }.toSet()

    private fun ledgers(context: Context, roots: Set<String>): Set<String> {
        val names = checkNotNull(context.noBackupFilesDir.list()) { "Lifecycle directory unavailable" }
        val aliases = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList()
        val found = (names.mapNotNull { ledgerFile.matchEntire(it)?.groupValues?.get(1) } +
            aliases.mapNotNull { ledgerKey.matchEntire(it)?.groupValues?.get(1) }).toSet()
        return found.filter { it.removeSuffix("-pads") in roots }.toSortedSet()
    }

    fun capture(context: Context, manifest: ActiveManifestCodec.Record, roots: Set<String>): List<RetirementEvidenceCodec.Guard> {
        check(GlobalRetirement.isFrozen(context.noBackupFilesDir))
        val namespaces = listOf(manifest.claims) + ledgers(context, roots)
        val guards = namespaces.map { RetirementEvidenceCodec.Guard(it, RetainedGuardReader.observeFrozen(context, it).reference.sha256) }
        RetainedGuardReader.readCohortFrozen(context, guards.map { RetainedGuardReader.Reference(it.namespace, it.sha256) }, roots)
        return guards
    }

    /** Sealed commitments must still authenticate and no new epoch ledger may have appeared. */
    fun verify(context: Context, record: EpochTerminalCodec.Record) {
        check(record.phase != RetirementEvidenceCodec.Phase.INTENT) { "Pre-cleanup provenance was not sealed" }
        check(ledgers(context, record.roots) == record.guards.map { it.namespace }.filter { it != record.claims }.toSet())
        RetainedGuardReader.readCohortFrozen(context, record.guards.map { RetainedGuardReader.Reference(it.namespace, it.sha256) }, record.roots)
    }
}
