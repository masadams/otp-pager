package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VaultRetirementAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun namespace() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Retired generation was usable") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("retired")) }
    }
    private fun cleanup(namespace: String) {
        for (slot in listOf(namespace, "$namespace-draft", "$namespace-pads")) SecureStore(context, slot).forget()
        VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, namespace)).forEach { it.delete() }
    }
    @Test fun retirementDestroysContactKeysAndDraftButKeepsReuseMetadataAndOtherContacts() {
        val root = namespace(); val other = namespace()
        val store = SecureStore(context, root); val neighbor = SecureStore(context, other)
        try {
            val engine = StationEngine(store)
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            engine.generatePad().fill(0)
            val staleState = JSONObject(engine.state.toString())
            val staleIdentity = store.identity(); staleIdentity.card(true)
            val draft = store.sibling("draft")
            draft.save(JSONObject().put("version", 2).put("text", "Sensitive draft"))
            neighbor.save(JSONObject().put("version", 2).put("text", "Other contact"))
            val ledger = File(context.noBackupFilesDir, "$root-pads.bin")
            val ledgerBytes = ledger.readBytes()
            for (slot in listOf(root, "$root-draft")) {
                val file = File(context.noBackupFilesDir, "$slot.bin")
                for (suffix in listOf(".bak", ".new")) File(file.path + suffix).writeBytes(file.readBytes())
            }
            val result = VaultRetirement.retireContact(context, root)
            assertTrue(result.failures.toString(), result.completed); assertTrue(result.terminalDurable)
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in listOf("otp-pager-$root", "otp-pager-$root-draft", "otp-pager-$root-device"))
                assertFalse(keys.containsAlias(alias))
            for (slot in listOf(root, "$root-draft"))
                assertTrue(VaultLifecycle.variants(File(context.noBackupFilesDir, "$slot.bin")).none { it.exists() })
            assertArrayEquals(ledgerBytes, ledger.readBytes())
            assertEquals("Other contact", neighbor.load()!!.getString("text"))
            rejected { store.load() }; rejected { SecureStore(context, root).save(staleState) }
            rejected { draft.save(JSONObject().put("version", 2)) }
            // Direct constructors cannot bypass a contact's marker through a child namespace.
            rejected { SecureStore(context, "$root-draft").save(JSONObject().put("version", 2)) }
            rejected { staleIdentity.card(true) }; rejected { staleIdentity.sign(byteArrayOf(1)) }
            rejected { engine.send("Stale engine") }; rejected { engine.view() }; rejected { engine.pairingInvite() }
            assertTrue(VaultRetirement.retireContact(context, root).completed)
        } finally { cleanup(root); cleanup(other) }
    }
    @Test fun malformedAndInterruptedMarkersBlockAccessAndRecoverWithoutAContactIndex() {
        val root = namespace(); val store = SecureStore(context, root)
        try {
            store.save(JSONObject().put("version", 2).put("text", "Old encrypted snapshot"))
            val oldBytes = File(context.noBackupFilesDir, "$root.bin").readBytes()
            // Interrupted AtomicFile intent: even an invalid .new is terminal, never a fresh vault.
            val marker = VaultLifecycle.marker(context.noBackupFilesDir, root)
            File(marker.path + ".new").writeText("truncated")
            rejected { store.load() }; rejected { store.identity().card(true) }
            val recovered = VaultRetirement.recoverPending(context).single { it.namespace == root }
            assertTrue(recovered.failures.toString(), recovered.completed)
            // Restoring an old encrypted file cannot reopen the terminal generation.
            File(context.noBackupFilesDir, "$root.bin").writeBytes(oldBytes)
            rejected { SecureStore(context, root).load() }
            assertTrue(VaultRetirement.recoverPending(context).single { it.namespace == root }.completed)
            assertFalse(File(context.noBackupFilesDir, "$root.bin").exists())
        } finally { cleanup(root) }
    }
    @Test fun queuedWriterCannotRecreateAKeyAfterTerminalCleanup() {
        val root = namespace(); val store = SecureStore(context, root)
        val worker = Executors.newSingleThreadExecutor()
        val entering = CountDownLatch(1)
        try {
            store.save(JSONObject().put("version", 2))
            val future = synchronized(VaultLifecycle.lock) {
                val submitted = worker.submit<Boolean> {
                    entering.countDown()
                    try { store.save(JSONObject().put("version", 2)); false }
                    catch (e: IllegalStateException) { e.message!!.contains("retired") }
                }
                assertTrue(entering.await(5, TimeUnit.SECONDS))
                assertTrue(VaultRetirement.retireContact(context, root).completed)
                submitted
            }
            assertTrue(future.get(5, TimeUnit.SECONDS))
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("otp-pager-$root"))
        } finally { worker.shutdownNow(); cleanup(root) }
    }
}
