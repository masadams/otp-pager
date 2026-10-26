package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore

/** Host finishes seed instrumentation, force-stops the process, then starts verify instrumentation. */
@RunWith(AndroidJUnit4::class)
class RetirementRestartTest {
    @Test fun applicationRecoversTerminalGenerationBeforeAnyVaultIsOpened() {
        val phase = InstrumentationRegistry.getArguments().getString("retirementPhase")
        assumeTrue("Host-driven cold process test only", phase in listOf("seed", "verify"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = "chat-00000000000000000001"; val other = "chat-00000000000000000002"
        val store = SecureStore(context, root); val neighbor = SecureStore(context, other)
        if (phase == "seed") {
            store.save(JSONObject().put("version", 2).put("text", "Cold restart fixture"))
            store.sibling("draft").save(JSONObject().put("version", 2).put("text", "Draft fixture"))
            store.sibling("pads").save(JSONObject().put("version", 2).put("counter", 19))
            store.identity().card(true)
            neighbor.save(JSONObject().put("version", 2).put("text", "Keep other contact"))
            // Simulate power loss after a durable intent, before key/file cleanup. No index is needed.
            val marker = VaultLifecycle.marker(context.noBackupFilesDir, root)
            FileOutputStream(marker).use { it.write("OTP Pager retirement v1\npending\n".toByteArray()); it.fd.sync() }
            VaultLifecycle.syncDirectory(context.noBackupFilesDir)
        } else {
            try {
                assertTrue(context.applicationContext is PagerApplication)
                assertFalse(RetirementStartup.failed)
                assertEquals("Keep other contact", neighbor.load()!!.getString("text"))
                assertTrue(File(context.noBackupFilesDir, "$root-pads.bin").isFile)
                for (slot in listOf(root, "$root-draft"))
                    assertTrue(VaultLifecycle.variants(File(context.noBackupFilesDir, "$slot.bin")).none { it.exists() })
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                for (alias in listOf("otp-pager-$root", "otp-pager-$root-draft", "otp-pager-$root-device"))
                    assertFalse(keys.containsAlias(alias))
                try { store.load(); fail("Cold start reopened terminal generation") } catch (_: IllegalStateException) {}
                val marker = VaultLifecycle.marker(context.noBackupFilesDir, root)
                assertEquals("OTP Pager retirement v1\ncomplete\n", marker.readText())
            } finally {
                for (slot in listOf(root, "$root-draft", "$root-pads", other)) SecureStore(context, slot).forget()
                VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, root)).forEach { it.delete() }
            }
        }
    }
}
