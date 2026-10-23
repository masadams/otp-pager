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
class SecureStore(context: Context, namespace: String = "station-v2") {
    private val slot = namespace.also { require(Regex("[a-z0-9-]{1,40}").matches(it)) }
    private val base = File(context.noBackupFilesDir, "$slot.bin")
    private val file = AtomicFile(base)
    fun sibling(suffix: String): SecureStore = SecureStore(contextRef, "$slot-$suffix")
    private val contextRef = context.applicationContext
    private val alias = "otp-pager-$slot"
    private val aad = "OTP Pager local vault v2".toByteArray()
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as SecretKey?
        if (existing != null) return existing
        check(create) { "Local encryption key unavailable; forget pairing and pair again" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun load(): JSONObject? {
        if (!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(!keys.containsAlias(alias)) { "Vault file missing; saved key exists. Refusing to reset counters." }
            return null
        }
        // Permission errors, a damaged path, and interrupted writes are not a new pairing.
        val bytes = file.readFully()
        require(bytes.size >= 29 && bytes[0] == 2.toByte()) { "Local vault damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        val clear = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try { JSONObject(String(clear, Charsets.UTF_8)).also { check(it.getInt("version") == 2) } }
        finally { clear.fill(0) }
    }
    fun save(state: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
        val clear = state.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { byteArrayOf(2) + cipher.iv + cipher.doFinal(clear) } finally { clear.fill(0) }
        val stream = file.startWrite()
        try { stream.write(encrypted); stream.fd.sync(); file.finishWrite(stream)
            check(base.isFile && base.readBytes().contentEquals(encrypted)) { "Vault commit could not be verified" }
            val directory = android.system.Os.open(base.parentFile!!.path, android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_DIRECTORY, 0)
            try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) } }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun forget() {
        // Invalidate the key first: if the process dies, old data cannot be reopened.
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(alias)
        file.delete()
    }
}
