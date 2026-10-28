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
    fun deniedRoots(directory: File): Set<String> = synchronized(lock) {
        denied.map(::File).filter { it.parentFile == directory.absoluteFile }
            .map { it.name.removeSuffix(".retired") }.filter(::isContact).toSet()
    }
    fun isRetired(directory: File, namespace: String): Boolean = synchronized(lock) {
        val marker = marker(directory, root(namespace))
        marker.absolutePath in denied || variants(marker).any { it.exists() }
    }
    fun requireActive(directory: File, namespace: String) = synchronized(lock) {
        check(!RetirementStartup.failed) { "Local retirement recovery unavailable; operation disabled" }
        check(!GlobalRetirement.isFrozen(directory)) { "All local pairings are retired; global recovery/enrollment required" }
        check(!isRetired(directory, namespace)) {
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

    enum class LocalState { ACTIVE, INCOMPLETE, COMPLETE, UNAVAILABLE }
    data class Status(val state: LocalState)
    private fun keyAliases(namespace: String) = listOf(
        "draft-key" to "otp-pager-$namespace-draft",
        "device-key" to "otp-pager-$namespace-device", "draft-device-key" to "otp-pager-$namespace-draft-device")
    private fun vaultSlots(namespace: String) = listOf("vault-files" to namespace, "draft-files" to "$namespace-draft")

    /** Observation only: never repairs a journal, creates a key, or treats a marker alone as cleanup proof. */
    fun inspect(context: Context, namespace: String): Status = synchronized(VaultLifecycle.lock) {
        require(VaultLifecycle.isContact(namespace)) { "Not an operational contact namespace" }
        if (RetirementStartup.failed) return@synchronized Status(LocalState.UNAVAILABLE)
        val directory = context.noBackupFilesDir
        try {
            val names = checkNotNull(directory.list()) { "Retirement directory unavailable" }.toSet()
            if (!VaultLifecycle.isRetired(directory, namespace)) return@synchronized Status(LocalState.ACTIVE)
            val marker = VaultLifecycle.marker(directory, namespace)
            val cleanMarker = marker.isFile && marker.length() <= 128 &&
                VaultLifecycle.variants(marker).drop(1).none { it.exists() } &&
                marker.readText(Charsets.US_ASCII) == "OTP Pager retirement v1\ncomplete\n"
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val keysGone = keyAliases(namespace).none { keys.containsAlias(it.second) } &&
                VaultKeys.all(namespace, keys.aliases().toList()).isEmpty() // Every rotated vault-key generation.
            val filesGone = vaultSlots(namespace).all { (_, slot) ->
                VaultLifecycle.variants(File(directory, "$slot.bin")).none { it.name in names }
            }
            Status(if (cleanMarker && keysGone && filesGone) LocalState.COMPLETE else LocalState.INCOMPLETE)
        } catch (_: Exception) { Status(LocalState.UNAVAILABLE) }
    }

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

    fun retireContact(context: Context, namespace: String): Result {
        require(VaultLifecycle.isContact(namespace)) { "Not an operational contact namespace" }
        val directory = context.noBackupFilesDir
        // Disable new work before dispatching cancellation; cleanup never waits on socket/USB close.
        synchronized(VaultLifecycle.lock) {
            VaultLifecycle.deny(directory, namespace)
            StationEngine.invalidateRetired(VaultLifecycle.marker(directory, namespace).absolutePath)
        }
        ContactOperations.cancel(VaultLifecycle.marker(directory, namespace).absolutePath)
        return synchronized(VaultLifecycle.lock) { retireLocked(directory, namespace) }
    }
    private fun retireLocked(directory: File, namespace: String): Result {
        val marker = AtomicFile(VaultLifecycle.marker(directory, namespace))
        return perform(namespace, object : Operations {
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
                    // Older Android AtomicFile implementations do not know the newer .new variant.
                    VaultLifecycle.variants(base).forEach { if (it.exists()) check(it.delete()) { "Vault file deletion incomplete" } }
                    check(VaultLifecycle.variants(base).none { it.exists() }) { "Vault file deletion incomplete" }
                }
                // Pad ledgers and global reuse claims deliberately survive: identifiers/counters only.
                return listOf("vault-keys" to { VaultKeys.deleteAll(namespace) }) +
                    keyAliases(namespace).map { (label, alias) -> label to { deleteKey(alias) } } +
                    vaultSlots(namespace).map { (label, slot) -> label to { deleteFiles(slot) } } +
                    listOf("directory-sync" to { VaultLifecycle.syncDirectory(directory) })
            }
        })
    }

    /** Derive targets from validated filenames, never from a readable contact index or marker payload. */
    fun recoverPending(context: Context): List<Result> {
        val directory = context.noBackupFilesDir
        val targets = checkNotNull(directory.listFiles()) { "Retirement directory unavailable" }.mapNotNull { file ->
            val name = file.name.removeSuffix(".bak").removeSuffix(".new")
            if (!name.endsWith(".retired")) null else name.removeSuffix(".retired").takeIf(VaultLifecycle::isContact)
        }.distinct().sorted()
        // Recheck even a completed marker: restored ciphertext must remain disabled and be cleaned again.
        return targets.map { retireContact(context, it) }
    }
}
