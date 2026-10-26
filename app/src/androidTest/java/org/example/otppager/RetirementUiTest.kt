package org.example.otppager

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RetirementUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun field(activity: NetworkActivity, name: String) = NetworkActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(test: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { ready = test() }
            if (!ready) Thread.sleep(40)
        }
        assertTrue("Retirement UI did not reach expected state", ready)
    }
    @Test fun retirementClearsDraftDialogsFieldsAndFinishesWithoutWaitingForWorker() {
        StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
        val root = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
        val index = SecureStore(context, "chat-index"); val claims = SecureStore(context, "chat-index-claims")
        val savedIndex = index.load(); val savedClaims = claims.load()
        var activity: NetworkActivity? = null
        val release = java.util.concurrent.CountDownLatch(1)
        try {
            index.save(JSONObject().put("version", 3).put("active", root).put("chats", JSONArray()
                .put(JSONObject().put("id", root).put("entryId", root).put("name", "Private friend"))))
            val station = ChatBook(context).engine()
            station.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val master = Protocol.master(station.state!!.getString("secret"))
            val room = try { Protocol.room(master) } finally { master.fill(0) }
            ChatDraft(SecureStore(context, "$root-draft")).save(room, UUID.randomUUID().toString(), "Private draft")
            val a = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NetworkActivity
            activity = a
            await { (field(a, "composer") as EditText).text.toString() == "Private draft" && field(a, "busy") == false }
            lateinit var dialog: AlertDialog
            instrumentation.runOnMainSync {
                NetworkActivity::class.java.getDeclaredMethod("showStationStatus").apply { isAccessible = true }.invoke(a)
                @Suppress("UNCHECKED_CAST")
                val dialogs = field(a, "openDialogs") as List<AlertDialog>
                dialog = dialogs.last { it.isShowing }
            }
            // The normal app worker is unavailable: UI invalidation must use the main looper directly.
            val entered = java.util.concurrent.CountDownLatch(1)
            StationRuntime.worker.execute { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(VaultRetirement.retireContact(context, root).completed)
            await { a.isDestroyed }
            instrumentation.runOnMainSync {
                assertFalse(dialog.isShowing)
                for (name in listOf("composer", "urlField", "tokenField", "secretField", "peerCardField"))
                    assertEquals("Retained text in $name", "", (field(a, name) as EditText).text.toString())
                assertNull(field(a, "engine")); assertNull(field(a, "latest")); assertNull(field(a, "rendered"))
                assertNull(field(a, "draftChatId")); assertNull(field(a, "draftRoom"))
                assertNull(field(a, "pendingScan")); assertNull(field(a, "pendingDeviceScan")); assertNull(field(a, "pendingDocument"))
                assertTrue(views(a.findViewById(android.R.id.content)).filterIsInstance<EditText>().all { it.text.isEmpty() })
            }
            assertFalse(java.io.File(context.noBackupFilesDir, "$root-draft.bin").exists())
        } finally {
            release.countDown()
            activity?.let { a -> instrumentation.runOnMainSync { if (!a.isFinishing) a.finish() }; await { a.isDestroyed } }
            StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS)
            listOf(root, "$root-draft", "$root-pads").forEach { SecureStore(context, it).forget() }
            VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, root)).forEach { it.delete() }
            if (savedIndex == null) index.forget() else index.save(savedIndex)
            if (savedClaims == null) claims.forget() else claims.save(savedClaims)
        }
    }
}
