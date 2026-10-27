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

/** Global local-only terminal state. No active UI trigger or unfreeze/enrollment API. */
internal object GlobalRetirement {
    const val JOURNAL = "global-retirement-v1.json"
    private const val MAX_BYTES = 256 * 1024
    private val denied = mutableSetOf<String>()
    private val known = mutableMapOf<String, MutableSet<String>>()
    private val running = mutableMapOf<String, Future<GlobalRetirementMachine.Result>>()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "pager-retire-all").apply { isDaemon = true } }
    fun marker(directory: File) = File(directory, JOURNAL)
    fun isFrozen(directory: File): Boolean = synchronized(VaultLifecycle.lock) {
        directory.absolutePath in denied || VaultLifecycle.variants(marker(directory)).any { it.exists() }
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
    /** Freeze immediately; cleanup never queues behind the station's network worker. */
    fun submit(context: Context): Future<GlobalRetirementMachine.Result> {
        val app = context.applicationContext
        freeze(app)
        return synchronized(VaultLifecycle.lock) {
            val directory = app.noBackupFilesDir.absolutePath
            running[directory]?.takeIf { !it.isDone } ?: executor.submit<GlobalRetirementMachine.Result> { recover(app) }
                .also { running[directory] = it }
        }
    }
    /** Only startup recovery of an existing global intent, or the explicit internal submit path, calls this. */
    fun recover(context: Context): GlobalRetirementMachine.Result {
        val directory = context.noBackupFilesDir.absoluteFile
        return GlobalRetirementMachine.perform(object : GlobalRetirementMachine.Operations {
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
                "global-directory-sync" to { VaultLifecycle.syncDirectory(directory) }
            )
        })
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
