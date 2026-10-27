package org.example.otppager

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import java.util.Collections

/** Complete bounded discovery and authenticated reads. Capture authority still belongs to the live ticket. */
internal object RetainedGuardInventory {
    data class Snapshot(val roots: Set<String>, val guards: List<RetirementEvidenceCodec.Guard>)
    private data class Sources(val names: Set<String>, val aliases: Set<String>)
    private val guardName = "(chat-index-claims|(?:station-v2|chat-[a-f0-9]{20})-pads)"
    private val guardFile = Regex("^$guardName\\.bin(?:\\.(?:bak|new))?$")
    private val guardKey = Regex("^otp-pager-$guardName(?:-device)?$")
    private fun sources(context: Context): Sources {
        var count = 0
        val names = sortedSetOf<String>(); val aliases = sortedSetOf<String>()
        Files.newDirectoryStream(context.noBackupFilesDir.toPath()).use { stream ->
            for (file in stream) { check(++count <= ContactInventory.MAX_ENTRIES); names.add(file.fileName.toString()) }
        }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; val all = keys.aliases()
        while (all.hasMoreElements()) { check(++count <= ContactInventory.MAX_ENTRIES); aliases.add(all.nextElement()) }
        return Sources(names, aliases)
    }
    private fun guardNamespaces(source: Sources): Set<String> {
        val result = sortedSetOf<String>()
        source.names.forEach { guardFile.matchEntire(it)?.groupValues?.get(1)?.let(result::add) }
        source.aliases.forEach { alias -> guardKey.matchEntire(alias)?.let {
            check(!alias.endsWith("-device")) { "Unexpected retained identity key" }; result.add(it.groupValues[1])
        } }
        check("chat-index-claims" in result) { "Missing claims cannot be inferred empty" }
        return result
    }
    fun captureFresh(context: Context, initialRoots: Set<String>): Snapshot = synchronized(VaultLifecycle.lock) {
        check(GlobalRetirement.isFrozen(context.noBackupFilesDir))
        check(initialRoots.size <= ContactInventory.MAX_ENTRIES && initialRoots.all(VaultLifecycle::isContact))
        // Conservative bootstrap: no capture from a state whose index/key cleanup already happened.
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(keys.containsAlias("otp-pager-chat-index") && Files.isRegularFile(
            File(context.noBackupFilesDir, "chat-index.bin").toPath(), LinkOption.NOFOLLOW_LINKS))
        val before = sources(context); val namespaces = guardNamespaces(before)
        val roots = ContactInventory.discover(before.names, before.aliases).toMutableSet().apply {
            addAll(initialRoots); add("station-v2")
        }
        val refs = mutableListOf<RetirementEvidenceCodec.Guard>(); var bytes = 0L; var claimEntries = 0; var ledgerEntries = 0
        for (namespace in namespaces) {
            val observed = RetainedGuardReader.observeFrozen(context, namespace)
            bytes += observed.ciphertextBytes; check(bytes <= RetainedGuardReader.MAX_COHORT_BYTES)
            when (val guard = observed.guard) {
                is RetainedGuardReader.Guard.Claims -> {
                    claimEntries += guard.pads.size + guard.rooms.size
                    roots.addAll(guard.pads.values); roots.addAll(guard.rooms.values)
                }
                is RetainedGuardReader.Guard.Ledger -> { ledgerEntries += guard.reservations.size; roots.add(guard.owner) }
            }
            check(claimEntries <= RetainedGuardReader.MAX_ENTRIES && ledgerEntries <= RetainedGuardReader.MAX_ENTRIES)
            check(roots.size <= ContactInventory.MAX_ENTRIES)
            refs.add(RetirementEvidenceCodec.Guard(namespace, observed.reference.sha256))
        }
        val snapshot = Snapshot(Collections.unmodifiableSet(roots.toSortedSet()), Collections.unmodifiableList(refs.toList()))
        verify(context, snapshot) // Second authenticated pass binds the observed commitments and both ownership directions.
        check(sources(context) == before) { "Inventory changed while frozen" }
        snapshot
    }
    fun verify(context: Context, snapshot: Snapshot) = synchronized(VaultLifecycle.lock) {
        check(GlobalRetirement.isFrozen(context.noBackupFilesDir))
        check(snapshot.roots.size <= ContactInventory.MAX_ENTRIES && "station-v2" in snapshot.roots && snapshot.roots.all(VaultLifecycle::isContact))
        val before = sources(context)
        check(guardNamespaces(before) == snapshot.guards.map { it.namespace }.toSet())
        check(snapshot.roots.containsAll(ContactInventory.discover(before.names, before.aliases)))
        RetainedGuardReader.readCohortFrozen(context, snapshot.guards.map {
            RetainedGuardReader.Reference(it.namespace, it.sha256)
        }, snapshot.roots)
        check(sources(context) == before) { "Inventory changed while frozen" }
    }
}
