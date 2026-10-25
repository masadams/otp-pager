package org.example.otppager

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
class PagerJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun views(a: Activity) = all(a.findViewById(android.R.id.content))
    private fun await(test: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000; var ready = false
        while (!ready && System.currentTimeMillis() < until) { instrumentation.runOnMainSync { ready = test() }; if (!ready) Thread.sleep(40) }
        assertTrue("Screen did not become ready", ready)
    }
    private fun open(): Activity {
        val a = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { views(a).filterIsInstance<Switch>().forEach { it.isChecked = false } }
        await { views(a).filterIsInstance<Button>().any { it.text.toString() == "Add friend" && it.isShown } && NetworkActivity::class.java.getDeclaredField("book").apply { isAccessible = true }.get(a) != null && !(NetworkActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.getBoolean(a)) }
        return a
    }
    private fun tap(a: Activity, label: String) {
        instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.text.toString() == label }.performClick() }
        instrumentation.waitForIdleSync()
    }
    private fun dialog(a: Activity): AlertDialog {
        @Suppress("UNCHECKED_CAST")
        val dialogs = NetworkActivity::class.java.getDeclaredField("openDialogs").apply { isAccessible = true }.get(a) as List<AlertDialog>
        return dialogs.last { it.isShowing }
    }
    private fun option(a: Activity, label: String) {
        instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.contentDescription?.toString() == "Conversation options" }.performClick() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { val d = dialog(a); val n = (0 until d.listView.adapter.count).first { d.listView.adapter.getItem(it).toString() == label }; d.listView.performItemClick(d.listView.getChildAt(n), n, n.toLong()) }
        instrumentation.waitForIdleSync()
    }
    private fun close(a: Activity) { instrumentation.runOnMainSync { a.finish() }; await { a.isDestroyed }; StationRuntime.worker.submit {}.get(15, TimeUnit.SECONDS) }
    private fun clean(ids: List<String>) {
        ids.forEach { id -> SecureStore(context, id).sibling("draft").forget(); SecureStore(context, id).forget(); SecureStore(context, id).sibling("pads").forget() }
        SecureStore(context, "chat-index").forget(); SecureStore(context, "chat-index-claims").forget()
    }
    @Test fun damagedVaultShowsRecoveryWithoutResettingStoredData() {
        clean(listOf("station-v2")); var activity: Activity? = null
        try {
            val book = ChatBook(context)
            book.engine().configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val file = java.io.File(context.noBackupFilesDir, "station-v2.bin")
            val damaged = byteArrayOf(1, 2, 3); file.writeBytes(damaged)
            val a = open(); activity = a
            instrumentation.runOnMainSync {
                assertTrue(views(a).filterIsInstance<Button>().any { it.isShown && it.text.toString() == "Open recovery tools" })
                assertFalse(views(a).filterIsInstance<Button>().first { it.text.toString() == "Add friend" }.isEnabled)
            }
            assertArrayEquals(damaged, file.readBytes())
            tap(a, "Open recovery tools")
            instrumentation.runOnMainSync {
                assertFalse(views(a).filterIsInstance<Button>().first { it.text.toString() == "Send" }.isEnabled)
                assertTrue(views(a).filterIsInstance<Button>().any { it.isShown && it.text.toString() == "Forget pairing" })
            }
            assertArrayEquals(damaged, file.readBytes())
        } finally { activity?.let(::close); clean(listOf("station-v2")) }
    }
    @Test fun firstLaunchGuidesNamingServerTransferAndUsbWithoutAGhostContact() {
        clean(listOf("station-v2")); var activity: Activity? = null
        try {
            assertEquals(NetworkActivity::class.java.name, context.packageManager.getLaunchIntentForPackage(context.packageName)!!.component!!.className)
            @Suppress("DEPRECATION")
            assertTrue(context.packageManager.getActivityInfo(ComponentName(context, NetworkActivity::class.java), 0).exported)
            val a = open(); activity = a
            assertEquals(1, ChatBook(context).chats().size)
            InterfaceTest().capture(instrumentation, a, "inbox-empty")
            tap(a, "Add friend")
            instrumentation.runOnMainSync {
                val d = dialog(a); all(d.window!!.decorView).filterIsInstance<EditText>().first().setText("Alex")
                d.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            await { views(a).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Pair with Alex" } }
            assertEquals(1, ChatBook(context).chats().size); assertEquals("Alex", ChatBook(context).active().name)
            InterfaceTest().capture(instrumentation, a, "setup-server")
            tap(a, "Get setup from my friend")
            assertNull(SecureStore(context).load()) // Reading station need not invent server credentials.
            instrumentation.runOnMainSync { assertTrue(views(a).filterIsInstance<Button>().any { it.isShown && it.text.toString() == "Use a USB cable" }) }
            tap(a, "Enter server details")
            instrumentation.runOnMainSync {
                val fields = views(a).filterIsInstance<EditText>()
                fields.first { it.hint.toString() == "Server address" }.setText("https://pager.example.com")
                fields.first { it.hint.toString() == "Access token" }.apply {
                    assertTrue(transformationMethod is android.text.method.PasswordTransformationMethod)
                    assertFalse(isSaveEnabled); setText("t".repeat(43))
                }
            }
            tap(a, "Save and continue")
            await { views(a).filterIsInstance<Button>().any { it.isShown && it.text.toString() == "Use a USB cable" } && !(NetworkActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.getBoolean(a)) }
            assertEquals("https://pager.example.com", SecureStore(context).load()!!.getString("endpoint"))
            InterfaceTest().capture(instrumentation, a, "setup-method")
            val monitor = instrumentation.addMonitor(CablePairActivity::class.java.name, null, false)
            try {
                tap(a, "Use a USB cable")
                val cable = monitor.waitForActivityWithTimeout(10000) ?: error("USB wizard did not open")
                try {
                    instrumentation.runOnMainSync { assertFalse(all(cable.findViewById(android.R.id.content)).filterIsInstance<Button>().first { it.text.toString() == "Codes match · Pair" }.isEnabled) }
                    InterfaceTest().capture(instrumentation, cable, "pairing-usb")
                } finally { instrumentation.runOnMainSync { cable.finish() } }
            } finally { instrumentation.removeMonitor(monitor) }
        } finally { activity?.let(::close); clean(listOf("station-v2")) }
    }
    @Test fun inboxConversationAndFriendDetailsKeepTechnicalToolsOutOfTheEverydayFlow() {
        clean(listOf("station-v2")); val ids = mutableListOf("station-v2"); var activity: Activity? = null
        val peer = DeviceIdentity("journey-peer"); peer.forget()
        try {
            val book = ChatBook(context); book.rename("Alex")
            val station = book.engine(); station.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            station.generatePad().fill(0); station.confirmPad(station.view().padCheck!!.takeLast(8))
            val store = SecureStore(context)
            val ready = JSONObject(station.state.toString()).put("peerReady", true)
            ready.getJSONArray("outbox").getJSONObject(0).put("status", "relayed"); store.save(ready)
            val bound = book.engine(); bound.prepareIdentity(); val card = peer.card(true)
            bound.pinDevice(card, IdentityProtocol.fingerprint(card).takeLast(8))
            // Fictional, encrypted local history for native UI captures; no test hook in the app.
            val now = System.currentTimeMillis()
            store.save(JSONObject(bound.state.toString()).put("history", JSONArray()
                .put(JSONObject().put("incoming", false).put("text", "The north gate is clear.").put("status", "delivered").put("time", now - 120000).put("unread", false).put("deviceVerified", true))
                .put(JSONObject().put("incoming", true).put("text", "Understood. See you there.").put("status", "received").put("time", now - 60000).put("unread", true).put("deviceVerified", true))))
            ids.add(book.add("Morgan").id)
            val master = Protocol.master(bound.state!!.getString("secret")); val room = try { Protocol.room(master) } finally { master.fill(0) }
            ChatDraft(store.sibling("draft")).save(room, UUID.randomUUID().toString(), "Meet at the north gate.")
            val a = open(); activity = a
            instrumentation.runOnMainSync {
                assertTrue(views(a).filterIsInstance<Button>().any { it.isShown && it.contentDescription?.toString()?.startsWith("Alex.") == true })
                assertFalse(views(a).filterIsInstance<EditText>().any { it.isShown })
            }
            InterfaceTest().capture(instrumentation, a, "inbox-friends")
            assertEquals(1, StationEngine(store).view().unread) // Showing Chats must not mark a friend's message read.
            instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.contentDescription?.toString()?.startsWith("Alex.") == true }.performClick() }
            await { views(a).filterIsInstance<EditText>().any { it.isShown && it.text.toString() == "Meet at the north gate." } }
            await { !(NetworkActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.getBoolean(a)) }
            instrumentation.runOnMainSync {
                for (label in listOf("Pad", "Device", "Sync", "Receiving & battery")) assertFalse(views(a).filterIsInstance<Button>().any { it.isShown && it.text.toString() == label })
                assertTrue(views(a).filterIsInstance<Button>().first { it.text.toString() == "Send" }.isShown)
            }
            InterfaceTest().capture(instrumentation, a, "conversation-redesign")
            option(a, "Friend details")
            InterfaceTest().capture(instrumentation, a, "friend-details")
            tap(a, "Advanced setup")
            instrumentation.runOnMainSync { assertTrue(views(a).filterIsInstance<Button>().first { it.text.toString() == "Device" }.isShown) }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { views(a).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Friend details" } }
            instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.contentDescription?.toString() == "Back" }.performClick() }
            instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.contentDescription?.toString() == "Back to chats" }.performClick() }
            instrumentation.runOnMainSync { views(a).filterIsInstance<Button>().first { it.isShown && it.contentDescription?.toString()?.startsWith("Morgan.") == true }.performClick() }
            await { views(a).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Pair with Morgan" } }
            InterfaceTest().capture(instrumentation, a, "add-friend-methods")
            instrumentation.runOnMainSync { assertFalse(views(a).filterIsInstance<EditText>().any { it.isShown && it.hint.toString() == "Access token" }) }
        } finally { activity?.let(::close); peer.forget(); clean(ids) }
    }
}
