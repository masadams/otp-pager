package org.example.otppager

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Write-once pad material outside the JSON vault (docs/PAD_STORAGE.md).
 *
 * `padmaterial/<root>/<padId>.bin` = header ‖ one record per slot, each
 * `iv(12) ‖ AES-256-GCM(slotKey, slot STRIDE bytes) ‖ tag(16)`, AAD = magic ‖ padId ‖ direction ‖ index.
 * The 32-byte slot keys live in the contact's encrypted, rotating vault as one table
 * (`padKeys`). Using or retiring a slot zeroes its key in the same atomic vault save; the
 * record left in this file can then never be decrypted. The file itself is never rewritten.
 */
internal object PadMaterial {
    private val MAGIC = "OTPMAT01".toByteArray(Charsets.US_ASCII)
    const val HEADER = 8 + 16 + 4 + 4
    const val RECORD = 12 + PadProtocol.STRIDE + 16
    const val KEY = 32
    private val padIdPattern = Regex("[a-f0-9]{32}")
    private val rootPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") // A contact root such as chat-… or station-v2.

    fun directory(base: File, root: String): File { require(rootPattern.matches(root)); return File(File(base, "padmaterial"), root) }
    fun file(base: File, root: String, padId: String): File { require(padIdPattern.matches(padId)); return File(directory(base, root), "$padId.bin") }
    fun slots(keys: ByteArray): Int { require(keys.size % (2 * KEY) == 0 && keys.isNotEmpty()); return keys.size / (2 * KEY) }
    private fun index(slots: Int, role: Char, counter: Long): Int {
        require(role == 'A' || role == 'B'); require(counter in 1..slots.toLong()) { "Pad exhausted; exchange a fresh pad" }
        return (if (role == 'A') 0 else slots) + (counter.toInt() - 1)
    }
    private fun raw(padId: String) = ByteArray(16) { padId.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun aad(id: ByteArray, slot: Int) = MAGIC + id + ByteBuffer.allocate(4).putInt(slot).array()
    private fun header(id: ByteArray, slots: Int, size: Int) = ByteBuffer.allocate(HEADER).put(MAGIC).put(id).putInt(slots).putInt(size).array()
    private fun zero(bytes: ByteArray, at: Int, len: Int): Boolean { for (i in at until at + len) if (bytes[i] != 0.toByte()) return false; return true }

    fun available(keys: ByteArray, role: Char, counter: Long): Boolean {
        val at = index(slots(keys), role, counter) * KEY
        return !zero(keys, at, KEY)
    }
    /** Returns true when the key was still present. */
    fun retire(keys: ByteArray, role: Char, counter: Long): Boolean {
        val at = index(slots(keys), role, counter) * KEY
        if (zero(keys, at, KEY)) return false
        keys.fill(0, at, at + KEY); return true
    }
    fun remaining(keys: ByteArray, role: Char): Int = (1..slots(keys)).count { available(keys, role, it.toLong()) }

    /**
     * Encrypts each available (non-zero) slot of [pad] under a fresh random key and returns the key
     * table. Already-retired slots get no key. Written to a temporary file, fsynced, renamed, verified.
     */
    fun write(target: File, padId: String, pad: ByteArray, syncDirectory: (File) -> Unit = {}): ByteArray {
        require(padIdPattern.matches(padId) && target.name == "$padId.bin")
        val slots = PadProtocol.slots(pad); val id = raw(padId)
        val keys = ByteArray(slots * 2 * KEY); val random = SecureRandom()
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Pad storage unavailable" }
        val temporary = File(parent, target.name + ".new")
        val record = ByteArray(PadProtocol.STRIDE)
        try {
            FileOutputStream(temporary).use { out ->
                out.write(header(id, slots, pad.size))
                for (slot in 0 until 2 * slots) {
                    val role = if (slot < slots) 'A' else 'B'
                    val at = (if (role == 'A') 0 else pad.size / 2) + (slot % slots) * PadProtocol.STRIDE
                    if (zero(pad, at, PadProtocol.STRIDE)) { out.write(ByteArray(RECORD)); continue }
                    val key = ByteArray(KEY).also(random::nextBytes)
                    if (zero(key, 0, KEY)) key[0] = 1 // An all-zero key means "retired".
                    key.copyInto(keys, slot * KEY)
                    val iv = ByteArray(12).also(random::nextBytes)
                    pad.copyInto(record, 0, at, at + PadProtocol.STRIDE)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    cipher.updateAAD(aad(id, slot))
                    out.write(iv); out.write(cipher.doFinal(record)); key.fill(0)
                }
                out.flush(); out.fd.sync()
            }
            check(temporary.renameTo(target)) { "Pad storage rename failed" }
            syncDirectory(parent)
            verify(target, padId, keys)
            return keys
        } catch (failure: Exception) {
            keys.fill(0); temporary.delete(); throw failure
        } finally { record.fill(0) }
    }

    /** Header must match, and every keyed record must authenticate. */
    fun verify(target: File, padId: String, keys: ByteArray) {
        val slots = slots(keys)
        RandomAccessFile(target, "r").use { file ->
            check(file.length() == HEADER.toLong() + 2L * slots * RECORD) { "Pad storage has the wrong size" }
            val head = ByteArray(HEADER); file.readFully(head)
            val buffer = ByteBuffer.wrap(head)
            check(head.copyOf(8).contentEquals(MAGIC)) { "Pad storage format unknown" }
            check(MessageDigest.isEqual(head.copyOfRange(8, 24), raw(padId))) { "Pad storage belongs to a different pad" }
            buffer.position(24); check(buffer.int == slots) { "Pad storage slot count mismatch" }
            check(PadProtocol.validSize(buffer.int)) { "Pad storage size invalid" }
        }
        for (slot in 0 until 2 * slots) if (!zero(keys, slot * KEY, KEY)) decrypt(target, padId, keys, slot).fill(0)
    }

    private fun decrypt(target: File, padId: String, keys: ByteArray, slot: Int): ByteArray {
        val record = ByteArray(RECORD)
        RandomAccessFile(target, "r").use { it.seek(HEADER + slot.toLong() * RECORD); it.readFully(record) }
        val key = keys.copyOfRange(slot * KEY, slot * KEY + KEY)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, record, 0, 12))
            cipher.updateAAD(aad(raw(padId), slot))
            return cipher.doFinal(record, 12, RECORD - 12)
        } finally { key.fill(0) }
    }

    /** One slot's STRIDE bytes. A zeroed key means the slot was used or retired. */
    fun slot(target: File, padId: String, keys: ByteArray, role: Char, counter: Long): ByteArray {
        val slot = index(slots(keys), role, counter)
        require(!zero(keys, slot * KEY, KEY)) { "Pad slot retired" }
        return decrypt(target, padId, keys, slot)
    }

    /** Material for pads other than [keep] is never needed again: renewal or install replaced it. */
    fun prune(base: File, root: String, keep: Set<String>) {
        val directory = directory(base, root)
        directory.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".new").removeSuffix(".bin")
            if (id !in keep || f.name.endsWith(".new")) f.delete()
        }
    }
    fun deleteAll(base: File, root: String) {
        val directory = directory(base, root)
        directory.listFiles()?.forEach { check(!it.exists() || it.delete()) { "Pad storage deletion incomplete" } }
        check(!directory.exists() || directory.delete()) { "Pad storage deletion incomplete" }
        directory.parentFile?.let { parent -> if (parent.list()?.isEmpty() == true) parent.delete() }
    }
    fun gone(base: File, root: String) = !directory(base, root).exists()
}
