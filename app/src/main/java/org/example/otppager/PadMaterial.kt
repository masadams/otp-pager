package org.example.otppager

import java.io.Closeable
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
 * `padmaterial/<root>/<padId>.bin` = header (magic ‖ file nonce ‖ pad id ‖ slots ‖ size) ‖ one
 * record per slot, `iv(12) ‖ AES-256-GCM(slotKey, slot STRIDE bytes) ‖ tag(16)`, with
 * AAD = magic ‖ file nonce ‖ slot index. The file nonce (not the pad id) binds records, so a pad
 * can be streamed in before its id (a hash of the whole pad) is known.
 *
 * The 32-byte slot keys live in the contact's encrypted, rotating vault as one table
 * (`padKeys`). Using or retiring a slot zeroes its key in the same atomic vault save; the record
 * left in this file can then never be decrypted. The file itself is never rewritten.
 */
internal object PadMaterial {
    private val MAGIC = "OTPMAT01".toByteArray(Charsets.US_ASCII)
    const val HEADER = 8 + 16 + 16 + 4 + 4
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
    private fun aad(nonce: ByteArray, slot: Int) = MAGIC + nonce + ByteBuffer.allocate(4).putInt(slot).array()
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

    class Installed(val padId: String, val digest: ByteArray, val keys: ByteArray, val file: File)

    /**
     * Streams a pad, in its original byte order (A half then B half), into a new material file.
     * Bytes past the last whole slot of each half are hashed but not stored. All-zero slots are
     * already used and get no key. [finish] names the file by the pad id and verifies it.
     */
    class Writer(private val directory: File, val size: Int, private val syncDirectory: (File) -> Unit = {}) : Closeable {
        val slots = run { require(PadProtocol.validSize(size)); size / 2 / PadProtocol.STRIDE }
        private val random = SecureRandom()
        private val nonce = ByteArray(16).also(random::nextBytes)
        private val temporary: File
        private val out: FileOutputStream
        private val digest = MessageDigest.getInstance("SHA-256")
        private val keys = ByteArray(slots * 2 * KEY)
        private val slot = ByteArray(PadProtocol.STRIDE)
        private var filled = 0
        private var slotIndex = 0
        private var position = 0L
        private var done = false
        init {
            check(directory.isDirectory || directory.mkdirs()) { "Pad storage unavailable" }
            temporary = File(directory, "pending-" + PadProtocol.hex(nonce) + ".new")
            out = FileOutputStream(temporary)
            out.write(ByteArray(HEADER))
        }
        val written: Long get() = position
        fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            check(!done); require(offset >= 0 && length >= 0 && offset + length <= bytes.size && position + length <= size) { "Pad longer than announced" }
            digest.update(bytes, offset, length)
            val half = size / 2; val used = slots * PadProtocol.STRIDE
            var i = offset; val end = offset + length
            while (i < end) {
                val within = (position % half).toInt()
                if (within >= used) { val skip = minOf(end - i, half - within); i += skip; position += skip; continue }
                val take = minOf(end - i, PadProtocol.STRIDE - filled)
                bytes.copyInto(slot, filled, i, i + take); filled += take; i += take; position += take
                if (filled == PadProtocol.STRIDE) { emit(); filled = 0 }
            }
        }
        private fun emit() {
            if (zero(slot, 0, PadProtocol.STRIDE)) out.write(ByteArray(RECORD))
            else {
                val key = ByteArray(KEY).also(random::nextBytes)
                if (zero(key, 0, KEY)) key[0] = 1 // An all-zero key means "retired".
                key.copyInto(keys, slotIndex * KEY)
                val iv = ByteArray(12).also(random::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(aad(nonce, slotIndex))
                out.write(iv); out.write(cipher.doFinal(slot)); key.fill(0)
            }
            slot.fill(0); slotIndex++
        }
        /**
         * [knownId] names an existing pad whose used slots are already zeroed (migration), so its
         * bytes no longer hash to the id the vault authenticated. New pads take the computed id.
         */
        fun finish(knownId: String? = null): Installed {
            check(!done); check(position == size.toLong() && slotIndex == 2 * slots) { "Pad shorter than announced" }
            require(knownId == null || padIdPattern.matches(knownId))
            done = true
            val full = digest.digest(); val id = knownId ?: PadProtocol.hex(full.copyOf(16))
            try {
                out.flush()
                out.channel.position(0)
                out.write(ByteBuffer.allocate(HEADER).put(MAGIC).put(nonce).put(raw(id)).putInt(slots).putInt(size).array())
                out.flush(); out.fd.sync(); out.close()
                val target = File(directory, "$id.bin")
                require(!target.exists()) { "This pad was already installed. Exchange a fresh pad; re-import is blocked." } // Never replace live material.
                check(temporary.renameTo(target)) { "Pad storage rename failed" }
                syncDirectory(directory)
                verify(target, id, keys)
                return Installed(id, full, keys.copyOf(), target)
            } catch (failure: Exception) { abort(); throw failure }
            finally { keys.fill(0) }
        }
        fun abort() { done = true; try { out.close() } catch (_: Exception) {}; temporary.delete(); keys.fill(0); slot.fill(0) }
        override fun close() { if (!done) abort() }
    }

    /** Whole-pad convenience for pads already in memory (up to 4 MiB), under their vault-authenticated id. */
    fun write(target: File, padId: String, pad: ByteArray, syncDirectory: (File) -> Unit = {}): ByteArray {
        require(padIdPattern.matches(padId) && target.name == "$padId.bin")
        return Writer(checkNotNull(target.parentFile), pad.size, syncDirectory).use { w -> w.write(pad); w.finish(padId) }.keys
    }

    private class Header(val nonce: ByteArray, val slots: Int)
    private fun header(file: RandomAccessFile, padId: String): Header {
        val head = ByteArray(HEADER); file.seek(0); file.readFully(head)
        check(head.copyOf(8).contentEquals(MAGIC)) { "Pad storage format unknown" }
        check(MessageDigest.isEqual(head.copyOfRange(24, 40), raw(padId))) { "Pad storage belongs to a different pad" }
        val buffer = ByteBuffer.wrap(head, 40, 8); val slots = buffer.int; val size = buffer.int
        check(PadProtocol.validSize(size) && slots == size / 2 / PadProtocol.STRIDE) { "Pad storage size invalid" }
        check(file.length() == HEADER.toLong() + 2L * slots * RECORD) { "Pad storage has the wrong size" }
        return Header(head.copyOfRange(8, 24), slots)
    }

    /** Header must match, and every keyed record must authenticate. */
    fun verify(target: File, padId: String, keys: ByteArray) {
        val slots = slots(keys)
        RandomAccessFile(target, "r").use { file ->
            val head = header(file, padId)
            check(head.slots == slots) { "Pad storage slot count mismatch" }
            for (slot in 0 until 2 * slots) if (!zero(keys, slot * KEY, KEY)) decrypt(file, head, keys, slot).fill(0)
        }
    }

    private fun decrypt(file: RandomAccessFile, head: Header, keys: ByteArray, slot: Int): ByteArray {
        val record = ByteArray(RECORD)
        file.seek(HEADER + slot.toLong() * RECORD); file.readFully(record)
        val key = keys.copyOfRange(slot * KEY, slot * KEY + KEY)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, record, 0, 12))
            cipher.updateAAD(aad(head.nonce, slot))
            return cipher.doFinal(record, 12, RECORD - 12)
        } finally { key.fill(0) }
    }

    /** One slot's STRIDE bytes. A zeroed key means the slot was used or retired. */
    fun slot(target: File, padId: String, keys: ByteArray, role: Char, counter: Long): ByteArray {
        val slots = slots(keys)
        val slot = index(slots, role, counter)
        require(!zero(keys, slot * KEY, KEY)) { "Pad slot retired" }
        return RandomAccessFile(target, "r").use { file ->
            val head = header(file, padId); check(head.slots == slots) { "Pad storage slot count mismatch" }
            decrypt(file, head, keys, slot)
        }
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
