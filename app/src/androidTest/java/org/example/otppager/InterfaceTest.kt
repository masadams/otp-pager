package org.example.otppager

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun content(activity: Activity) = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun awaitStation(instrumentation: Instrumentation, activity: Activity) {
        var ready = false; val deadline = System.currentTimeMillis() + 10000
        while (!ready && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val views = all(content(activity))
                ready = views.filterIsInstance<TextView>().any { it.text.toString() == "STATION A" } &&
                    views.filterIsInstance<Button>().any { it.text.toString() == "Save & connect" && it.isEnabled }
            }
            if (!ready) Thread.sleep(50)
        }
        assertTrue("Station did not finish loading", ready)
        instrumentation.runOnMainSync { all(content(activity)).filterIsInstance<Switch>().forEach { it.isChecked = false } }
    }
    private fun tap(instrumentation: Instrumentation, activity: Activity, label: String) {
        instrumentation.runOnMainSync { all(content(activity)).filterIsInstance<Button>().first { it.text.toString() == label }.performClick() }
        instrumentation.waitForIdleSync()
    }
    private fun capture(instrumentation: Instrumentation, activity: Activity, name: String, windowOnly: Boolean = false) {
        val context = instrumentation.targetContext
        @Suppress("UNCHECKED_CAST")
        val dialogs = (NetworkActivity::class.java.getDeclaredField("openDialogs").apply { isAccessible = true }
            .get(activity) as List<android.app.AlertDialog>).filter { it.isShowing }
        // Only this test's fictional profile is present. Check the production flag before
        // temporarily permitting compositor capture; restore it even if capture fails.
        instrumentation.runOnMainSync {
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialogs.forEach { dialog ->
                assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                dialog.window!!.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        val image: Bitmap
        try {
            instrumentation.waitForIdleSync(); Thread.sleep(200)
            image = if (windowOnly) {
                // Android 12 compositor capture can stall with a secure IME layer. Copy the
                // actual app surface instead; keyboard presence/geometry is asserted separately.
                val bounds = android.graphics.Point()
                instrumentation.runOnMainSync { bounds.set(activity.window.decorView.width, activity.window.decorView.height) }
                val copy = Bitmap.createBitmap(bounds.x, bounds.y, Bitmap.Config.ARGB_8888)
                val done = java.util.concurrent.CountDownLatch(1)
                val result = java.util.concurrent.atomic.AtomicInteger(-1)
                android.view.PixelCopy.request(activity.window, copy, { result.set(it); done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
                if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS) || result.get() != android.view.PixelCopy.SUCCESS) {
                    copy.recycle(); error("App window capture failed: ${result.get()}")
                }
                copy
            } else checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        } finally {
            instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); dialogs.forEach { it.window!!.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/otp-pager-preview")
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally { image.recycle() }
    }
    @Test fun unpairedChatExplainsSetupAndProtectsKeyboardInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = SecureStore(instrumentation.targetContext)
        store.forget(); store.sibling("pads").forget()
        StationEngine(store).configure("https://pager.example.com", "x".repeat(43), Protocol.randomSecret(), "A")
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            awaitStation(instrumentation, activity)
            instrumentation.runOnMainSync {
                val views = all(content(activity))
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Make a private connection." })
                assertFalse("Unpaired chat offered AES sending", views.filterIsInstance<Button>().first { it.text.toString() == "Send" }.isEnabled)
                assertTrue(views.filterIsInstance<Button>().any { it.text.toString() == "Set up this chat" && it.isShown })
                views.filterIsInstance<EditText>().forEach {
                    assertTrue(it.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
                    assertTrue(it.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
                }
            }
            capture(instrumentation, activity, "unpaired-chat")
            tap(instrumentation, activity, "Set up this chat")
            instrumentation.runOnMainSync { assertTrue(all(content(activity)).filterIsInstance<Button>().first { it.text.toString() == "Save server settings" }.isShown) }
        } finally { instrumentation.runOnMainSync { activity.finish() }; store.forget(); store.sibling("pads").forget() }
    }
    @Test fun capturesActualEinkInterfaceWithFictionalTraffic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SecureStore(context); store.forget(); store.sibling("pads").forget()
        val secret = Protocol.randomSecret()
        val engine = StationEngine(store)
        engine.configure("https://pager.example.com", "x".repeat(43), secret, "A")
        val bundle = engine.generatePad()
        engine.confirmPad(engine.view().padCheck!!.takeLast(8))
        // Fictional readiness without making a network request.
        store.save(JSONObject(engine.state.toString()).put("peerReady", true))
        val readyEngine = StationEngine(store)
        readyEngine.send("The north gate is clear. Meet at 22:00.")
        val state = JSONObject(readyEngine.state.toString())
        for (i in 0 until state.getJSONArray("outbox").length()) {
            val entry = state.getJSONArray("outbox").getJSONObject(i)
            entry.put("status", if (entry.getString("kind") == "text") "delivered" else "relayed")
        }
        val master = Protocol.master(secret)
        val pad = PadProtocol.material(bundle, PadProtocol.binding(master))
        val incoming = PadProtocol.seal(pad, 'B', 1, JSONObject().put("kind", "text").put("text", "Understood. Bringing the maps.").toString())
        master.fill(0); pad.fill(0); bundle.fill(0)
        state.put("inbox", JSONArray().put(JSONObject().put("id", incoming.first).put("read", true).put("packet", incoming.second).put("time", System.currentTimeMillis())))
        state.put("high", 1).put("seen", JSONArray(listOf(1)))
        store.save(state)
        val activity = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            awaitStation(instrumentation, activity)
            instrumentation.runOnMainSync {
                val fields = all(content(activity)).filterIsInstance<EditText>().filter {
                    it.hint.toString() == "Relay access token" || it.hint.toString() == "43-character pairing secret"
                }
                assertEquals(2, fields.size)
                fields.forEach { assertTrue("Credential field must be masked", it.transformationMethod is PasswordTransformationMethod) }
            }
            capture(instrumentation, activity, "messages")
            instrumentation.runOnMainSync {
                all(content(activity)).filterIsInstance<Button>().first { it.text.toString().contains("▾") }.performClick()
            }
            capture(instrumentation, activity, "chats")
            instrumentation.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                val dialogs = NetworkActivity::class.java.getDeclaredField("openDialogs").apply { isAccessible = true }.get(activity) as List<android.app.AlertDialog>
                assertTrue(dialogs.last().isShowing)
                dialogs.last().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            tap(instrumentation, activity, "Link"); capture(instrumentation, activity, "link")
            tap(instrumentation, activity, "Pad"); capture(instrumentation, activity, "pad")
            instrumentation.runOnMainSync {
                assertTrue(all(content(activity)).filterIsInstance<Button>().any { it.text.toString() == "Send readiness again" })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
            store.forget(); store.sibling("pads").forget()
        }
    }
    @Test fun deviceExchangePausesSendingAndShowsHonestKeyStatus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SecureStore(context); store.forget(); store.sibling("pads").forget()
        val engine = StationEngine(store)
        engine.configure("https://pager.example.com", "x".repeat(43), Protocol.randomSecret(), "A")
        engine.generatePad().fill(0); engine.confirmPad(engine.view().padCheck!!.takeLast(8))
        val state = JSONObject(engine.state.toString()).put("peerReady", true)
        state.getJSONArray("outbox").getJSONObject(0).put("status", "relayed")
        store.save(state)
        val prepared = StationEngine(store); prepared.prepareIdentity()
        val card = prepared.view().ownDevice!!
        val activity = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            awaitStation(instrumentation, activity)
            instrumentation.runOnMainSync {
                assertFalse(all(content(activity)).filterIsInstance<Button>().first { it.text.toString() == "Send" }.isEnabled)
                assertTrue(all(content(activity)).filterIsInstance<TextView>().any { it.text.toString().contains("DEVICE EXCHANGE INCOMPLETE") || it.text.toString().contains("FINISH DEVICE EXCHANGE") })
            }
            tap(instrumentation, activity, "Device")
            instrumentation.runOnMainSync {
                val views = all(content(activity))
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains(card) && it.text.toString().contains("KEYSTORE") })
                assertTrue(views.filterIsInstance<Button>().first { it.text.toString() == "Compare & pin other device" }.isEnabled)
                assertFalse(views.filterIsInstance<Button>().first { it.text.toString() == "Create this device identity" }.isEnabled)
            }
            capture(instrumentation, activity, "device")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
            store.forget(); store.sibling("pads").forget()
        }
    }
    @Test fun unverifiedPadDisablesSendingAndShowsSetup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SecureStore(context); store.forget(); store.sibling("pads").forget()
        val engine = StationEngine(store)
        engine.configure("https://pager.example.com", "x".repeat(43), Protocol.randomSecret(), "A")
        engine.generatePad().fill(0)
        val activity = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            awaitStation(instrumentation, activity)
            instrumentation.runOnMainSync {
                val views = all(content(activity))
                assertFalse(views.filterIsInstance<Button>().first { it.text.toString() == "Send" }.isEnabled)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString().contains("COMPARE CODES IN PERSON") })
            }
            tap(instrumentation, activity, "Pad")
            instrumentation.runOnMainSync {
                val views = all(content(activity))
                assertTrue(views.filterIsInstance<Button>().first { it.text.toString() == "Verify codes & announce" }.isEnabled)
                assertFalse(views.filterIsInstance<Button>().first { it.text.toString() == "A · Create & export pad file" }.isEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
            store.forget(); store.sibling("pads").forget()
        }
    }
    @Test fun compactDisplayKeepsTrafficAndComposerVisibleWithKeyboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        org.junit.Assume.assumeTrue("Kompakt viewport runs on the native API 31 AVD", android.os.Build.VERSION.SDK_INT == 31)
        val oldKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        val store = SecureStore(context); store.forget(); store.sibling("pads").forget()
        var activity: Activity? = null
        try {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            val engine = StationEngine(store)
            engine.configure("https://pager.example.com", "x".repeat(43), Protocol.randomSecret(), "A")
            engine.generatePad().fill(0); engine.confirmPad(engine.view().padCheck!!.takeLast(8))
            val state = JSONObject(engine.state.toString()).put("peerReady", true)
            state.getJSONArray("outbox").getJSONObject(0).put("status", "relayed")
            store.save(state)
            val ready = StationEngine(store); ready.send("Compact screen delivery check")
            val waiting = JSONObject(ready.state.toString())
            waiting.getJSONArray("outbox").getJSONObject(0).put("status", "relayed"); store.save(waiting)
            val launched = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = launched; awaitStation(instrumentation, launched)
            fun assertUsable() = instrumentation.runOnMainSync {
                val views = all(content(launched))
                val scroll = views.filterIsInstance<android.widget.ScrollView>().first { it.isShown }
                assertTrue("Traffic area collapsed", scroll.height >= (40 * context.resources.displayMetrics.density).toInt())
                val send = views.filterIsInstance<Button>().first { it.text.toString() == "Send" }
                val bounds = android.graphics.Rect()
                assertTrue("Send clipped", send.getGlobalVisibleRect(bounds))
                assertEquals(send.height, bounds.height())
                assertTrue(send.isEnabled)
            }
            assertUsable(); capture(instrumentation, launched, "messages-compact")
            instrumentation.runOnMainSync {
                assertEquals(480, launched.resources.displayMetrics.widthPixels)
                assertEquals(320, launched.resources.configuration.screenWidthDp)
                val input = all(content(launched)).filterIsInstance<EditText>().first { it.hint.toString() == "Write a message…" }
                input.requestFocus(); input.setText("Keyboard remains usable")
                launched.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            val deadline = System.currentTimeMillis() + 5000
            var keyboard = false
            while (!keyboard && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val root = content(launched); val visible = android.graphics.Rect(); root.getWindowVisibleDisplayFrame(visible)
                    keyboard = root.rootView.height - visible.bottom > (150 * context.resources.displayMetrics.density).toInt()
                }
                if (!keyboard) Thread.sleep(50)
            }
            assertTrue("Keyboard did not open", keyboard)
            instrumentation.waitForIdleSync(); assertUsable(); capture(instrumentation, launched, "messages-compact-keyboard", windowOnly = true)
        } finally {
            activity?.let { a -> instrumentation.runOnMainSync {
                a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(a.window.decorView.windowToken, 0)
                a.finish()
            }; instrumentation.waitForIdleSync() }
            if (oldKeyboard == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $oldKeyboard")
            store.forget(); store.sibling("pads").forget()
        }
    }

    @Test fun completedPairingHidesSetupAndSettingsRemainReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val store = SecureStore(context); store.forget(); store.sibling("pads").forget()
        val peer = DeviceIdentity("ui-complete-peer"); peer.forget()
        try {
            val station = StationEngine(store)
            station.configure("https://pager.example.com", "x".repeat(43), Protocol.randomSecret(), "A")
            station.generatePad().fill(0); station.confirmPad(station.view().padCheck!!.takeLast(8))
            val ready = JSONObject(station.state.toString()).put("peerReady", true)
            ready.getJSONArray("outbox").getJSONObject(0).put("status", "relayed"); store.save(ready)
            val bound = StationEngine(store); bound.prepareIdentity()
            val card = peer.card(create = true); bound.pinDevice(card, IdentityProtocol.fingerprint(card).takeLast(8))
            store.save(JSONObject(bound.state.toString()).put("relayScoped", true))
            val activity = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                awaitStation(instrumentation, activity)
                instrumentation.runOnMainSync {
                    val views = all(content(activity))
                    for (label in listOf("Link", "Pad", "Device")) assertFalse("Setup tab remained visible", views.filterIsInstance<Button>().first { it.text.toString() == label }.isShown)
                    val send = views.filterIsInstance<Button>().first { it.text.toString() == "Send" }
                    assertFalse("Empty composer enabled Send", send.isEnabled)
                    views.filterIsInstance<android.widget.EditText>().first { it.hint?.toString() == "Write a message…" }.setText("Ready")
                    assertTrue("Valid draft disabled Send", send.isEnabled)
                    views.filterIsInstance<android.widget.EditText>().first { it.hint?.toString() == "Write a message…" }.text.clear()
                    assertFalse(send.isEnabled)
                }
                capture(instrumentation, activity, "messages-paired")
                @Suppress("UNCHECKED_CAST")
                fun latestDialog() = (NetworkActivity::class.java.getDeclaredField("openDialogs").apply { isAccessible = true }.get(activity) as List<android.app.AlertDialog>).last { it.isShowing }
                instrumentation.runOnMainSync {
                    val strip = NetworkActivity::class.java.getDeclaredField("indicators").apply { isAccessible = true }.get(activity) as TextView
                    assertTrue(strip.text.toString().contains("CHAT ACCESS"))
                    strip.performClick()
                }
                instrumentation.waitForIdleSync()
                capture(instrumentation, activity, "chat-status")
                instrumentation.runOnMainSync { latestDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val dialog = latestDialog()
                    assertEquals("Revoke this chat", dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
                    assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isShown)
                }
                capture(instrumentation, activity, "chat-access")
                instrumentation.runOnMainSync { latestDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals("Continue", latestDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).text.toString())
                    latestDialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                assertFalse(StationEngine(store).view().relayRevoked) // Opening/cancelling confirmation never revokes.

                instrumentation.runOnMainSync {
                    all(content(activity)).filterIsInstance<Button>().first { it.text.toString().contains("▾") }.performClick()
                    @Suppress("UNCHECKED_CAST")
                    val dialogs = NetworkActivity::class.java.getDeclaredField("openDialogs").apply { isAccessible = true }.get(activity) as List<android.app.AlertDialog>
                    val dialog = dialogs.last()
                    val index = (0 until dialog.listView.adapter.count).first { dialog.listView.adapter.getItem(it).toString() == "Chat settings" }
                    dialog.listView.performItemClick(dialog.listView.getChildAt(index), index, index.toLong())
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertTrue(all(content(activity)).filterIsInstance<Button>().first { it.text.toString() == "Link" }.isShown)
                }
                tap(instrumentation, activity, "Messages")
                instrumentation.runOnMainSync {
                    assertFalse(all(content(activity)).filterIsInstance<Button>().first { it.text.toString() == "Link" }.isShown)
                }
            } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
        } finally { store.forget(); store.sibling("pads").forget(); peer.forget() }
    }

}
