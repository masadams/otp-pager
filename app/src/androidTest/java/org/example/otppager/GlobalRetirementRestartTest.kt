package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exclusive host fixture, AFTER ordinary connected tests. Leaves the disposable app globally frozen. */
@RunWith(AndroidJUnit4::class)
class GlobalRetirementRestartTest {
    private fun root(n: Int) = "chat-" + n.toString().padStart(20, '0')
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun hash(file: File) = PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    private fun write(file: File, text: String) {
        FileOutputStream(file).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        VaultLifecycle.syncDirectory(context.noBackupFilesDir)
    }
    private fun awaitMain(test: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 15000; var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { ready = test() }
            if (!ready) Thread.sleep(40)
        }
        assertTrue("Global UI did not reach expected state", ready)
    }
    private fun field(activity: NetworkActivity, name: String) = NetworkActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun views(v: android.view.View): List<android.view.View> = listOf(v) +
        if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun views(a: NetworkActivity) = views(a.findViewById(android.R.id.content))
    private fun hasStatus(a: NetworkActivity, text: String) = views(a).filterIsInstance<android.widget.TextView>()
        .any { it.isShown && it.text.toString() == text }
    private fun openRecovery(): NetworkActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return instrumentation.startActivitySync(android.content.Intent(context, NetworkActivity::class.java)
            .setAction(android.content.Intent.ACTION_MAIN).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as NetworkActivity
    }
    private fun tap(a: NetworkActivity, label: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val button = views(a).filterIsInstance<android.widget.Button>().first { it.isShown && it.text.toString() == label }
            assertTrue(button.isEnabled); button.performClick()
        }
    }
    private fun capture(a: NetworkActivity, name: String) = InterfaceTest().capture(InstrumentationRegistry.getInstrumentation(), a, name, windowOnly = true)
    private fun close(a: NetworkActivity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { a.finish() }
        awaitMain { a.isDestroyed }
    }
    private fun blocked(action: () -> Unit) {
        try { action(); fail("Global freeze allowed operational work") } catch (_: IllegalStateException) {}
    }
    private fun verify(reference: JSONObject, expectedComplete: Boolean = true, observed: GlobalRetirementMachine.Result? = null) {
        assertFalse(RetirementStartup.failed)
        val result = observed ?: checkNotNull(RetirementStartup.global)
        assertEquals(result.failures.toString(), expectedComplete, result.completed)
        if (!expectedComplete) assertTrue(result.failures.any { it.contains(root(34)) })
        assertTrue(GlobalRetirement.isFrozen(context.noBackupFilesDir))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        for (n in 31..36) {
            for (slot in listOf(root(n), "${root(n)}-draft")) {
                if (expectedComplete || slot != "${root(34)}-draft")
                    assertTrue(VaultLifecycle.variants(File(context.noBackupFilesDir, "$slot.bin")).none { it.exists() })
                assertFalse(keys.containsAlias("otp-pager-$slot")); assertFalse(keys.containsAlias("otp-pager-$slot-device"))
            }
        }
        assertFalse(keys.containsAlias("otp-pager-chat-index"))
        assertFalse(keys.containsAlias("otp-pager-chat-index-device"))
        assertTrue(VaultLifecycle.variants(File(context.noBackupFilesDir, "chat-index.bin")).none { it.exists() })
        assertEquals(reference.getString("ledger"), hash(File(context.noBackupFilesDir, "${root(31)}-pads.bin")))
        assertEquals(reference.getString("claims"), hash(File(context.noBackupFilesDir, "chat-index-claims.bin")))
        assertTrue(keys.containsAlias("otp-pager-chat-index-claims"))
        assertTrue(keys.containsAlias("otp-pager-${root(31)}-pads"))
        assertFalse(ReceiveService.enabled(context))
        var notified = false
        assertFalse(ReceiveService.publishIfActive(context) { notified = true })
        assertFalse(notified)
        blocked { ChatBook(context) }
        blocked { SecureStore(context, "chat-index-claims").forget() }
        blocked { SecureStore(context, root(36)).save(JSONObject().put("version", 2)) }
        blocked { SecureStore(context, root(31)).identity().card(true) }
    }
    @Test fun globalRecoveryHandlesCorruptIndexOrphansBlockedWorkerAndLaterRestoration() {
        val phase = InstrumentationRegistry.getArguments().getString("globalRetirementPhase")
        assumeTrue("Exclusive host-driven global destruction fixture only", phase in listOf("seed", "verify", "restored", "malformed"))
        val directory = context.noBackupFilesDir
        val referenceFile = File(directory, "global-test-reference.json")
        when (phase) {
            "seed" -> {
                assertFalse(GlobalRetirement.isFrozen(directory))
                assertEquals(GlobalRetirement.LocalState.NOT_FROZEN, GlobalRetirement.inspect(context).state)
                blocked { GlobalRetirement.retryExisting(context) }
                assertFalse(GlobalRetirement.marker(directory).exists())
                SecureStore(context, "chat-index").save(JSONObject().put("version", 3).put("active", root(31))
                    .put("chats", JSONArray((31..32).map { n -> JSONObject().put("id", root(n)).put("entryId", root(n)).put("name", "Fixture $n") })))
                val book = ChatBook(context)
                val liveEngines = mutableListOf<StationEngine>()
                for (n in 31..32) {
                    val engine = book.engine(root(n)); liveEngines.add(engine)
                    engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
                    if (n == 31) engine.generatePad().fill(0)
                    SecureStore(context, root(n)).identity().card(true)
                    SecureStore(context, "${root(n)}-draft").save(JSONObject().put("version", 2).put("text", "Fixture draft"))
                }
                // Key-only and file-only contacts are absent from the index.
                DeviceIdentity("otp-pager-${root(33)}-device").card(true)
                write(File(directory, "${root(34)}-draft.bin.new"), "damaged orphan ciphertext")
                val obstruction = File(directory, "${root(34)}-draft.bin")
                check(obstruction.mkdir()); File(obstruction, "blocked").writeText("test obstruction")
                val reference = JSONObject().put("ledger", hash(File(directory, "${root(31)}-pads.bin")))
                    .put("claims", hash(File(directory, "chat-index-claims.bin")))
                write(referenceFile, reference.toString())
                // Recovery pages have no active contact vault; their index lease must still clear private UI.
                assertTrue(VaultRetirement.retireContact(context, root(32)).completed)
                book.select(root(32))
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val activity = instrumentation.startActivitySync(android.content.Intent(context, NetworkActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as NetworkActivity
                awaitMain { field(activity, "retiredChat") != null && field(activity, "busy") == false }
                write(File(directory, "chat-index.bin"), "damaged encrypted index")
                ReceiveService.enabled(context, true)
                NfcSetupExchange.publish("fixture offer".toByteArray(), android.os.SystemClock.elapsedRealtime() + 60000,
                    VaultLifecycle.marker(directory, root(31)).absolutePath)
                // Exercise the real freeze boundary, then interrupt before its cleanup executor starts.
                GlobalRetirement::class.java.getDeclaredMethod("freeze", android.content.Context::class.java)
                    .apply { isAccessible = true }.invoke(GlobalRetirement, context)
                assertTrue(liveEngines.all { it.state == null })
                assertNull(NfcSetupExchange.payload)
                awaitMain { activity.isDestroyed }
                assertNull(field(activity, "retiredChat"))
                // Power loss after global intent but before scope enumeration/key cleanup.
                write(GlobalRetirement.marker(directory), JSONObject().put("version", 1).put("state", "pending")
                    .put("roots", JSONArray().put(root(31))).toString())
                blocked { SecureStore(context, root(32)).load() }
            }
            "verify" -> {
                val reference = JSONObject(referenceFile.readText()); verify(reference, expectedComplete = false)
                val journal = GlobalRetirement.marker(directory).readBytes()
                repeat(2) { assertEquals(GlobalRetirement.LocalState.INCOMPLETE, GlobalRetirement.inspect(context).state) }
                assertArrayEquals(journal, GlobalRetirement.marker(directory).readBytes())
                val recovery = openRecovery()
                awaitMain { hasStatus(recovery, "Local cleanup is incomplete. All pairings remain disabled.") }
                assertNull(field(recovery, "book")); assertNull(field(recovery, "engine"))
                capture(recovery, "17-global-incomplete")
                // A stale offer without a registered lease must still be rejected by the HCE service.
                NfcSetupExchange.publish("late fixture offer".toByteArray(), android.os.SystemClock.elapsedRealtime() + 60000,
                    VaultLifecycle.marker(directory, root(36)).absolutePath)
                lateinit var card: NfcSetupCard
                InstrumentationRegistry.getInstrumentation().runOnMainSync { card = NfcSetupCard() }
                assertFalse(card.processCommandApdu(NfcSetupExchange.select, null).contentEquals(byteArrayOf(0x90.toByte(), 0)))
                NfcSetupExchange.clear()
                assertEquals(1, RetirementStartup.incomplete)
                File(directory, "${root(34)}-draft.bin").deleteRecursively()
                // Test-only raw alias creation simulates a restored key after a completed wipe.
                DeviceIdentity("otp-pager-${root(35)}-device").card(true)
                val workerEntered = CountDownLatch(1); val release = CountDownLatch(1)
                val closeEntered = CountDownLatch(1); val closed = CountDownLatch(1)
                val key = VaultLifecycle.marker(directory, root(35)).absolutePath
                // An unguarded test lease models an operation registered immediately before the freeze.
                val lease = ContactOperations.open(key) {}
                lease.onCancel { closeEntered.countDown(); try { release.await(45, TimeUnit.SECONDS) } finally { closed.countDown() } }
                StationRuntime.worker.execute { workerEntered.countDown(); release.await(45, TimeUnit.SECONDS) }
                try {
                    assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
                    tap(recovery, "Retry local cleanup")
                    assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
                    assertTrue(lease.cancelled)
                    awaitMain { hasStatus(recovery, "Local cleanup verified. All pairings remain disabled.") }
                    val result = checkNotNull(RetirementStartup.global)
                    assertTrue(result.failures.toString(), result.completed)
                    assertEquals(1L, release.count) // Both unrelated waits are still blocked.
                    verify(reference, observed = result)
                    assertEquals(GlobalRetirement.LocalState.COMPLETE, GlobalRetirement.inspect(context).state)
                    awaitMain {
                        val label = views(recovery).filterIsInstance<android.widget.TextView>().first { it.text.toString() == "Local recovery" }
                        val bounds = android.graphics.Rect()
                        label.getGlobalVisibleRect(bounds) && bounds.height() == label.height
                    }
                    capture(recovery, "18-global-complete")
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        views(recovery).filterIsInstance<android.widget.ScrollView>().first().fullScroll(android.view.View.FOCUS_DOWN)
                    }
                    awaitMain {
                        val button = views(recovery).filterIsInstance<android.widget.Button>().first { it.text.toString() == "Check status" }
                        val bounds = android.graphics.Rect()
                        button.getGlobalVisibleRect(bounds) && bounds.height() == button.height
                    }
                    capture(recovery, "19-global-actions")
                    close(recovery)
                    // Reopening the frozen app does not queue status behind the blocked message worker.
                    val reopened = openRecovery()
                    awaitMain { hasStatus(reopened, "Local cleanup verified. All pairings remain disabled.") }
                    assertEquals(1L, release.count)
                    tap(reopened, "Check status")
                    awaitMain { hasStatus(reopened, "Local cleanup verified. All pairings remain disabled.") }
                    close(reopened)
                    assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("otp-pager-${root(35)}-device"))
                } finally { release.countDown(); closed.await(5, TimeUnit.SECONDS); lease.close() }
                // A completed journal cannot hide restored index keys or an unknown contact root.
                DeviceIdentity("otp-pager-chat-index-device").card(true)
                DeviceIdentity("otp-pager-${root(37)}-device").card(true)
                assertEquals(GlobalRetirement.LocalState.INCOMPLETE, GlobalRetirement.inspect(context).state)
                val recoveryFuture = GlobalRetirement.retryExisting(context)
                assertFalse(recoveryFuture.cancel(true))
                val recovered = recoveryFuture.get(20, TimeUnit.SECONDS)
                assertTrue(recovered.completed); assertTrue(root(37) in recovered.roots)
                assertEquals(GlobalRetirement.LocalState.COMPLETE, GlobalRetirement.inspect(context).state)
                // A later restore must be swept again by startup, even though the journal says complete.
                write(File(directory, "${root(31)}.bin.new"), "restored ciphertext")
                DeviceIdentity("otp-pager-${root(36)}-device").card(true)
                assertEquals(GlobalRetirement.LocalState.INCOMPLETE, GlobalRetirement.inspect(context).state)
                assertEquals("restored ciphertext", File(directory, "${root(31)}.bin.new").readText())
            }
            "restored" -> {
                verify(JSONObject(referenceFile.readText()))
                write(File(GlobalRetirement.marker(directory).path + ".new"), "unknown partial journal")
            }
            "malformed" -> {
                assertFalse(RetirementStartup.failed)
                assertFalse(checkNotNull(RetirementStartup.global).completed)
                assertTrue(GlobalRetirement.isFrozen(directory))
                assertEquals("unknown partial journal", File(GlobalRetirement.marker(directory).path + ".new").readText())
                blocked { ChatBook(context) }
                assertEquals(GlobalRetirement.LocalState.INCOMPLETE, GlobalRetirement.inspect(context).state)
                val a = openRecovery()
                awaitMain { hasStatus(a, "Local cleanup is incomplete. All pairings remain disabled.") }
                tap(a, "Retry local cleanup")
                awaitMain { hasStatus(a, "Local cleanup is incomplete. All pairings remain disabled.") }
                assertEquals("unknown partial journal", File(GlobalRetirement.marker(directory).path + ".new").readText())
                capture(a, "20-global-malformed")
                close(a)
            }
        }
    }
}
