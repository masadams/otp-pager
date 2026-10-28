package org.example.otppager

import android.app.AlertDialog
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RetirementRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private fun cleanup(id: String) {
        listOf(id, "$id-draft", "$id-pads").forEach { slot ->
            SecureStore(context, slot).forget()
            VaultLifecycle.variants(File(context.noBackupFilesDir, "$slot.bin")).forEach { it.delete() }
        }
        VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, id)).forEach { it.delete() }
    }
    private fun field(a: NetworkActivity, name: String) = NetworkActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(a)
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun views(a: NetworkActivity) = views(a.findViewById(android.R.id.content))
    private fun await(test: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000; var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { ready = test() }; if (!ready) Thread.sleep(40)
        }
        assertTrue("Recovery screen did not reach expected state", ready)
    }
    private fun tap(a: NetworkActivity, label: String) {
        instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.text.toString() == label }.performClick() }
    }
    @Test fun orphanScanDoesNotDecryptDamagedVaultsOrDependOnAnIndex() {
        val id = root(); val keyOnly = root()
        try {
            File(context.noBackupFilesDir, "$id-draft.bin.new").writeText("damaged encrypted data")
            DeviceIdentity("otp-pager-$keyOnly-device").card(true)
            val discovered = ContactInventory.scan(context)
            assertTrue(id in discovered); assertTrue(keyOnly in discovered)
            assertEquals("damaged encrypted data", File(context.noBackupFilesDir, "$id-draft.bin.new").readText())
            assertFalse(VaultLifecycle.isRetired(context.noBackupFilesDir, id))
        } finally { cleanup(id); cleanup(keyOnly) }
    }
    @Test fun inspectionNeverRepairsOrTrustsCompletionMarkerWithoutCheckingKeysAndFiles() {
        val id = root(); val store = SecureStore(context, id)
        val badDirectory = File(context.cacheDir, "bad-status-" + UUID.randomUUID())
        try {
            StationEngine(store).configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val bytes = File(context.noBackupFilesDir, "$id.bin").readBytes()
            assertEquals(VaultRetirement.LocalState.ACTIVE, VaultRetirement.inspect(context, id).state)
            assertArrayEquals(bytes, File(context.noBackupFilesDir, "$id.bin").readBytes())
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            val marker = VaultLifecycle.marker(context.noBackupFilesDir, id); val originalMarker = marker.readBytes()
            assertEquals(VaultRetirement.LocalState.COMPLETE, VaultRetirement.inspect(context, id).state)
            val restored = File(context.noBackupFilesDir, "$id.bin.new"); restored.writeBytes(bytes)
            repeat(2) { assertEquals(VaultRetirement.LocalState.INCOMPLETE, VaultRetirement.inspect(context, id).state) }
            assertArrayEquals(bytes, restored.readBytes()); assertArrayEquals(originalMarker, marker.readBytes())
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            // Test-only unguarded identity constructor simulates a restored/stale signing alias.
            DeviceIdentity("otp-pager-$id-draft-device").card(true)
            assertEquals(VaultRetirement.LocalState.INCOMPLETE, VaultRetirement.inspect(context, id).state)
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            File(marker.path + ".new").writeText("unknown journal")
            assertEquals(VaultRetirement.LocalState.INCOMPLETE, VaultRetirement.inspect(context, id).state)
            assertEquals("unknown journal", File(marker.path + ".new").readText())
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            badDirectory.writeText("not a directory")
            val unavailable = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = badDirectory }
            assertEquals(VaultRetirement.LocalState.UNAVAILABLE, VaultRetirement.inspect(unavailable, id).state)
        } finally { badDirectory.delete(); cleanup(id) }
    }
    @Test fun retiredActiveContactCanRecoverReachOtherChatsAndStartAnEmptyPhysicalPairing() {
        StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
        val index = SecureStore(context, "chat-index"); val claims = SecureStore(context, "chat-index-claims")
        val previousIndex = index.load(); val previousClaims = claims.load()
        val id = root(); val ids = mutableSetOf(id); var activity: NetworkActivity? = null
        val obstruction = File(context.noBackupFilesDir, "$id.bin")
        try {
            index.save(JSONObject().put("version", 3).put("active", id).put("chats", JSONArray()
                .put(JSONObject().put("id", id).put("entryId", id).put("name", "Alice"))))
            val book = ChatBook(context)
            // An empty non-deletable directory makes cleanup incomplete, without faulting any neighbor.
            assertTrue(obstruction.mkdir()); File(obstruction, "blocked").writeText("test obstruction")
            assertFalse(book.recoverRetiredSafelyForTest(id))
            val other = book.add("Bob", null); ids.add(other.id)
            val neighbor = book.engine(other.id)
            neighbor.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val master = Protocol.master(neighbor.state!!.getString("secret"))
            val room = try { Protocol.room(master) } finally { master.fill(0) }
            ChatDraft(SecureStore(context, "${other.id}-draft")).save(room, UUID.randomUUID().toString(), "Other contact draft")
            val neighborBytes = File(context.noBackupFilesDir, "${other.id}.bin").readBytes()
            val a = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NetworkActivity
            activity = a
            await { field(a, "busy") == false && views(a).filterIsInstance<Button>().any { it.isShown && it.contentDescription?.toString() == "Alice. Retired on this device" } }
            instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.contentDescription?.toString() == "Alice. Retired on this device" }.performClick() }
            await { views(a).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Local cleanup incomplete" } }
            instrumentation.runOnMainSync {
                val freshButton = views(a).filterIsInstance<Button>().first { it.text.toString() == "Start fresh pairing" }
                assertFalse(freshButton.isEnabled); assertTrue(freshButton.alpha < 0.5f)
                assertFalse(views(a).filterIsInstance<Button>().first { it.text.toString() == "Done · remove from Chats" }.isShown)
            }
            InterfaceTest().capture(instrumentation, a, "14-retired-incomplete", windowOnly = true)
            tap(a, "Back to chats")
            assertTrue(ChatBook(context).chats().any { it.id == id })
            instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.contentDescription?.toString()?.startsWith("Bob.") == true }.performClick() }
            await { field(a, "busy") == false && (field(a, "composer") as EditText).text.toString() == "Other contact draft" }
            instrumentation.runOnMainSync { a.onBackPressed() }
            instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.contentDescription?.toString() == "Alice. Retired on this device" }.performClick() }
            await { field(a, "busy") == false && views(a).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Local cleanup incomplete" } }
            obstruction.deleteRecursively(); tap(a, "Retry local cleanup")
            await { field(a, "busy") == false && views(a).filterIsInstance<Button>().any { it.isShown && it.text.toString() == "Start fresh pairing" && it.isEnabled } }
            InterfaceTest().capture(instrumentation, a, "15-retired-complete", windowOnly = true)
            instrumentation.runOnMainSync {
                ((field(a, "screens") as PagerScreens).retired.getChildAt(1) as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN)
            }
            await {
                listOf("Start fresh pairing", "Back to chats").all { label ->
                    val button = views(a).filterIsInstance<Button>().first { it.isShown && it.text.toString() == label }
                    val bounds = android.graphics.Rect()
                    button.getGlobalVisibleRect(bounds) && bounds.height() == button.height
                }
            }
            InterfaceTest().capture(instrumentation, a, "16-retired-actions", windowOnly = true)
            tap(a, "Start fresh pairing")
            lateinit var dialog: AlertDialog
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val dialogs = field(a, "openDialogs") as List<AlertDialog>
                dialog = dialogs.last { it.isShowing }; dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            assertEquals(id, ChatBook(context).active().id)
            tap(a, "Start fresh pairing")
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST") val dialogs = field(a, "openDialogs") as List<AlertDialog>
                dialogs.last { it.isShowing }.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            await { field(a, "busy") == false && views(a).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Pair with Alice" } }
            val fresh = ChatBook(context).active(); ids.add(fresh.id)
            assertNotEquals(id, fresh.id); assertEquals(id, fresh.entryId)
            assertNull(SecureStore(context, fresh.id).load())
            assertArrayEquals(neighborBytes, File(context.noBackupFilesDir, "${other.id}.bin").readBytes())
            assertEquals(VaultRetirement.LocalState.COMPLETE, VaultRetirement.inspect(context, id).state)
            instrumentation.runOnMainSync {
                assertEquals("", (field(a, "composer") as EditText).text.toString())
                assertEquals("", (field(a, "tokenField") as EditText).text.toString())
                assertNull((field(a, "engine") as StationEngine).state)
                assertFalse((field(a, "sendButton") as Button).isEnabled)
            }
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() }; await { a.isDestroyed } }
            StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
            // Collect even a replacement committed just before a failed UI assertion.
            try { ids.addAll(ChatBook(context).chats().map { it.id }) } catch (_: Exception) {}
            obstruction.deleteRecursively(); ids.forEach(::cleanup)
            if (previousIndex == null) index.forget() else index.save(previousIndex)
            if (previousClaims == null) claims.forget() else claims.save(previousClaims)
        }
    }
    @Test fun dismissalRequiresVerifiedCleanupAndPreservesGuards() {
        val id = root(); val indexName = "dismiss-" + UUID.randomUUID().toString().take(8)
        val index = SecureStore(context, indexName)
        val ids = mutableSetOf(id)
        val dispatch = File(context.noBackupFilesDir, "peerwipe/out-test-$id.json")
        try {
            index.save(JSONObject().put("version", 3).put("active", id).put("chats", JSONArray()
                .put(JSONObject().put("id", id).put("entryId", id).put("name", "Alice"))))
            val book = ChatBook(context, indexName, id)
            try { book.dismissRetired(id); fail("Dismissed active chat") } catch (_: IllegalStateException) {}
            val other = book.add("Bob", null); ids.add(other.id)
            val ledger = SecureStore(context, "$id-pads")
            val padId = "a".repeat(32)
            PadLedger(ledger).register(padId)
            assertEquals(1L, PadLedger(ledger).reserve(padId))
            val claims = PairingClaims(SecureStore(context, "$indexName-claims"))
            claims.pad(padId, id)
            val claimBytes = File(context.noBackupFilesDir, "$indexName-claims.bin").readBytes()
            dispatch.parentFile!!.mkdirs()
            val dispatchBytes = "retained dispatch fixture".toByteArray()
            dispatch.writeBytes(dispatchBytes)
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            val ledgerBytes = File(context.noBackupFilesDir, "$id-pads.bin").readBytes()
            val marker = VaultLifecycle.marker(context.noBackupFilesDir, id).readBytes()
            val residue = File(context.noBackupFilesDir, "$id.bin.new").apply { writeText("restored residue") }
            try { book.dismissRetired(id); fail("Dismissed incomplete cleanup") } catch (_: IllegalStateException) {}
            assertTrue(book.chats().any { it.id == id })
            residue.delete()
            assertTrue(book.dismissRetired(id))
            assertEquals(other.id, book.active().id)
            assertEquals(listOf(other), ChatBook(context, indexName, id).chats())
            assertArrayEquals(ledgerBytes, File(context.noBackupFilesDir, "$id-pads.bin").readBytes())
            assertArrayEquals(marker, VaultLifecycle.marker(context.noBackupFilesDir, id).readBytes())
            assertArrayEquals(claimBytes, File(context.noBackupFilesDir, "$indexName-claims.bin").readBytes())
            assertArrayEquals(dispatchBytes, dispatch.readBytes())
            try { claims.pad(padId, other.id); fail("Reassigned retired pad") } catch (_: IllegalArgumentException) {}
            assertTrue(id in ContactInventory.scan(context))
            try { VaultLifecycle.requireActive(context.noBackupFilesDir, id); fail("Reopened retired root") } catch (_: IllegalStateException) {}
            assertFalse(book.dismissRetired(id))
            assertTrue(VaultRetirement.retireContact(context, other.id).completed)
            assertTrue(book.dismissRetired(other.id))
            val placeholder = book.active(); ids.add(placeholder.id)
            assertEquals("Chat 1", placeholder.name)
            assertFalse(placeholder.id in setOf(id, other.id))
            assertNull(SecureStore(context, placeholder.id).load())
            assertEquals(placeholder, ChatBook(context, indexName, id).active())
            // A stale dismissal must never remove a replacement sharing the old entryId.
            assertTrue(VaultRetirement.retireContact(context, placeholder.id).completed)
            val fresh = book.replaceRetired(placeholder.id); ids.add(fresh.id)
            assertFalse(book.dismissRetired(placeholder.id))
            assertEquals(fresh, book.active())
        } finally { dispatch.delete(); ids.forEach(::cleanup); index.forget(); SecureStore(context, "$indexName-claims").forget() }
    }

    @Test fun completedNoticeCanBeDismissedFromTheScreen() = dismissCompletedNotice(false)
    @Test fun backAcknowledgesCompletedNotice() = dismissCompletedNotice(true)

    private fun dismissCompletedNotice(back: Boolean) {
        StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
        val index = SecureStore(context, "chat-index"); val previous = index.load()
        val id = root(); val ids = mutableSetOf(id); var activity: NetworkActivity? = null
        try {
            index.save(JSONObject().put("version", 3).put("active", id).put("chats", JSONArray()
                .put(JSONObject().put("id", id).put("entryId", id).put("name", "Dismiss me"))))
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            val a = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NetworkActivity
            activity = a
            await { field(a, "busy") == false && field(a, "retiredChat") != null }
            instrumentation.runOnMainSync {
                if (field(a, "tab") == 4) views(a).filterIsInstance<Button>().first {
                    it.isShown && it.contentDescription?.toString() == "Dismiss me. Retired on this device"
                }.performClick()
            }
            await { field(a, "busy") == false && views(a).filterIsInstance<Button>().any {
                it.isShown && it.text.toString() == "Done · remove from Chats" && it.isEnabled
            } }
            if (back) instrumentation.runOnMainSync { a.onBackPressed() }
            else tap(a, "Done · remove from Chats")
            await { field(a, "busy") == false && field(a, "tab") == 4 }
            val book = ChatBook(context); ids.addAll(book.chats().map { it.id })
            assertFalse(book.chats().any { it.id == id })
            assertTrue(VaultLifecycle.isRetired(context.noBackupFilesDir, id))
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync { a.finish() }; await { a.isDestroyed } }
            StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
            try { ids.addAll(ChatBook(context).chats().map { it.id }) } catch (_: Exception) {}
            ids.forEach(::cleanup)
            if (previous == null) index.forget() else index.save(previous)
        }
    }

    private fun ChatBook.recoverRetiredSafelyForTest(id: String): Boolean {
        try { recoverRetired(id); fail("Recovery action retired an active contact") } catch (_: IllegalStateException) {}
        return VaultRetirement.retireContact(context, id).completed
    }
}
