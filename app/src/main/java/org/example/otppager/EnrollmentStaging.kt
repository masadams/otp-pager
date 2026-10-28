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
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Confirmation-bound EMPTY storage preparation. Every attempt remains globally blocked, including PREPARED. */
internal object EnrollmentStaging {
    enum class Presence { NONE, PRESENT, UNAVAILABLE }
    private val none = mutableSetOf<String>()
    /** Staging records and keys are permanent once created; a cached PRESENT can only fail closed. */
    private val present = mutableSetOf<String>()
    private val confirmations = mutableMapOf<String, Confirmation>()
    private val epochPattern = Regex("[a-f0-9]{32}")
    private val filePattern = Regex("enrollment-stage-([a-f0-9]{32})\\.bin(?:\\.(?:bak|new))?")
    private val metadataFile = Regex("(enr-[a-f0-9]{20}(?:-claims)?)\\.bin(?:\\.(?:bak|new))?")
    private val metadataKey = Regex("otp-pager-(enr-[a-f0-9]{20}(?:-claims)?)(?:-device)?")
    private const val prefix = "enrollment-stage-"
    class Confirmation internal constructor(internal val directory: String, internal val generation: String, internal val commitment: String)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun hash(data: ByteArray) = PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(data))
    internal fun keyPrefix(directory: File) = "otp-pager-enroll-" + hash(directory.absolutePath.toByteArray()).take(32) + "-"
    internal fun keyAlias(directory: File, epoch: String) = keyPrefix(directory) + epoch
    fun marker(directory: File, epoch: String): File {
        require(epochPattern.matches(epoch)); return File(directory, "$prefix$epoch.bin")
    }
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private data class Sources(val names: Set<String>, val aliases: Set<String>)
    private fun sources(directory: File): Sources {
        var count = 0; val names = sortedSetOf<String>(); val aliases = sortedSetOf<String>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (path in stream) { check(++count <= 4096); names.add(path.fileName.toString()) }
        }
        val all = keys().aliases()
        while (all.hasMoreElements()) { check(++count <= 4096); aliases.add(all.nextElement()) }
        return Sources(names, aliases)
    }
    fun probe(directory: File): Presence = synchronized(VaultLifecycle.lock) {
        try {
            val s = sources(directory)
            val found = s.names.any { it.startsWith(prefix) || it.startsWith("enr-") || it.startsWith("epoch-active-") || it.startsWith("epoch-terminal-") } ||
                s.aliases.any { it.startsWith(keyPrefix(directory)) || it.startsWith("otp-pager-enr-") }
            if (found) { none.remove(directory.absolutePath); present.add(directory.absolutePath) }
            else { none.add(directory.absolutePath); present.remove(directory.absolutePath) }
            if (found) Presence.PRESENT else Presence.NONE
        } catch (_: Exception) { Presence.UNAVAILABLE }
    }
    fun presence(directory: File): Presence = synchronized(VaultLifecycle.lock) {
        when (directory.absolutePath) { in present -> Presence.PRESENT; in none -> Presence.NONE; else -> probe(directory) }
    }
    /** The epoch an attempt became once activated; its storage is live state, never staging. */
    internal fun activated(directory: File, r: EnrollmentStageCodec.Record) =
        VaultLifecycle.variants(EpochManifests.file(directory, r.epoch)).any { exists(it) }

    /** The newest terminal in the lineage, verified CLEANED with every check its format defines. */
    internal class Latest(val generation: String, val commitment: String, val priorRoots: Set<String>)
    internal fun latestCompleted(context: Context): Latest = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir.absoluteFile
        EpochState.invalidate(directory)
        val detail = EpochState.detail(directory)
        check(detail.state == EpochLineage.State.Frozen("awaiting-enrollment")) { "Not awaiting fresh enrollment" }
        val terminal = detail.chain.last()
        check(terminal.cleaned)
        if (terminal.boundEpoch == EpochLineage.LEGACY) {
            check(RetirementEvidence.verifyCompleted(context).generation == terminal.generation)
            check(RetirementEvidence.commitment(directory) == terminal.commitment)
        } else {
            EpochEvidence.verifyCompleted(context, terminal.generation)
            check(EpochEvidence.commitment(directory, terminal.generation) == terminal.commitment)
        }
        Latest(terminal.generation, terminal.commitment, detail.chain.flatMap { it.roots }.toSet())
    }

    fun confirmation(context: Context): Confirmation = synchronized(VaultLifecycle.lock) {
        val latest = latestCompleted(context)
        readAll(context.noBackupFilesDir).also { records ->
            check(records.none { it.phase != EnrollmentStageCodec.Phase.ABANDONED && !activated(context.noBackupFilesDir, it) })
            records.filter { it.phase == EnrollmentStageCodec.Phase.ABANDONED }.forEach { Vault.verifyGone(context.noBackupFilesDir, it) }
        }
        Confirmation(context.noBackupFilesDir.absolutePath, latest.generation, latest.commitment)
            .also { confirmations[it.directory] = it }
    }
    fun cancel(confirmation: Confirmation) = synchronized(VaultLifecycle.lock) {
        if (confirmations[confirmation.directory] === confirmation) confirmations.remove(confirmation.directory)
        Unit
    }
    /** All inputs are derived internally; the caller cannot supply credentials, plaintext, namespaces or keys. */
    fun prepare(context: Context, confirmation: Confirmation): EnrollmentStageCodec.Record = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir.absoluteFile
        check(confirmation.directory == directory.path && confirmations[directory.path] === confirmation) { "Fresh local confirmation required" }
        confirmations.remove(directory.path) // One use, even if storage fails.
        val terminal = latestCompleted(context)
        check(terminal.generation == confirmation.generation && terminal.commitment == confirmation.commitment)
        val previous = readAll(directory)
        check(previous.size < EnrollmentStageCodec.MAX_ATTEMPTS &&
            previous.all { it.phase == EnrollmentStageCodec.Phase.ABANDONED || activated(directory, it) })
        previous.filter { it.phase == EnrollmentStageCodec.Phase.ABANDONED }.forEach { Vault.verifyGone(directory, it) }
        val s = sources(directory)
        var r: EnrollmentStageCodec.Record
        do {
            val epoch = PadProtocol.hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
            r = EnrollmentStageCodec.Record(epoch, terminal.generation, confirmation.commitment, EnrollmentStageCodec.Phase.PREPARING)
        } while (r.root in terminal.priorRoots || previous.any { it.index == r.index || it.root == r.root } ||
            s.names.any { it.startsWith(r.index + ".") || it.startsWith(r.index + "-") || it.startsWith(r.root + ".") || it.startsWith(r.root + "-") } ||
            s.aliases.any { it == "otp-pager-${r.index}" || it.startsWith("otp-pager-${r.index}-") || it == "otp-pager-${r.root}" || it.startsWith("otp-pager-${r.root}-") } ||
            keyAlias(directory, r.epoch) in s.aliases)
        none.remove(directory.path); present.add(directory.path); EpochState.invalidate(directory)
        // The per-attempt authentication key is a permanent reservation; never recreated/deleted on recovery.
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias(directory, r.epoch), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build()); generator.generateKey()
        write(directory, r) // Durable PREPARING before either fresh AES key/file.
        try {
            val indexHash = Vault.create(directory, r.index, Vault.index(r))
            val claimsHash = Vault.create(directory, r.claims, Vault.claims())
            check(latestCompleted(context).commitment == r.terminalCommitment)
            r = r.copy(phase = EnrollmentStageCodec.Phase.PREPARED, indexHash = indexHash, claimsHash = claimsHash)
            Vault.verify(directory, r); write(directory, r)
            r
        } catch (failure: Exception) {
            try { abandonRecord(directory, r) } catch (_: Exception) { /* Partial cleanup stays blocked and is retried on restart. */ }
            throw failure
        }
    }
    /** Attempts bound to the newest terminal; earlier activated epochs are listed but not re-verified here. */
    fun inspect(context: Context): List<EnrollmentStageCodec.Record> = synchronized(VaultLifecycle.lock) {
        val terminal = latestCompleted(context)
        readAll(context.noBackupFilesDir).filterNot { activated(context.noBackupFilesDir, it) }.also { records -> records.forEach { r ->
            check(r.terminalGeneration == terminal.generation && r.terminalCommitment == terminal.commitment && r.root !in terminal.priorRoots)
            if (r.phase == EnrollmentStageCodec.Phase.PREPARED) Vault.verify(context.noBackupFilesDir, r)
            if (r.phase == EnrollmentStageCodec.Phase.ABANDONED) Vault.verifyGone(context.noBackupFilesDir, r)
        } }
    }
    fun abandon(context: Context, epoch: String) = synchronized(VaultLifecycle.lock) {
        val record = readAll(context.noBackupFilesDir).single { it.epoch == epoch }
        check(!activated(context.noBackupFilesDir, record)) { "An activated epoch is wiped, never abandoned" }
        abandonRecord(context.noBackupFilesDir, record)
    }
    /** Called before old global scope discovery. Never finishes PREPARING or activates PREPARED. */
    fun recoverInterrupted(context: Context) = synchronized(VaultLifecycle.lock) {
        if (presence(context.noBackupFilesDir) == Presence.NONE) return@synchronized
        val records = readAll(context.noBackupFilesDir)
        var failed = false
        for (r in records) {
            try {
                // An activated epoch's index/claims delta are live state and retained guards, never
                // abandoned staging. Epoch-scoped wipe recovery is slice B3d; until then stay blocked.
                if (activated(context.noBackupFilesDir, r)) continue
                if (r.phase == EnrollmentStageCodec.Phase.PREPARED) {
                    try { Vault.verify(context.noBackupFilesDir, r) } catch (_: Exception) { abandonRecord(context.noBackupFilesDir, r) }
                } else abandonRecord(context.noBackupFilesDir, r)
            } catch (_: Exception) { failed = true }
        }
        check(!failed) { "Staging cleanup incomplete" }
    }
    private fun abandonRecord(directory: File, r: EnrollmentStageCodec.Record) {
        // Scope was authenticated and allocated while globally frozen, and has NEVER been active.
        var failed = false
        val slots = listOf(r.index, r.claims, r.root, "${r.root}-draft", "${r.root}-pads")
        for (slot in slots) {
            for (alias in listOf("otp-pager-$slot", "otp-pager-$slot-device")) try {
                val store = keys(); store.deleteEntry(alias); check(!store.containsAlias(alias))
            } catch (_: Exception) { failed = true }
            for (file in VaultLifecycle.variants(File(directory, "$slot.bin"))) try {
                if (exists(file)) check(file.delete()); check(!exists(file))
            } catch (_: Exception) { failed = true }
        }
        VaultLifecycle.syncDirectory(directory)
        check(!failed) { "Stage erasure incomplete" }
        if (r.phase != EnrollmentStageCodec.Phase.ABANDONED) write(directory, r.copy(phase = EnrollmentStageCodec.Phase.ABANDONED,
            indexHash = EnrollmentStageCodec.zeroHash, claimsHash = EnrollmentStageCodec.zeroHash))
    }
    /** Authenticated attempt records for lineage resolution; never repairs or creates anything. */
    fun records(directory: File): List<EnrollmentStageCodec.Record> = synchronized(VaultLifecycle.lock) { readAll(directory) }
    private fun readAll(directory: File): List<EnrollmentStageCodec.Record> {
        val s = sources(directory)
        val files = s.names.filter { it.startsWith(prefix) }.map {
            checkNotNull(filePattern.matchEntire(it)) { "Unknown staging record" }.groupValues[1]
        }.toSet()
        val aliases = s.aliases.filter { it.startsWith(keyPrefix(directory)) }.map {
            it.removePrefix(keyPrefix(directory)).also { epoch -> check(epochPattern.matches(epoch)) }
        }.toSet()
        check(files == aliases && files.size <= EnrollmentStageCodec.MAX_ATTEMPTS) { "Missing staging file/key or capacity exceeded" }
        return files.sorted().map { read(directory, it) }.also { records ->
            check(records.count { it.phase != EnrollmentStageCodec.Phase.ABANDONED && !activated(directory, it) } <= 1)
            check(records.map { it.index }.toSet().size == records.size)
            val slots = records.flatMap { listOf(it.index, it.claims) }.toSet()
            for (name in s.names.filter { it.startsWith("enr-") }) check(metadataFile.matchEntire(name)?.groupValues?.get(1) in slots)
            for (alias in s.aliases.filter { it.startsWith("otp-pager-enr-") }) check(metadataKey.matchEntire(alias)?.groupValues?.get(1) in slots)
        }
    }
    private fun read(directory: File, epoch: String): EnrollmentStageCodec.Record {
        val bytes = bytes(marker(directory, epoch), EnrollmentStageCodec.ENVELOPE_BYTES)
        check(bytes.size == EnrollmentStageCodec.ENVELOPE_BYTES)
        val payload = bytes.copyOfRange(0, EnrollmentStageCodec.PAYLOAD_BYTES)
        check(MessageDigest.isEqual(mac(directory, epoch, payload), bytes.copyOfRange(payload.size, bytes.size)))
        return EnrollmentStageCodec.decode(payload).also { check(it.epoch == epoch) }
    }
    private fun mac(directory: File, epoch: String, payload: ByteArray): ByteArray {
        val key = keys().getKey(keyAlias(directory, epoch), null) as? SecretKey ?: error("Stage authentication key missing")
        return Mac.getInstance("HmacSHA256").run { init(key); doFinal(payload) }
    }
    private fun write(directory: File, r: EnrollmentStageCodec.Record) {
        val payload = EnrollmentStageCodec.encode(r)
        try {
            atomic(directory, marker(directory, r.epoch), payload + mac(directory, r.epoch, payload))
            check(read(directory, r.epoch) == r)
        } finally { EpochState.invalidate(directory) }
    }
    private fun bytes(file: File, limit: Int): ByteArray {
        check(VaultLifecycle.variants(file).drop(1).none(::exists))
        check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        Files.newByteChannel(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            check(channel.size() in 1..limit.toLong())
            val out = java.io.ByteArrayOutputStream(); val buffer = java.nio.ByteBuffer.allocate(4096)
            while (true) {
                val count = channel.read(buffer); if (count == -1) break
                check(count > 0 && out.size() <= limit - count); out.write(buffer.array(), 0, count); buffer.clear()
            }
            return out.toByteArray()
        }
    }
    private fun atomic(directory: File, file: File, data: ByteArray) {
        check(VaultLifecycle.variants(file).drop(1).none(::exists))
        if (exists(file)) check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
        val atomic = AtomicFile(file); val stream = atomic.startWrite(); var finished = false
        try {
            stream.write(data); stream.fd.sync(); atomic.finishWrite(stream); finished = true
            check(bytes(file, maxOf(8192, data.size)).contentEquals(data)); VaultLifecycle.syncDirectory(directory)
        } catch (failure: Exception) { if (!finished) atomic.failWrite(stream); throw failure }
    }
    private object Vault {
        private val aad = "OTP Pager local vault v2".toByteArray(Charsets.UTF_8)
        fun index(r: EnrollmentStageCodec.Record) = ("{\"version\":3,\"active\":\"${r.root}\",\"chats\":[{\"id\":\"${r.root}\",\"entryId\":\"${r.root}\",\"name\":\"Chat 1\"}]}").toByteArray()
        fun claims() = "{\"version\":2,\"pads\":{},\"rooms\":{}}".toByteArray()
        fun create(directory: File, namespace: String, clear: ByteArray): String {
            val file = File(directory, "$namespace.bin"); val alias = "otp-pager-$namespace"
            check(VaultLifecycle.variants(file).none(::exists) && !keys().containsAlias(alias))
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            val key = generator.generateKey(); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
            val data = try { byteArrayOf(2) + cipher.iv + cipher.doFinal(clear) } finally { clear.fill(0) }
            atomic(directory, file, data)
            return hash(data)
        }
        private fun verifySlot(directory: File, namespace: String, expectedHash: String, expectedClear: ByteArray) {
            val data = bytes(File(directory, "$namespace.bin"), 8192)
            check(hash(data) == expectedHash && data.size >= 29 && data[0] == 2.toByte())
            val key = keys().getKey("otp-pager-$namespace", null) as? SecretKey ?: error("Staged key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(1, 13))); cipher.updateAAD(aad)
            val clear = cipher.doFinal(data, 13, data.size - 13)
            try { check(clear.contentEquals(expectedClear)) } finally { clear.fill(0); expectedClear.fill(0) }
        }
        fun verifyGone(directory: File, r: EnrollmentStageCodec.Record) {
            for (slot in listOf(r.index, r.claims, r.root, "${r.root}-draft", "${r.root}-pads")) {
                check(VaultLifecycle.variants(File(directory, "$slot.bin")).none(::exists))
                check(!keys().containsAlias("otp-pager-$slot") && !keys().containsAlias("otp-pager-$slot-device"))
            }
        }
        fun verify(directory: File, r: EnrollmentStageCodec.Record) {
            check(r.phase == EnrollmentStageCodec.Phase.PREPARED)
            verifySlot(directory, r.index, r.indexHash, index(r)); verifySlot(directory, r.claims, r.claimsHash, claims())
            check(!keys().containsAlias("otp-pager-${r.index}-device") && !keys().containsAlias("otp-pager-${r.claims}-device"))
            for (slot in listOf(r.root, "${r.root}-draft", "${r.root}-pads")) {
                check(VaultLifecycle.variants(File(directory, "$slot.bin")).none(::exists))
                check(!keys().containsAlias("otp-pager-$slot") && !keys().containsAlias("otp-pager-$slot-device"))
            }
        }
    }
}
