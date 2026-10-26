package org.example.otppager

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore

/** One lock for local vault/identity use and terminal retirement; never hold during network I/O. */
internal object VaultLifecycle {
    val lock = Any()
    private val denied = mutableSetOf<String>()
    private val contact = Regex("^(chat-[a-f0-9]{20})(?:-.+)?$")
    fun root(namespace: String): String = when {
        namespace == "station-v2" || namespace.startsWith("station-v2-") -> "station-v2"
        else -> contact.matchEntire(namespace)?.groupValues?.get(1) ?: namespace
    }
    fun isContact(namespace: String) = namespace == "station-v2" || Regex("chat-[a-f0-9]{20}").matches(namespace)
    fun marker(directory: File, root: String) = File(directory, "$root.retired")
    fun variants(file: File) = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
    fun deny(directory: File, root: String) { denied.add(marker(directory, root).absolutePath) }
    fun requireActive(directory: File, namespace: String) = synchronized(lock) {
        val marker = marker(directory, root(namespace))
        check(marker.absolutePath !in denied && variants(marker).none { it.exists() }) {
            "This local pairing generation is retired; create a new friend pairing"
        }
    }
    fun syncDirectory(directory: File) {
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
}

/** No packet format or UI. Local prerequisite only; caller must not claim remote destruction. */
internal object VaultRetirement {
    data class Result(val namespace: String, val terminalDurable: Boolean, val completed: Boolean, val failures: List<String>)

    /** All cleanup steps are attempted, even if a marker/key/file operation fails. */
    internal interface Operations {
        fun denyInProcess()
        fun writeMarker(complete: Boolean)
        fun cleanup(): List<Pair<String, () -> Unit>>
    }
    internal fun perform(namespace: String, operations: Operations): Result {
        operations.denyInProcess()
        val failures = mutableListOf<String>()
        fun attempt(name: String, action: () -> Unit): Boolean = try { action(); true }
            catch (_: Exception) { failures.add(name); false }
        val terminal = attempt("terminal-marker") { operations.writeMarker(false) }
        for ((name, action) in operations.cleanup()) attempt(name, action)
        val complete = terminal && failures.isEmpty() && attempt("completion-marker") { operations.writeMarker(true) }
        return Result(namespace, terminal, complete, failures.toList())
    }

    fun retireContact(context: Context, namespace: String): Result = synchronized(VaultLifecycle.lock) {
        require(VaultLifecycle.isContact(namespace)) { "Not an operational contact namespace" }
        val directory = context.noBackupFilesDir
        val marker = AtomicFile(VaultLifecycle.marker(directory, namespace))
        perform(namespace, object : Operations {
            override fun denyInProcess() = VaultLifecycle.deny(directory, namespace)
            override fun writeMarker(complete: Boolean) {
                val bytes = ("OTP Pager retirement v1\n" + if (complete) "complete\n" else "pending\n").toByteArray(Charsets.US_ASCII)
                val stream = marker.startWrite()
                try {
                    stream.write(bytes); stream.fd.sync(); marker.finishWrite(stream)
                    check(marker.readFully().contentEquals(bytes)) { "Retirement marker verification failed" }
                    VaultLifecycle.syncDirectory(directory)
                } catch (failure: Exception) { marker.failWrite(stream); throw failure }
            }
            override fun cleanup(): List<Pair<String, () -> Unit>> {
                fun deleteKey(alias: String) {
                    val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    keys.deleteEntry(alias); check(!keys.containsAlias(alias)) { "Key deletion incomplete" }
                }
                fun deleteFiles(slot: String) {
                    val base = File(directory, "$slot.bin")
                    AtomicFile(base).delete()
                    check(VaultLifecycle.variants(base).none { it.exists() }) { "Vault file deletion incomplete" }
                }
                // Pad ledgers and global reuse claims deliberately survive: identifiers/counters only.
                return listOf(
                    "vault-key" to { deleteKey("otp-pager-$namespace") },
                    "draft-key" to { deleteKey("otp-pager-$namespace-draft") },
                    "device-key" to { deleteKey("otp-pager-$namespace-device") },
                    "draft-device-key" to { deleteKey("otp-pager-$namespace-draft-device") },
                    "vault-files" to { deleteFiles(namespace) },
                    "draft-files" to { deleteFiles("$namespace-draft") },
                    "directory-sync" to { VaultLifecycle.syncDirectory(directory) }
                )
            }
        })
    }

    /** Derive targets from validated filenames, never from a readable contact index or marker payload. */
    fun recoverPending(context: Context): List<Result> = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir
        val targets = checkNotNull(directory.listFiles()) { "Retirement directory unavailable" }.mapNotNull { file ->
            val name = file.name.removeSuffix(".bak").removeSuffix(".new")
            if (!name.endsWith(".retired")) null else name.removeSuffix(".retired").takeIf(VaultLifecycle::isContact)
        }.distinct().sorted()
        // Recheck even a completed marker: restored ciphertext must remain disabled and be cleaned again.
        targets.map { retireContact(context, it) }
    }
}
