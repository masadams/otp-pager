package org.example.otppager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted, atomic snapshot: pairing, counters, inbox and outbox commit together. */
class SecureStore private constructor(context: Context, namespace: String, private val generation: VaultGeneration.Capability) {
    // Keep the two-argument/default constructor ABI used by signed-upgrade fixtures.
    constructor(context: Context, namespace: String = "station-v2") : this(context, namespace, VaultGeneration.capture(context.noBackupFilesDir))
    private val slot = namespace.also { require(Regex("[a-z0-9-]{1,40}").matches(it)) }
    internal val namespace: String get() = slot
    internal val directory: File get() = base.parentFile!!
    /** The epoch this owner captured, or null for legacy/blocked owners. Never re-evaluated. */
    internal val activeEpoch: String? get() = (generation as? VaultGeneration.Capability.Active)?.epoch
    internal val operationKey: String get() = VaultLifecycle.marker(base.parentFile!!, VaultLifecycle.root(slot)).absolutePath
    internal fun operation() = ContactOperations.open(operationKey, ::requireActive)
    private val base = File(context.noBackupFilesDir, "$slot.bin")
    private val file = AtomicFile(base)
    fun identity() = DeviceIdentity.scoped("otp-pager-$slot-device", ::requireActive, ::requireResettable)
    fun sibling(suffix: String): SecureStore = SecureStore(contextRef, "$slot-$suffix", generation)
    private val contextRef = context.applicationContext
    private val rotates = VaultKeys.rotates(slot)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun generate(alias: String): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
    }
    internal fun requireActive() {
        VaultGeneration.requireCurrent(base.parentFile!!, generation, slot)
        VaultLifecycle.requireActive(base.parentFile!!, slot)
    }
    fun load(): JSONObject? = synchronized(VaultLifecycle.lock) { requireActive(); loadActive() }
    private fun loadActive(): JSONObject? {
        if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) {
            check(VaultKeys.all(slot, keys().aliases().toList()).isEmpty()) { "Vault file missing; saved key exists. Refusing to reset counters." }
            return null
        }
        // Permission errors, a damaged path, and interrupted writes are not a new pairing.
        val bytes = file.readFully()
        val envelope = VaultKeys.Envelope.parse(slot, bytes)
        val key = keys().getKey(VaultKeys.alias(slot, envelope.generation), null) as SecretKey?
            ?: error("Local encryption key unavailable; forget pairing and pair again")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
        cipher.updateAAD(envelope.aad)
        val clear = cipher.doFinal(bytes, envelope.offset, bytes.size - envelope.offset)
        return try { JSONObject(String(clear, Charsets.UTF_8)).also { check(it.getInt("version") in 2..6) } }
        finally { clear.fill(0) }
    }
    fun save(state: JSONObject) = save(state, rekey = false)
    /**
     * [rekey] forces a fresh wrapping key for contact vaults (used after content is removed).
     * Contact vaults also rotate on their first save in a process and then at most every
     * [VaultKeys.ROTATION_INTERVAL_MS]. The old key is deleted only after the new snapshot is
     * committed, verified and the directory synced, so older ciphertext copies (flash remnants,
     * crash backups, restored files) stop being decryptable. Other stores keep one v2 key.
     */
    fun save(state: JSONObject, rekey: Boolean) = synchronized(VaultLifecycle.lock) { requireActive(); saveActive(state, rekey) }
    private fun saveActive(state: JSONObject, rekey: Boolean) {
        val keys = keys()
        val current = if (base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists())
            VaultKeys.Envelope.parse(slot, file.readFully()).generation else null
        val now = android.os.SystemClock.elapsedRealtime()
        val generation = when {
            !rotates -> 0
            current == null -> {
                check(VaultKeys.all(slot, keys.aliases().toList()).isEmpty()) { "Vault file missing; saved key exists. Refusing to reset counters." }
                1
            }
            rekey || VaultKeys.due(base.path, now) -> { check(current < VaultKeys.MAX_GENERATION) { "Vault key generations exhausted" }; current + 1 }
            else -> current
        }
        val alias = VaultKeys.alias(slot, generation)
        val key = if (!rotates) keys.getKey(alias, null) as SecretKey? ?: generate(alias) // Unchanged single-key behaviour.
        else if (generation == current) keys.getKey(alias, null) as SecretKey?
            ?: error("Local encryption key unavailable; forget pairing and pair again")
        else {
            // A key for an uncommitted generation is a leftover of an interrupted rotation, never in use.
            if (keys.containsAlias(alias)) keys.deleteEntry(alias)
            generate(alias)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val clear = state.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            if (rotates) { val header = VaultKeys.Envelope.header(generation); cipher.updateAAD(VaultKeys.Envelope.aad(slot, generation)); header + cipher.iv + cipher.doFinal(clear) }
            else { cipher.updateAAD(VaultKeys.Envelope.V2_AAD); byteArrayOf(2) + cipher.iv + cipher.doFinal(clear) }
        } finally { clear.fill(0) }
        val stream = try { file.startWrite() } catch (e: Exception) { throw IllegalStateException("Local storage write could not start. No new packet will be sent; restart the station before retrying.", e) }
        try { stream.write(encrypted); stream.fd.sync(); file.finishWrite(stream)
            check(base.isFile && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists() && base.readBytes().contentEquals(encrypted)) { "Vault commit could not be verified" }
            val directory = android.system.Os.open(base.parentFile!!.path, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) } }
        catch (e: Exception) { file.failWrite(stream); throw IllegalStateException("Local storage commit failed. No new packet will be sent; restart the station before retrying.", e) }
        if (rotates && generation != current) {
            VaultKeys.rotated(base.path, now)
            // Committed and synced: every other generation's key is now unused. Best effort; leftovers go next time.
            for (old in VaultKeys.all(slot, keys.aliases().toList())) if (old != alias) try { keys.deleteEntry(old) } catch (_: Exception) {}
        }
    }
    private fun requireResettable() {
        VaultGeneration.requireCurrent(base.parentFile!!, generation, slot)
        check(!GlobalRetirement.isFrozen(base.parentFile!!)) { "Global retirement blocks ordinary storage reset" }
    }
    fun forget() = synchronized(VaultLifecycle.lock) {
        // Global cleanup uses its explicit raw scope; stale ordinary resets cannot delete reuse guards.
        requireResettable()
        // Invalidate the key first: if the process dies, old data cannot be reopened.
        identity().forget()
        VaultKeys.deleteAll(slot)
        file.delete()
    }
}

/**
 * Wrapping-key generations and vault envelopes (package C). Generation 0 is the original
 * alias `otp-pager-<namespace>`; generation g ≥ 1 is `otp-pager-<namespace>~g`. `~` cannot
 * appear in a namespace, so aliases are unambiguous. Every scanner that finds or deletes a
 * vault's keys must use [all] or [SUFFIX]; otherwise a rotated key could survive a wipe.
 */
internal object VaultKeys {
    const val ROTATION_INTERVAL_MS = 10 * 60 * 1000L
    const val MAX_GENERATION = 999_999_999
    /** Regex fragment matching an optional rotated-generation suffix. */
    const val SUFFIX = "(?:~[1-9][0-9]{0,8})?"
    private val generationSuffix = Regex("~([1-9][0-9]{0,8})")
    private val lastRotation = mutableMapOf<String, Long>()
    fun rotates(namespace: String) = VaultLifecycle.isContact(namespace)
    fun alias(namespace: String, generation: Int): String {
        require(generation in 0..MAX_GENERATION)
        return if (generation == 0) "otp-pager-$namespace" else "otp-pager-$namespace~$generation"
    }
    fun all(namespace: String, aliases: Iterable<String>): List<String> {
        val base = alias(namespace, 0)
        return aliases.filter { it == base || (it.startsWith("$base~") && generationSuffix.matches(it.removePrefix(base))) }
    }
    fun deleteAll(namespace: String) {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (alias in all(namespace, keys.aliases().toList())) keys.deleteEntry(alias)
        check(all(namespace, keys.aliases().toList()).isEmpty()) { "Key deletion incomplete" }
    }
    fun due(path: String, now: Long) = synchronized(lastRotation) { lastRotation[path]?.let { now - it >= ROTATION_INTERVAL_MS } ?: true }
    fun rotated(path: String, now: Long) = synchronized(lastRotation) { lastRotation[path] = now }

    /** v2: [2][iv 12][ct]; v3: [3][generation u32][iv 12][ct] with namespace- and generation-bound AAD. */
    class Envelope private constructor(val generation: Int, val iv: ByteArray, val offset: Int, val aad: ByteArray) {
        companion object {
            val V2_AAD = "OTP Pager local vault v2".toByteArray(Charsets.UTF_8)
            fun aad(namespace: String, generation: Int) = "OTP Pager local vault v3\u0000$namespace\u0000$generation".toByteArray(Charsets.UTF_8)
            fun header(generation: Int) = byteArrayOf(3, (generation ushr 24).toByte(), (generation ushr 16).toByte(), (generation ushr 8).toByte(), generation.toByte())
            fun parse(namespace: String, bytes: ByteArray): Envelope = when {
                bytes.size >= 29 && bytes[0] == 2.toByte() -> Envelope(0, bytes.copyOfRange(1, 13), 13, V2_AAD)
                bytes.size >= 33 && bytes[0] == 3.toByte() && rotates(namespace) -> {
                    val g = ((bytes[1].toInt() and 255) shl 24) or ((bytes[2].toInt() and 255) shl 16) or
                        ((bytes[3].toInt() and 255) shl 8) or (bytes[4].toInt() and 255)
                    require(g in 0..MAX_GENERATION) { "Local vault damaged" }
                    Envelope(g, bytes.copyOfRange(5, 17), 17, aad(namespace, g))
                }
                else -> throw IllegalArgumentException("Local vault damaged")
            }
        }
    }
}
