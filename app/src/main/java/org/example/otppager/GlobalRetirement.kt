package org.example.otppager

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Global local-only terminal state. No active wipe trigger or unfreeze/enrollment API. */
internal object GlobalRetirement {
    const val JOURNAL = "global-retirement-v1.json"
    private const val MAX_BYTES = 256 * 1024
    private val denied = mutableSetOf<String>()
    private val known = mutableMapOf<String, MutableSet<String>>()
    private val captureAttempted = mutableSetOf<String>()
    private val running = mutableMapOf<String, Future<GlobalRetirementMachine.Result>>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pager-retire-all").apply { isDaemon = true } }
    fun marker(directory: File) = File(directory, JOURNAL)
    fun isFrozen(directory: File): Boolean = synchronized(VaultLifecycle.lock) {
        // An in-process freeze latch always wins, including during a wipe of an ACTIVE epoch.
        if (directory.absolutePath in denied) return@synchronized true
        val lifecycle = hasLegacyIntent(directory) || RetirementEvidence.presence(directory) != RetirementEvidence.Presence.NONE ||
            EnrollmentStaging.presence(directory) != EnrollmentStaging.Presence.NONE
        // Lifecycle records stay forever once an epoch is ACTIVE, so only the lineage can unfreeze.
        // Without a manifest file this is exactly the previous presence rule.
        lifecycle && EpochState.resolveIfManifest(directory) !is EpochLineage.State.Active
    }
    internal fun hasLegacyIntent(directory: File): Boolean = synchronized(VaultLifecycle.lock) {
        directory.absolutePath in denied || VaultLifecycle.variants(marker(directory)).any {
            java.nio.file.Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }
    }
    enum class LocalState { NOT_FROZEN, INCOMPLETE, COMPLETE, UNAVAILABLE }
    data class Status(val state: LocalState)

    /** Observation only. A completed journal is evidence to check, never proof by itself. */
    fun inspect(context: Context): Status = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir.absoluteFile
        try {
            check(directory.isDirectory && directory.list() != null)
            if (!isFrozen(directory)) return@synchronized Status(LocalState.NOT_FROZEN)
            if (RetirementStartup.failed) return@synchronized Status(LocalState.UNAVAILABLE)
            when (val scope = EpochState.wipeScope(directory, requested = false)) {
                EpochState.WipeScope.Unknown -> return@synchronized Status(LocalState.UNAVAILABLE)
                is EpochState.WipeScope.Epoch -> return@synchronized inspectEpoch(context, scope)
                EpochState.WipeScope.Legacy -> Unit
            }
            var sealedRoots: Set<String>? = null
            when (RetirementEvidence.presence(directory)) {
                RetirementEvidence.Presence.UNAVAILABLE -> return@synchronized Status(LocalState.UNAVAILABLE)
                RetirementEvidence.Presence.PRESENT -> {
                    val evidence = RetirementEvidence.read(directory)
                    if (evidence.phase != RetirementEvidenceCodec.Phase.INTENT) {
                        RetirementEvidence.verifyFrozen(context)
                        sealedRoots = evidence.roots
                    }
                }
                RetirementEvidence.Presence.NONE -> Unit
            }
            val previous = read(directory)
            if (sealedRoots != null && previous.roots != sealedRoots) return@synchronized Status(LocalState.UNAVAILABLE)
            val marker = marker(directory)
            val clean = previous.usable && marker.isFile &&
                VaultLifecycle.variants(marker).drop(1).none { it.exists() } &&
                JSONObject(marker.readText(Charsets.UTF_8)).getString("state") == "complete"
            if (!clean || "station-v2" !in previous.roots) return@synchronized Status(LocalState.INCOMPLETE)
            val scope = ContactInventory.scanBestEffort(context)
            if (!scope.complete) return@synchronized Status(LocalState.UNAVAILABLE)
            if (!previous.roots.containsAll(scope.roots)) return@synchronized Status(LocalState.INCOMPLETE)
            for (root in previous.roots) {
                when (VaultRetirement.inspect(context, root).state) {
                    VaultRetirement.LocalState.UNAVAILABLE -> return@synchronized Status(LocalState.UNAVAILABLE)
                    VaultRetirement.LocalState.COMPLETE -> Unit
                    else -> return@synchronized Status(LocalState.INCOMPLETE)
                }
            }
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val metadataGone = listOf("otp-pager-chat-index", "otp-pager-chat-index-device").none(keys::containsAlias) &&
                VaultLifecycle.variants(File(directory, "chat-index.bin")).none { it.exists() }
            val notifications = context.getSystemService(android.app.NotificationManager::class.java)
            Status(if (metadataGone && !ReceiveService.enabled(context) && notifications.activeNotifications.isEmpty())
                LocalState.COMPLETE else LocalState.INCOMPLETE)
        } catch (_: Exception) { Status(LocalState.UNAVAILABLE) }
    }

    /** Recovery controls may retry an existing terminal intent, never initiate destruction. */
    fun retryExisting(context: Context): Future<GlobalRetirementMachine.Result> {
        check(hasLegacyIntent(context.noBackupFilesDir) || RetirementEvidence.probe(context.noBackupFilesDir) == RetirementEvidence.Presence.PRESENT ||
            EnrollmentStaging.probe(context.noBackupFilesDir) == EnrollmentStaging.Presence.PRESENT) {
            "No existing global retirement to recover"
        }
        return submit(context)
    }
    private fun freeze(context: Context): Set<String> {
        val directory = context.noBackupFilesDir.absoluteFile
        synchronized(VaultLifecycle.lock) {
            denied.add(directory.path)
            NfcSetupExchange.clear()
            known.getOrPut(directory.path) { mutableSetOf() }.apply {
                add("station-v2") // Retire the reserved default even if it currently has no artifacts.
                addAll(VaultLifecycle.deniedRoots(directory))
                addAll(StationEngine.invalidateDirectory(directory))
            }
        }
        // Close callbacks are dispatched outside the lifecycle lock, never awaited.
        val leases = ContactOperations.cancelDirectory(directory)
        return synchronized(VaultLifecycle.lock) {
            known.getValue(directory.path).apply { addAll(leases) }.toSet()
        }
    }
    /**
     * Freeze latch only, without starting cleanup. Exclusive restart fixtures use it to
     * simulate a crash after durable wipe intent; production wipes always use [submit].
     */
    internal fun latchOnlyForRestartFixture(context: Context): Set<String> = freeze(context.applicationContext)
    /** Freeze immediately; cleanup never queues behind the station's network worker. */
    fun submit(context: Context): Future<GlobalRetirementMachine.Result> {
        val app = context.applicationContext
        freeze(app)
        return synchronized(VaultLifecycle.lock) {
            val directory = app.noBackupFilesDir.absolutePath
            val existing = running[directory]
            if (existing != null && !existing.isDone) existing else {
                val firstAttempt = captureAttempted.add(directory)
                val task = executor.submit<GlobalRetirementMachine.Result> {
                    if (firstAttempt) captureInitialEvidence(app)
                    recover(app, requested = true).also { RetirementStartup.recordGlobal(app, it) }
                }
                // Closing a screen or cancelling a result wait must never cancel local destruction.
                val result = object : Future<GlobalRetirementMachine.Result> by task {
                    override fun cancel(mayInterruptIfRunning: Boolean) = false
                }
                running[directory] = result
                result
            }
        }
    }
    private fun captureInitialEvidence(context: Context) {
        val directory = context.noBackupFilesDir.absoluteFile
        val diagDetail = try { EpochState.detail(directory) } catch (e: Exception) { null }
        android.util.Log.e("OTPDIAG", "capture detail state=${diagDetail?.state} last=${diagDetail?.lastEpoch?.epoch} chain=${diagDetail?.chain?.size} obs=${try { EpochState.observe(directory).let { "unreadable=${it.unreadable} t=${it.terminals.size} s=${it.stages.size} m=${it.manifests.size}" } } catch (e: Exception) { e.toString() }}")
        when (val scope = try { EpochState.wipeScope(directory, requested = true) } catch (e: Exception) { android.util.Log.e("OTPDIAG", "wipeScope threw", e); EpochState.WipeScope.Unknown }) {
            EpochState.WipeScope.Legacy -> Unit
            EpochState.WipeScope.Unknown -> return // Destruction proceeds; no provenance can be captured.
            is EpochState.WipeScope.Epoch -> {
                if (scope.terminal != null) return // Existing evidence is never replaced or recaptured.
                var ticket: EpochEvidence.Ticket? = null
                try {
                    ticket = EpochEvidence.beginFresh(directory, scope.manifest.epoch)
                    EpochEvidence.seal(context, ticket, scope.manifest, scope.priorRoots)
                } catch (e: Exception) {
                    android.util.Log.e("OTPDIAG", "epoch capture failed", e)
                } finally { EpochEvidence.closeCapture(directory) }
                return
            }
        }
        var ticket: RetirementEvidence.Ticket? = null
        try {
            ticket = RetirementEvidence.beginFresh(context)
            val roots = synchronized(VaultLifecycle.lock) { known[context.noBackupFilesDir.absolutePath]?.toSet() ?: emptySet() }
            val snapshot = RetainedGuardInventory.captureFresh(context, roots)
            RetirementEvidence.seal(ticket, snapshot)
        } catch (_: Exception) {
            // No evidence failure, absent registry or key-provider error may prevent local cleanup.
        } finally { ticket?.let(RetirementEvidence::close) }
    }
    /** Only startup recovery of an existing global intent, or the explicit internal submit path, calls this. */
    fun recover(context: Context, requested: Boolean = false): GlobalRetirementMachine.Result {
        val directory = context.noBackupFilesDir.absoluteFile
        return when (val scope = try { EpochState.wipeScope(directory, requested) } catch (e: Exception) { android.util.Log.e("OTPDIAG", "recover wipeScope threw", e); EpochState.WipeScope.Unknown }.also { android.util.Log.e("OTPDIAG", "recover scope=$it requested=$requested") }) {
            EpochState.WipeScope.Legacy -> recoverLegacy(context)
            is EpochState.WipeScope.Epoch -> recoverEpoch(context, scope)
            EpochState.WipeScope.Unknown ->
                if (requested) recoverUnknown(context)
                // Boot never destroys a possibly ACTIVE epoch without durable intent; stay frozen.
                else GlobalRetirementMachine.Result(emptySet(), false, false, false, listOf("lineage-unavailable"))
        }
    }
    private fun recoverLegacy(context: Context): GlobalRetirementMachine.Result {
        val directory = context.noBackupFilesDir.absoluteFile
        RetirementEvidence.retireCaptureWindow(directory)
        try { EnrollmentStaging.recoverInterrupted(context) } catch (_: Exception) { /* Keep blocked; old local cleanup still proceeds. */ }
        try {
            val evidence = RetirementEvidence.read(directory)
            synchronized(VaultLifecycle.lock) { known.getOrPut(directory.path) { mutableSetOf() }.addAll(evidence.roots) }
        } catch (_: Exception) { /* Invalid/missing evidence blocks enrollment, not best-effort local cleanup. */ }
        val result = GlobalRetirementMachine.perform(object : GlobalRetirementMachine.Operations {
            override fun freeze() = freeze(context)
            override fun previous(): GlobalRetirementMachine.Previous = synchronized(VaultLifecycle.lock) { read(directory) }
            override fun write(roots: Set<String>, complete: Boolean) = synchronized(VaultLifecycle.lock) { write(directory, roots, complete) }
            override fun discover() = ContactInventory.scanBestEffort(context)
            override fun retire(root: String) = VaultRetirement.retireContact(context, root).completed
            override fun metadata(): List<Pair<String, () -> Unit>> = listOf(
                "index-key" to { deleteKey("otp-pager-chat-index") },
                "index-device-key" to { deleteKey("otp-pager-chat-index-device") },
                "index-files" to { deleteFiles(directory, "chat-index") },
                "receiving-preference" to {
                    check(context.getSharedPreferences("receiving", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit())
                },
                "receiving-service" to { context.stopService(Intent(context, ReceiveService::class.java)); Unit },
                "notifications" to { context.getSystemService(android.app.NotificationManager::class.java).cancelAll() },
                "global-directory-sync" to { VaultLifecycle.syncDirectory(directory) }
            )
        })
        return result
    }
    private fun deviceMetadata(context: Context, directory: File): List<Pair<String, () -> Unit>> = listOf(
        "receiving-preference" to {
            check(context.getSharedPreferences("receiving", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit())
        },
        "receiving-service" to { context.stopService(Intent(context, ReceiveService::class.java)); Unit },
        "notifications" to { context.getSystemService(android.app.NotificationManager::class.java).cancelAll() },
        "global-directory-sync" to { VaultLifecycle.syncDirectory(directory) }
    )
    /** Wipe of an ACTIVE epoch: its listed and discovered roots and its index; every cohort/record stays. */
    private fun recoverEpoch(context: Context, scope: EpochState.WipeScope.Epoch): GlobalRetirementMachine.Result {
        val directory = context.noBackupFilesDir.absoluteFile
        val manifest = scope.manifest
        EpochEvidence.closeCapture(directory)
        try { EnrollmentStaging.recoverInterrupted(context) } catch (_: Exception) { /* Keep blocked; cleanup still proceeds. */ }
        fun terminal() = try { EpochEvidence.readAll(directory).singleOrNull { it.epoch == manifest.epoch } } catch (_: Exception) { null }
        fun epochRoots(found: Set<String>) = found.filter { it != "station-v2" && it !in scope.priorRoots }.toSet()
        return GlobalRetirementMachine.perform(object : GlobalRetirementMachine.Operations {
            override fun freeze() = epochRoots(freeze(context)) + manifest.roots
            override fun previous(): GlobalRetirementMachine.Previous {
                val record = terminal()
                return GlobalRetirementMachine.Previous(record?.roots ?: emptySet(), usable = true)
            }
            // The OTPTERM2 record is the durable intent; the listed manifest already holds every root.
            override fun write(roots: Set<String>, complete: Boolean) {
                try { android.util.Log.e("OTPDIAG", "write terminals=" + EpochEvidence.readAll(directory).map { "${it.epoch.take(6)}:${it.phase}" }) } catch (e: Exception) { android.util.Log.e("OTPDIAG", "write readAll threw", e) }
                checkNotNull(terminal()) { "No durable epoch wipe intent" } }
            override fun discover() = ContactInventory.scanBestEffort(context).let { it.copy(roots = epochRoots(it.roots)) }
            override fun retire(root: String) = VaultRetirement.retireContact(context, root).completed
            override fun metadata(): List<Pair<String, () -> Unit>> = listOf(
                "index-key" to { deleteKey("otp-pager-${manifest.index}") },
                "index-device-key" to { deleteKey("otp-pager-${manifest.index}-device") },
                "index-files" to { deleteFiles(directory, manifest.index) }
            ) + deviceMetadata(context, directory)
        })
    }
    /** Explicit in-process request with an unreadable lineage: destroy every discoverable contact and index. */
    private fun recoverUnknown(context: Context): GlobalRetirementMachine.Result {
        val directory = context.noBackupFilesDir.absoluteFile
        val indexes = (directory.list()?.mapNotNull { Regex("^(enr-[a-f0-9]{20})\\.bin(?:\\.(?:bak|new))?$").matchEntire(it)?.groupValues?.get(1) }
            ?: emptyList()).toSet()
        return GlobalRetirementMachine.perform(object : GlobalRetirementMachine.Operations {
            override fun freeze() = freeze(context)
            override fun previous() = GlobalRetirementMachine.Previous(emptySet(), usable = true)
            override fun write(roots: Set<String>, complete: Boolean) = error("No durable record for an unreadable lineage")
            override fun discover() = ContactInventory.scanBestEffort(context)
            override fun retire(root: String) = VaultRetirement.retireContact(context, root).completed
            override fun metadata(): List<Pair<String, () -> Unit>> = indexes.flatMap { index -> listOf(
                "index-key:$index" to { deleteKey("otp-pager-$index") },
                "index-files:$index" to { deleteFiles(directory, index) }) } + deviceMetadata(context, directory)
        })
    }
    private fun inspectEpoch(context: Context, scope: EpochState.WipeScope.Epoch): Status {
        val directory = context.noBackupFilesDir.absoluteFile
        val record = scope.terminal ?: return Status(LocalState.INCOMPLETE)
        if (record.phase == RetirementEvidenceCodec.Phase.INTENT) return Status(LocalState.INCOMPLETE)
        EpochGuardInventory.verify(context, record)
        val found = ContactInventory.scanBestEffort(context)
        if (!found.complete) return Status(LocalState.UNAVAILABLE)
        val roots = found.roots.filter { it != "station-v2" && it !in scope.priorRoots }
        if (!record.roots.containsAll(roots)) return Status(LocalState.INCOMPLETE)
        for (root in record.roots) when (VaultRetirement.inspect(context, root).state) {
            VaultRetirement.LocalState.UNAVAILABLE -> return Status(LocalState.UNAVAILABLE)
            VaultRetirement.LocalState.COMPLETE -> Unit
            else -> return Status(LocalState.INCOMPLETE)
        }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val index = scope.manifest.index
        val metadataGone = listOf("otp-pager-$index", "otp-pager-$index-device").none(keys::containsAlias) &&
            VaultLifecycle.variants(File(directory, "$index.bin")).none { it.exists() }
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        return Status(if (metadataGone && !ReceiveService.enabled(context) && notifications.activeNotifications.isEmpty())
            LocalState.COMPLETE else LocalState.INCOMPLETE)
    }
    /** Record a verified cleanup in whichever terminal record governs the current scope. */
    fun recordCleanup(context: Context, result: GlobalRetirementMachine.Result) = synchronized(VaultLifecycle.lock) {
        when (val scope = EpochState.wipeScope(context.noBackupFilesDir.absoluteFile, requested = false)) {
            EpochState.WipeScope.Legacy -> RetirementEvidence.recordCleanup(context, result)
            is EpochState.WipeScope.Epoch -> scope.terminal?.let { EpochEvidence.recordCleanup(context, it.generation, result) }
            EpochState.WipeScope.Unknown -> Unit
        }
        Unit
    }
    /**
     * Called only by [FreshEnrollment] after the new epoch's manifest is committed and resolves
     * ACTIVE. Old owners stay rejected by their captured capability, so the in-process freeze
     * latch is released for owners constructed from now on.
     */
    internal fun reopenAfterActivation(directory: File) = synchronized(VaultLifecycle.lock) {
        val path = directory.absolutePath
        denied.remove(path); known.remove(path); captureAttempted.remove(path); running.remove(path)
        EpochState.invalidate(directory)
    }
    private fun read(directory: File): GlobalRetirementMachine.Previous {
        val roots = mutableSetOf<String>(); var usable = true
        for (file in VaultLifecycle.variants(marker(directory)).filter { it.exists() }) {
            try {
                check(file.isFile && file.length() in 1..MAX_BYTES.toLong())
                val value = JSONObject(file.readText(Charsets.UTF_8))
                check(value.length() == 3 && value.opt("version") == 1)
                check(value.getString("state") in listOf("pending", "complete"))
                val list = value.getJSONArray("roots")
                check(list.length() <= ContactInventory.MAX_ENTRIES)
                val parsed = mutableSetOf<String>()
                for (i in 0 until list.length()) {
                    val root = list.getString(i)
                    check(VaultLifecycle.isContact(root) && parsed.add(root))
                }
                roots.addAll(parsed)
            } catch (_: Exception) { usable = false }
        }
        return GlobalRetirementMachine.Previous(roots.toSet(), usable)
    }
    private fun write(directory: File, roots: Set<String>, complete: Boolean) {
        check(roots.size <= ContactInventory.MAX_ENTRIES && roots.all(VaultLifecycle::isContact))
        val bytes = JSONObject().put("version", 1).put("state", if (complete) "complete" else "pending")
            .put("roots", JSONArray(roots.sorted())).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val journal = AtomicFile(marker(directory)); val stream = journal.startWrite()
        var finished = false
        try {
            stream.write(bytes); stream.fd.sync(); journal.finishWrite(stream); finished = true
            // Cross-version AtomicFile implementations may not remove a newer .new variant.
            VaultLifecycle.variants(marker(directory)).drop(1).forEach { if (it.exists()) check(it.delete()) }
            check(journal.readFully().contentEquals(bytes))
            VaultLifecycle.syncDirectory(directory)
        } catch (failure: Exception) {
            // Once finalized, preserve the terminal file even if verification/directory sync fails.
            if (!finished) journal.failWrite(stream)
            throw failure
        }
    }
    private fun deleteKey(alias: String) {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(alias); check(!keys.containsAlias(alias))
    }
    private fun deleteFiles(directory: File, namespace: String) {
        val base = File(directory, "$namespace.bin")
        AtomicFile(base).delete()
        VaultLifecycle.variants(base).forEach { if (it.exists()) check(it.delete()) }
        check(VaultLifecycle.variants(base).none { it.exists() })
    }
}
