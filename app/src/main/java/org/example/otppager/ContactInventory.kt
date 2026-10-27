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
    private val key = Regex("^otp-pager-$ROOT(?:-(?:draft|pads))?(?:-device)?$")

    /** A partial enumeration is an error, never an empty/completed wipe-all scope. */
    fun discover(fileNames: Iterable<String>, aliases: Iterable<String>): Set<String> {
        val roots = sortedSetOf<String>(); var count = 0
        fun bounded() { check(++count <= MAX_ENTRIES) { "Contact inventory capacity exceeded; scope unavailable" } }
        for (name in fileNames) {
            bounded()
            (vault.matchEntire(name) ?: marker.matchEntire(name))?.groupValues?.get(1)?.let(roots::add)
        }
        for (alias in aliases) {
            bounded(); key.matchEntire(alias)?.groupValues?.get(1)?.let(roots::add)
        }
        return roots.toSet()
    }
    fun scan(context: Context): Set<String> = synchronized(VaultLifecycle.lock) {
        val names = mutableListOf<String>()
        Files.newDirectoryStream(context.noBackupFilesDir.toPath()).use { stream ->
            for (path in stream) {
                check(names.size < MAX_ENTRIES) { "Contact inventory capacity exceeded; scope unavailable" }
                names.add(path.fileName.toString())
            }
        }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliases = keys.aliases().asSequence().asIterable()
        discover(names, aliases)
    }
}
