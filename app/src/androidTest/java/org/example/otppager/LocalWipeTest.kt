package org.example.otppager

import android.app.AlertDialog
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** User-initiated local-only wipe: S02 isolation, S05 no waiting on the station worker, S10 honest status. */
@RunWith(AndroidJUnit4::class)
class LocalWipeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private fun cleanup(roots: Collection<String>) {
        for (id in roots) {
            listOf(id, "$id-draft", "$id-pads").forEach { SecureStore(context, it).forget() }
            VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, id)).forEach { it.delete() }
        }
    }
    /** Occupies the shared station worker, as a hung relay request or pairing step would. */
    private fun blockWorker(): CountDownLatch {
        StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        StationRuntime.worker.execute { entered.countDown(); release.await(30, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        return release
    }
    private fun wipe(book: ChatBook, id: String): VaultRetirement.Result {
        val done = CountDownLatch(1); val outcome = AtomicReference<Result<VaultRetirement.Result>>()
        LocalWipe.submit(book, id) { outcome.set(it); done.countDown() }
        assertTrue("Local wipe waited behind other work", done.await(10, TimeUnit.SECONDS))
        return outcome.get().getOrThrow()
    }

    @Test fun wipeRetiresOnlyTheTargetWithoutWaitingForTheStationWorker() {
        val index = "wipe-" + UUID.randomUUID().toString().take(8); val id = root()
        val roots = mutableListOf(id)
        var release: CountDownLatch? = null
        try {
            val book = ChatBook(context, index, id); book.rename("Alice")
            val engine = book.engine()
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            engine.generatePad().fill(0)
            val master = Protocol.master(engine.state!!.getString("secret"))
            val room = try { Protocol.room(master) } finally { master.fill(0) }
            ChatDraft(SecureStore(context, id).sibling("draft")).save(room, UUID.randomUUID().toString(), "Private draft")
            val neighbor = book.add("Bob", null); roots.add(neighbor.id)
            book.engine(neighbor.id).configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val neighborBytes = File(context.noBackupFilesDir, "${neighbor.id}.bin").readBytes()
            val ledger = File(context.noBackupFilesDir, "$id-pads.bin").readBytes()
            val claims = SecureStore(context, "$index-claims").load()!!.toString()

            assertFalse(PadMaterial.gone(context.noBackupFilesDir, id))
            release = blockWorker()
            val result = wipe(book, id)
            assertTrue(result.terminalDurable); assertTrue(result.completed); assertTrue(result.failures.isEmpty())
            assertEquals(VaultRetirement.LocalState.COMPLETE, VaultRetirement.inspect(context, id).state)
            for (slot in listOf(id, "$id-draft"))
                assertTrue("$slot survived", VaultLifecycle.variants(File(context.noBackupFilesDir, "$slot.bin")).none { it.exists() })
            assertTrue("Pad material survived the wipe", PadMaterial.gone(context.noBackupFilesDir, id))
            assertNull("Live engine kept the wiped snapshot", engine.state)
            assertTrue(book.summary(id).retired)
            // Reuse guards and the contact entry stay; the neighbor is untouched and still usable.
            assertArrayEquals(ledger, File(context.noBackupFilesDir, "$id-pads.bin").readBytes())
            assertEquals(claims, SecureStore(context, "$index-claims").load()!!.toString())
            assertEquals(listOf("Alice", "Bob"), book.chats().map { it.name })
            assertArrayEquals(neighborBytes, File(context.noBackupFilesDir, "${neighbor.id}.bin").readBytes())
            assertFalse(book.summary(neighbor.id).retired)
            assertNotNull(book.engine(neighbor.id).state)
            release.countDown(); release = null

            // Repeating the wipe only retries cleanup; a fresh pairing still starts empty.
            assertTrue(wipe(book, id).completed)
            val fresh = book.replaceRetired(id); roots.add(fresh.id)
            assertEquals("Alice", fresh.name); assertNull(book.engine(fresh.id).state)
        } finally {
            release?.countDown()
            cleanup(roots)
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
        }
    }

    @Test fun wipeRefusesUnlistedAndNonContactNamespaces() {
        val index = "wipe-" + UUID.randomUUID().toString().take(8); val id = root(); val stranger = root()
        try {
            val book = ChatBook(context, index, id)
            for (target in listOf(stranger, index, "$index-claims", "$id-pads")) {
                try { book.wipeLocal(target); fail("Wiped $target") } catch (_: IllegalArgumentException) {}
                assertFalse(VaultLifecycle.isRetired(context.noBackupFilesDir, target))
            }
            assertFalse(book.summary(id).retired)
        } finally {
            cleanup(listOf(id, stranger))
            SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
        }
    }

    private fun field(activity: NetworkActivity, name: String) =
        NetworkActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun views(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun shown(activity: NetworkActivity) = views(activity.findViewById(android.R.id.content)).filter { it.isShown }
    private fun dialog(activity: NetworkActivity): AlertDialog {
        @Suppress("UNCHECKED_CAST")
        return (field(activity, "openDialogs") as List<AlertDialog>).last { it.isShowing }
    }
    private fun await(test: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { ready = test() }
            if (!ready) Thread.sleep(40)
        }
        assertTrue("Wipe UI did not reach expected state", ready)
    }

    @Test fun menuWipeNeedsTypedConfirmationClearsPrivateUiAndReportsOnlyLocalResult() {
        StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
        val id = root()
        val index = SecureStore(context, "chat-index"); val claims = SecureStore(context, "chat-index-claims")
        val savedIndex = index.load(); val savedClaims = claims.load()
        var activity: NetworkActivity? = null
        var release: CountDownLatch? = null
        try {
            index.save(JSONObject().put("version", 3).put("active", id).put("chats", JSONArray()
                .put(JSONObject().put("id", id).put("entryId", id).put("name", "Private friend"))))
            val station = ChatBook(context).engine()
            station.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val master = Protocol.master(station.state!!.getString("secret"))
            val room = try { Protocol.room(master) } finally { master.fill(0) }
            ChatDraft(SecureStore(context, "$id-draft")).save(room, UUID.randomUUID().toString(), "Private draft")
            val a = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NetworkActivity
            activity = a
            await { (field(a, "composer") as EditText).text.toString() == "Private draft" && field(a, "busy") == false }

            instrumentation.runOnMainSync {
                shown(a).filterIsInstance<Button>().first { it.contentDescription?.toString() == "Conversation options" }.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val menu = dialog(a)
                // Title comes from worker-captured state, not a UI-thread index read.
                assertTrue(views(menu.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Private friend" })
                val n = (0 until menu.listView.adapter.count).first { menu.listView.adapter.getItem(it).toString() == "Wipe this chat from this phone" }
                menu.listView.performItemClick(menu.listView.getChildAt(n), n, n.toLong())
            }
            instrumentation.waitForIdleSync()
            lateinit var input: EditText
            instrumentation.runOnMainSync {
                val confirm = dialog(a)
                input = views(confirm.window!!.decorView).filterIsInstance<EditText>().single()
                val wipe = confirm.getButton(AlertDialog.BUTTON_POSITIVE)
                assertFalse("Wipe armed without typed confirmation", wipe.isEnabled)
                input.setText("wip"); assertFalse(wipe.isEnabled)
                input.setText(" wipe "); assertTrue(wipe.isEnabled)
            }
            InterfaceTest().capture(instrumentation, a, "wipe-confirm")
            assertFalse(VaultLifecycle.isRetired(context.noBackupFilesDir, id))

            // The normal app worker is stuck (e.g. a hung relay request); the wipe must still complete.
            release = blockWorker()
            instrumentation.runOnMainSync { dialog(a).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await { shown(a).filterIsInstance<TextView>().any { it.text.toString() == "Wiped from this phone" } }
            InterfaceTest().capture(instrumentation, a, "wipe-result")
            instrumentation.runOnMainSync {
                val text = shown(a).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
                assertTrue(text.contains("Your friend's phone was not changed"))
                assertFalse("Private text still on screen", text.contains("Private draft"))
                assertNull(field(a, "engine")); assertNull(field(a, "latest")); assertNull(field(a, "openChat"))
                assertNull(field(a, "draftChatId")); assertNull(field(a, "draftRoom"))
                for (name in listOf("composer", "urlField", "tokenField", "secretField"))
                    assertEquals("Retained text in $name", "", (field(a, name) as EditText).text.toString())
            }
            assertTrue(VaultLifecycle.isRetired(context.noBackupFilesDir, id))
            assertEquals(VaultRetirement.LocalState.COMPLETE, VaultRetirement.inspect(context, id).state)
            assertFalse(File(context.noBackupFilesDir, "$id-draft.bin").exists())
            release.countDown(); release = null

            // Continue lands on the existing recovery screen, which offers retry/fresh pairing.
            instrumentation.runOnMainSync { shown(a).filterIsInstance<Button>().first { it.text.toString() == "Continue" }.performClick() }
            await { shown(a).filterIsInstance<TextView>().any { it.text.toString() == "Local cleanup completed" } }
            instrumentation.runOnMainSync { assertTrue(shown(a).filterIsInstance<Button>().any { it.text.toString() == "Start fresh pairing" && it.isEnabled }) }
            InterfaceTest().capture(instrumentation, a, "wipe-recovery")
        } finally {
            release?.countDown()
            activity?.let { a -> instrumentation.runOnMainSync { if (!a.isFinishing) a.finish() }; await { a.isDestroyed } }
            StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
            cleanup(listOf(id))
            if (savedIndex == null) index.forget() else index.save(savedIndex)
            if (savedClaims == null) claims.forget() else claims.save(savedClaims)
        }
    }
}
