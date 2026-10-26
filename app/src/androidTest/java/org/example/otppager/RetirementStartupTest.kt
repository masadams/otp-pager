package org.example.otppager

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RetirementStartupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun unreadableJournalEnumerationDisablesVaultAccessWithoutResettingData() {
        val slot = "startup-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val store = SecureStore(context, slot)
        val broken = File(context.cacheDir, slot)
        try {
            store.save(JSONObject().put("version", 2).put("text", "Preserve saved data"))
            broken.writeText("Not a directory")
            val wrapper = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = broken }
            RetirementStartup.recover(wrapper)
            assertTrue(RetirementStartup.failed)
            try { store.load(); fail("Unreadable recovery silently allowed vault access") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("recovery unavailable")) }
            RetirementStartup.recover(context)
            assertFalse(RetirementStartup.failed)
            assertEquals("Preserve saved data", store.load()!!.getString("text"))
        } finally { broken.delete(); RetirementStartup.recover(context); store.forget() }
    }
    @Test fun oneIncompleteRetirementDoesNotStopRecoveryOrDisableUnrelatedContacts() {
        fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
        val broken = root(); val other = root()
        val path = File(context.noBackupFilesDir, "$broken.bin")
        val neighbor = SecureStore(context, other)
        try {
            assertTrue(path.mkdir()); File(path, "block-deletion").writeText("Fixture")
            VaultLifecycle.marker(context.noBackupFilesDir, broken).writeText("OTP Pager retirement v1\npending\n")
            neighbor.save(JSONObject().put("version", 2).put("text", "Unrelated contact"))
            RetirementStartup.recover(context)
            assertFalse(RetirementStartup.failed); assertTrue(RetirementStartup.incomplete >= 1)
            assertEquals("Unrelated contact", neighbor.load()!!.getString("text"))
            try { SecureStore(context, broken).load(); fail("Incomplete retirement reopened") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("retired")) }
        } finally {
            File(path, "block-deletion").delete(); path.delete(); neighbor.forget()
            VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, broken)).forEach { it.delete() }
            RetirementStartup.recover(context)
        }
    }
}
