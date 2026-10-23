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
    private fun capture(instrumentation: Instrumentation, activity: Activity, name: String) {
        val context = instrumentation.targetContext
        // Only this test's fictional profile is present. Check the production flag before
        // temporarily permitting compositor capture; restore it even if capture fails.
        instrumentation.runOnMainSync {
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val image: Bitmap
        try {
            instrumentation.waitForIdleSync(); Thread.sleep(200)
            image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        } finally {
            instrumentation.runOnMainSync { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
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
                assertTrue(all(content(activity)).filterIsInstance<TextView>().any { it.text.toString().contains("DEVICE EXCHANGE INCOMPLETE") })
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
}
