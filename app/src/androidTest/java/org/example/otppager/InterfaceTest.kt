package org.example.otppager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.text.method.PasswordTransformationMethod
import android.widget.Button
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
    @Test fun capturesActualEinkInterfaceWithFictionalTraffic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SecureStore(context); store.forget(); store.sibling("pads").forget()
        val masterSecret = Protocol.randomSecret()
        val engine = StationEngine(store)
        engine.configure("https://pager.example.com", "x".repeat(43), masterSecret, "A")
        val bundle = engine.generatePad()
        engine.confirmPad(engine.view().padCheck!!.takeLast(8))
        // Fictional authenticated peer readiness, without making network requests.
        store.save(JSONObject(engine.state.toString()).put("peerReady", true))
        val readyEngine = StationEngine(store)
        readyEngine.send("The north gate is clear. Meet at 22:00.")
        val state = JSONObject(readyEngine.state.toString())
        for (i in 0 until state.getJSONArray("outbox").length()) {
            val entry = state.getJSONArray("outbox").getJSONObject(i); entry.put("status", if (entry.getString("kind") == "text") "delivered" else "relayed")
        }
        val master = Protocol.master(masterSecret)
        val pad = PadProtocol.material(bundle, PadProtocol.binding(master))
        val incoming = PadProtocol.seal(pad, 'B', 1, JSONObject().put("kind", "text").put("text", "Understood. Bringing the maps.").toString())
        master.fill(0); pad.fill(0); bundle.fill(0)
        state.put("inbox", JSONArray().put(JSONObject().put("id", incoming.first).put("packet", incoming.second).put("time", System.currentTimeMillis())))
        state.put("high", 1).put("seen", JSONArray(listOf(1)))
        store.save(state)
        val activity = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
            var ready = false
            val deadline = System.currentTimeMillis() + 10000
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = all(content).filterIsInstance<TextView>().any { it.text.toString() == "STATION A" } }
                if (!ready) Thread.sleep(50)
            }
            assertTrue("Station UI did not load", ready)
            instrumentation.runOnMainSync {
                all(content).filterIsInstance<Switch>().forEach { it.isChecked = false }
                val credentialFields = all(content).filterIsInstance<EditText>().filter {
                    it.hint.toString() == "Relay access token" || it.hint.toString() == "43-character pairing secret"
                }
                assertEquals(2, credentialFields.size)
                credentialFields.forEach { assertTrue("Credential field must be masked", it.transformationMethod is PasswordTransformationMethod) }
                fun capture(name: String) {
                    // Draw our own view tree, keeping FLAG_SECURE enabled. No real pairing data.
                    val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                    content.draw(Canvas(image))
                    // MediaStore downloads survive the test runner uninstalling the target APK.
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/otp-pager-preview")
                        }
                        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                        context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    image.recycle()
                }
                capture("messages")
                all(content).filterIsInstance<Button>().first { it.text.toString() == "Link" }.performClick()
                content.measure(View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(content.height, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, content.width, content.height)
                capture("link")
                all(content).filterIsInstance<Button>().first { it.text.toString() == "Pad" }.performClick()
                content.measure(View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(content.height, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, content.width, content.height)
                assertTrue(all(content).filterIsInstance<Button>().any { it.text.toString() == "Send readiness again" })
                capture("pad")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
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
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
            var ready = false; val deadline = System.currentTimeMillis() + 10000
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = all(content).filterIsInstance<TextView>().any { it.text.toString() == "STATION A" } }
                if (!ready) Thread.sleep(50)
            }
            assertTrue(ready)
            instrumentation.runOnMainSync {
                all(content).filterIsInstance<Switch>().forEach { it.isChecked = false }
                assertFalse(all(content).filterIsInstance<Button>().first { it.text.toString() == "Send" }.isEnabled)
                assertTrue(all(content).filterIsInstance<TextView>().any { it.text.toString().contains("COMPARE CODES IN PERSON") })
                all(content).filterIsInstance<Button>().first { it.text.toString() == "Pad" }.performClick()
                assertTrue(all(content).filterIsInstance<Button>().first { it.text.toString() == "Verify codes & announce" }.isEnabled)
                assertFalse(all(content).filterIsInstance<Button>().first { it.text.toString() == "A · Create & export pad file" }.isEnabled)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
            store.forget(); store.sibling("pads").forget()
        }
    }

}
