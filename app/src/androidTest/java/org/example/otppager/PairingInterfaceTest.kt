package org.example.otppager

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingInterfaceTest {
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    @Test fun scanRequiresReviewThenSavesOppositeRoleWithoutAnotherManualStep() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val store = SecureStore(context); store.forget(); store.sibling("pads").forget()
        val invite = PairingInvite("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "B")
        val activity = instrumentation.startActivitySync(Intent(context, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val resultMethod = NetworkActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            val resume = NetworkActivity::class.java.getDeclaredMethod("onResume").apply { isAccessible = true }
            val field = NetworkActivity::class.java.getDeclaredField("pairingDialog").apply { isAccessible = true }
            fun scan(): AlertDialog {
                instrumentation.runOnMainSync {
                    resultMethod.invoke(activity, com.google.zxing.integration.android.IntentIntegrator.REQUEST_CODE, Activity.RESULT_OK,
                        Intent().putExtra("SCAN_RESULT", invite.encode()).putExtra("SCAN_RESULT_FORMAT", "QR_CODE"))
                    resume.invoke(activity)
                }
                val deadline = System.currentTimeMillis() + 10000; var dialog: AlertDialog? = null
                while (dialog == null && System.currentTimeMillis() < deadline) {
                    instrumentation.runOnMainSync { dialog = field.get(activity) as AlertDialog? }
                    if (dialog == null) Thread.sleep(40)
                }
                return checkNotNull(dialog) { "QR review did not appear" }
            }
            instrumentation.waitForIdleSync(); Thread.sleep(250)
            val first = scan()
            instrumentation.runOnMainSync {
                assertTrue(first.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val fields = all(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                assertTrue(fields.first { it.hint.toString() == "Relay access token" }.text.isEmpty())
                first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            instrumentation.waitForIdleSync()
            assertNull(store.load())
            val second = scan()
            instrumentation.runOnMainSync { second.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            instrumentation.waitForIdleSync()
            val deadline = System.currentTimeMillis() + 10000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = all(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().any { it.text.toString() == "STATION B" } }
                if (!ready) Thread.sleep(40)
            }
            assertTrue("Scanned setup was not saved", ready)
            instrumentation.runOnMainSync {
                val fields = all(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>()
                assertEquals(invite.endpoint, fields.first { it.hint.toString() == "https://pager.example.com" }.text.toString())
                assertEquals(invite.token, fields.first { it.hint.toString() == "Relay access token" }.text.toString())
                assertEquals(invite.secret, fields.first { it.hint.toString() == "43-character pairing secret" }.text.toString())
                assertTrue(all(activity.findViewById(android.R.id.content)).filterIsInstance<RadioGroup>().any { it.checkedRadioButtonId == R.id.station_b })
            }
            val saved = checkNotNull(store.load())
            assertEquals(invite.secret, saved.getString("secret")); assertEquals("B", saved.getString("role"))
            assertEquals(0L, saved.getLong("counter"))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync()
            store.forget(); store.sibling("pads").forget()
        }
    }
}
