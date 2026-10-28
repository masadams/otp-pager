package org.example.otppager

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Authenticated `OTPACT01` manifest files (design: docs/ACTIVE_EPOCH_DESIGN.md).
 *
 * Each manifest is MACed with its epoch's existing permanent staging reservation key; this
 * object never creates or deletes keys. [create] has no production caller in this build:
 * only the reviewed activation transaction (slice B3e) may write sequence 1. Until then no
 * manifest exists and every lineage decision below falls back to today's frozen/legacy rules.
 */
internal object EpochManifests {
    private const val PREFIX = "epoch-active-"
    private val filePattern = Regex("epoch-active-([a-f0-9]{32})\\.bin")
    private const val MAX_FILE_BYTES = ActiveManifestCodec.MAX_PAYLOAD_BYTES + 32
    private const val MIN_FILE_BYTES = ActiveManifestCodec.HEADER_BYTES + 10 + 32

    fun file(directory: File, epoch: String) = File(directory, "$PREFIX$epoch.bin")
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Cheap name check. An unreadable directory reports present so callers resolve (and freeze). */
    fun anyPresent(directory: File): Boolean = directory.list()?.any { it.startsWith(PREFIX) } ?: true

    /** Every manifest-like name must be a base file of a valid epoch; variants or strays throw. */
    fun readAll(directory: File): List<ActiveManifestCodec.Record> = synchronized(VaultLifecycle.lock) {
        val names = checkNotNull(directory.list()) { "Lifecycle directory unavailable" }.filter { it.startsWith(PREFIX) }
        check(names.size <= EnrollmentStageCodec.MAX_ATTEMPTS) { "Manifest capacity exceeded" }
        names.sorted().map { name ->
            val epoch = checkNotNull(filePattern.matchEntire(name)) { "Unknown or variant manifest file" }.groupValues[1]
            read(directory, epoch)
        }
    }

    fun read(directory: File, epoch: String): ActiveManifestCodec.Record = synchronized(VaultLifecycle.lock) {
        val bytes = bounded(file(directory, epoch))
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        check(MessageDigest.isEqual(mac(directory, epoch, payload), bytes.copyOfRange(payload.size, bytes.size))) {
            "Manifest authentication failed"
        }
        ActiveManifestCodec.decode(payload).also { check(it.epoch == epoch) { "Manifest epoch mismatch" } }
    }

    /** Activation commit (slice B3e only). Never overwrites; sequence 1 lists only the initial root. */
    internal fun create(directory: File, record: ActiveManifestCodec.Record) = synchronized(VaultLifecycle.lock) {
        check(record.sequence == 1L && record.roots == listOf(record.initialRoot)) { "Manifest must start at sequence 1" }
        check(VaultLifecycle.variants(file(directory, record.epoch)).none(::exists)) { "Manifest already exists" }
        write(directory, record)
    }

    /**
     * Durably list a new contact root before its first key or file is created. A manifest
     * rolled back to an earlier sequence only withdraws permission from newer roots.
     */
    fun appendRoot(directory: File, epoch: String, root: String): ActiveManifestCodec.Record = synchronized(VaultLifecycle.lock) {
        val next = ActiveManifestCodec.appended(read(directory, epoch), root)
        write(directory, next)
        next
    }

    private fun mac(directory: File, epoch: String, payload: ByteArray): ByteArray {
        val key = keys().getKey(EnrollmentStaging.keyAlias(directory, epoch), null) as? SecretKey
            ?: error("Epoch reservation key missing")
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(payload) }
    }

    private fun bounded(file: File): ByteArray {
        check(VaultLifecycle.variants(file).drop(1).none(::exists)) { "Ambiguous manifest variants" }
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Manifest unavailable" }
        Files.newByteChannel(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            check(channel.size() in MIN_FILE_BYTES.toLong()..MAX_FILE_BYTES.toLong()) { "Manifest size invalid" }
            val out = java.io.ByteArrayOutputStream(); val buffer = java.nio.ByteBuffer.allocate(4096)
            while (true) {
                val count = channel.read(buffer); if (count == -1) break
                check(count > 0 && out.size() <= MAX_FILE_BYTES - count); out.write(buffer.array(), 0, count); buffer.clear()
            }
            return out.toByteArray()
        }
    }

    private fun write(directory: File, record: ActiveManifestCodec.Record) {
        val payload = ActiveManifestCodec.encode(record)
        val bytes = payload + mac(directory, record.epoch, payload)
        val target = file(directory, record.epoch)
        check(VaultLifecycle.variants(target).drop(1).none(::exists)) { "Ambiguous manifest variants" }
        val atomic = AtomicFile(target); val stream = atomic.startWrite(); var finished = false
        try {
            stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream); finished = true
            check(bounded(target).contentEquals(bytes)) { "Manifest readback mismatch" }
            check(read(directory, record.epoch) == record)
            VaultLifecycle.syncDirectory(directory)
        } catch (failure: Exception) {
            if (!finished) atomic.failWrite(stream) // A finalized manifest is never deleted on a later check failure.
            throw failure
        }
    }
}

/**
 * Reads real lifecycle records into an [EpochLineage.Observation]. Any read failure becomes
 * `unreadable`, which resolves to Frozen. Uncached by design in this slice: no manifest can
 * exist yet, so callers only reach here in already-frozen states; B3e adds a validated cache
 * before ACTIVE epochs make this a per-operation path.
 */
internal object EpochState {
    fun observe(directory: File): EpochLineage.Observation = synchronized(VaultLifecycle.lock) {
        try {
            val journal = VaultLifecycle.variants(GlobalRetirement.marker(directory))
                .any { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            val terminals = when (RetirementEvidence.probe(directory)) {
                RetirementEvidence.Presence.NONE -> emptyList()
                RetirementEvidence.Presence.UNAVAILABLE -> error("Terminal evidence unavailable")
                RetirementEvidence.Presence.PRESENT -> {
                    val record = RetirementEvidence.read(directory)
                    listOf(EpochLineage.Terminal(record.generation, EpochLineage.LEGACY, RetirementEvidence.commitment(directory),
                        record.phase == RetirementEvidenceCodec.Phase.CLEANED))
                }
            }
            val stages = when (EnrollmentStaging.probe(directory)) {
                EnrollmentStaging.Presence.NONE -> emptyList()
                EnrollmentStaging.Presence.UNAVAILABLE -> error("Staging unavailable")
                EnrollmentStaging.Presence.PRESENT -> EnrollmentStaging.records(directory)
            }
            EpochLineage.Observation(legacyJournal = journal, terminals = terminals, stages = stages,
                manifests = EpochManifests.readAll(directory))
        } catch (_: Exception) { EpochLineage.Observation(unreadable = true) }
    }

    fun resolve(directory: File): EpochLineage.State = EpochLineage.resolve(observe(directory))

    /** Without any manifest file the answer is never Active, so skip all reads. */
    fun resolveIfManifest(directory: File): EpochLineage.State =
        if (EpochManifests.anyPresent(directory)) resolve(directory) else EpochLineage.State.Frozen("no-manifest")

    /** Cheap legacy fast path for routing: no staging artifacts means no epoch can be ACTIVE. */
    fun activeIfStaged(directory: File): ActiveManifestCodec.Record? = synchronized(VaultLifecycle.lock) {
        if (EnrollmentStaging.presence(directory) == EnrollmentStaging.Presence.NONE) null else active(directory)
    }

    /** Active manifest when the lineage resolves to an ACTIVE epoch, else null. */
    fun active(directory: File): ActiveManifestCodec.Record? = (resolveIfManifest(directory) as? EpochLineage.State.Active)?.manifest
}
