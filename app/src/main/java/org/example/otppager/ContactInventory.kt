package org.example.otppager

import android.content.Context
import java.nio.file.Files
import java.security.KeyStore

/** Read-only wipe-all prerequisite: known contact artifacts, without opening vaults or the index. */
internal object ContactInventory {
    const val MAX_ENTRIES = 4096
    private const val ROOT = "(station-v2|chat-[a-f0-9]{20})"
    private val vault = Regex("^$ROOT(?:-(?:draft|pads))?\\.bin(?:\\.(?:bak|new))?$")
    private val marker = Regex("^$ROOT\\.retired(?:\\.(?:bak|new))?$")
    private val key = Regex("^otp-pager-$ROOT(?:-(?:draft|pads))?(?:-device|${VaultKeys.SUFFIX})?$")

    /** Keep known targets when either source fails; callers must not mistake partial scope for complete. */
    fun collect(fileNames: Iterable<String>, aliases: Iterable<String>): GlobalRetirementMachine.Scope {
        val roots = sortedSetOf<String>(); val failures = mutableListOf<String>(); var count = 0
        fun bounded() { check(++count <= MAX_ENTRIES) { "Contact inventory capacity exceeded" } }
        try {
            for (name in fileNames) {
                bounded()
                (vault.matchEntire(name) ?: marker.matchEntire(name))?.groupValues?.get(1)?.let(roots::add)
            }
        } catch (_: Exception) { failures.add("inventory-files") }
        try {
            for (alias in aliases) { bounded(); key.matchEntire(alias)?.groupValues?.get(1)?.let(roots::add) }
        } catch (_: Exception) { failures.add("inventory-keys") }
        return GlobalRetirementMachine.Scope(roots.toSet(), failures.isEmpty(), failures)
    }
    /** A partial enumeration is an error, never an empty/completed wipe-all scope. */
    fun discover(fileNames: Iterable<String>, aliases: Iterable<String>): Set<String> {
        val scope = collect(fileNames, aliases)
        check(scope.complete) { "Contact inventory capacity/enumeration failure; scope unavailable" }
        return scope.roots
    }
    fun scanBestEffort(context: Context): GlobalRetirementMachine.Scope = synchronized(VaultLifecycle.lock) {
        val names = mutableListOf<String>(); var fileFailure = false
        try {
            Files.newDirectoryStream(context.noBackupFilesDir.toPath()).use { stream ->
                for (path in stream) {
                    check(names.size < MAX_ENTRIES) { "Contact inventory capacity exceeded" }
                    names.add(path.fileName.toString())
                }
            }
        } catch (_: Exception) { fileFailure = true }
        val aliases = Iterable {
            sequence {
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val all = keys.aliases()
                while (all.hasMoreElements()) yield(all.nextElement())
            }.iterator()
        }
        val found = collect(names, aliases)
        found.copy(complete = found.complete && !fileFailure,
            failures = found.failures + if (fileFailure) listOf("inventory-files") else emptyList())
    }
    fun scan(context: Context): Set<String> {
        val scope = scanBestEffort(context)
        check(scope.complete) { "Contact inventory capacity/enumeration failure; scope unavailable" }
        return scope.roots
    }
}
