package org.example.otppager

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DraftInterfaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun views(activity: Activity) = all(activity.findViewById(android.R.id.content))
    private fun composer(activity: Activity) = views(activity).filterIsInstance<EditText>().first { it.hint.toString() == "Write a message…" }
    private fun await(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 12000; var ready = false
        while (!ready && System.currentTimeMillis() < until) { instrumentation.runOnMainSync { ready = check() }; if (!ready) Thread.sleep(40) }
        assertTrue("UI state did not settle", ready)
    }
    private fun open(): Activity {
        val activity = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync { views(activity).filterIsInstance<Switch>().forEach { it.isChecked = false } }
        await { composer(activity).isEnabled }
        return activity
    }
    private fun close(activity: Activity) {
        instrumentation.runOnMainSync { activity.finish() }; await { activity.isDestroyed }
        StationRuntime.worker.submit {}.get(12, TimeUnit.SECONDS)
    }
    private fun cleanup(ids: List<String>) {
        ids.forEach { id -> SecureStore(context, id).sibling("draft").forget(); SecureStore(context, id).forget(); SecureStore(context, id).sibling("pads").forget() }
        SecureStore(context, "chat-index").forget(); SecureStore(context, "chat-index-claims").forget()
    }
    @Test fun switchingFriendsAndRestartingRestoreSeparateDraftsWithoutDiscardPrompts() {
        cleanup(listOf("station-v2")); val ids = mutableListOf("station-v2"); var activity: Activity? = null
        try {
            val book = ChatBook(context); book.rename("Alpha")
            book.engine().configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val beta = book.add("Beta"); ids.add(beta.id)
            val a = open(); activity = a
            instrumentation.runOnMainSync {
                composer(a).setText("Draft for Alpha")
                NetworkActivity::class.java.getDeclaredMethod("switchChat", String::class.java).apply { isAccessible = true }.invoke(a, beta.id)
            }
            await { views(a).filterIsInstance<Button>().any { it.text.toString() == "Beta" } && composer(a).isEnabled && composer(a).text.isEmpty() }
            instrumentation.runOnMainSync { composer(a).setText("Draft for Beta") }
            close(a); activity = null
            val b = open(); activity = b
            await { composer(b).text.toString() == "Draft for Beta" }
            instrumentation.runOnMainSync { NetworkActivity::class.java.getDeclaredMethod("switchChat", String::class.java).apply { isAccessible = true }.invoke(b, "station-v2") }
            await { composer(b).text.toString() == "Draft for Alpha" }
            assertTrue(ChatBook(context).summary(beta.id).draft)
        } finally { activity?.let(::close); cleanup(ids) }
    }
    @Test fun sendingDoesNotEraseNewTypingWhileWorkerIsBusy() {
        cleanup(listOf("station-v2")); var activity: Activity? = null
        val gate = CountDownLatch(1)
        try {
            val book = ChatBook(context); val engine = book.engine()
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            engine.generatePad().fill(0); engine.confirmPad(engine.view().padCheck!!.takeLast(8))
            SecureStore(context).save(JSONObject(engine.state.toString()).put("peerReady", true))
            val a = open(); activity = a
            val entered = CountDownLatch(1)
            StationRuntime.worker.execute { entered.countDown(); gate.await(10, TimeUnit.SECONDS) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                composer(a).setText("First message")
                val send = views(a).filterIsInstance<Button>().first { it.text.toString() == "Send" }
                assertTrue(send.isEnabled); send.performClick()
                composer(a).setText("Second draft")
            }
            gate.countDown()
            await { composer(a).text.toString() == "Second draft" && views(a).filterIsInstance<TextView>().any { it.text.toString() == "First message" } }
            close(a); activity = null
            activity = open(); await { composer(activity!!).text.toString() == "Second draft" }
            assertEquals(1, StationEngine(SecureStore(context)).view().messages.count { !it.incoming })
        } finally { gate.countDown(); activity?.let(::close); cleanup(listOf("station-v2")) }
    }
}
