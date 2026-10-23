package org.example.otppager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
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
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @Test fun capturesActualEinkInterfaceWithFictionalTraffic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SecureStore(context); store.forget()
        val masterSecret = Protocol.randomSecret()
        val engine = StationEngine(store)
        engine.configure("https://pager.example.com", "x".repeat(43), masterSecret, "A")
        engine.send("The north gate is clear. Meet at 22:00.")
        val state = JSONObject(engine.state.toString())
        state.getJSONArray("outbox").getJSONObject(0).put("status", "delivered")
        val master = Protocol.master(masterSecret)
        val incoming = Protocol.seal(master, 'B', 1, JSONObject().put("kind", "text").put("text", "Understood. Bringing the maps.").toString())
        master.fill(0)
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
                fun capture(name: String) {
                    // Draw our own view tree, keeping FLAG_SECURE enabled. No real pairing data.
                    val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                    content.draw(Canvas(image))
                    val target = File(context.getExternalFilesDir(null), "screenshots/$name.png")
                    target.parentFile!!.mkdirs()
                    target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
                capture("messages")
                all(content).filterIsInstance<Button>().first { it.text.toString() == "Link" }.performClick()
                content.measure(View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(content.height, View.MeasureSpec.EXACTLY))
                content.layout(0, 0, content.width, content.height)
                capture("link")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            store.forget()
        }
    }
}
